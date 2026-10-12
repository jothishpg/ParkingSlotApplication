package org.example.listener;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import org.example.config.DatabaseConnection;
import org.example.notification.WhatsAppNotificationService;
import org.example.payment.PaymentMessaging;
import org.example.payment.PaymentLinkService;
import org.example.payment.PaymentService;
import org.example.payment.RazorpayPaymentResource;

import java.sql.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Keeps notification network calls outside the webhook response and checkout transaction.
 */
@WebListener
public class PaymentNotificationListener implements ServletContextListener {
    private static final Logger LOG = Logger.getLogger(PaymentNotificationListener.class.getName());
    private ScheduledExecutorService worker;

    @Override
    public void contextInitialized(ServletContextEvent event) {
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "payment-notifications");
            t.setDaemon(true);
            return t;
        });
        worker.scheduleWithFixedDelay(this::dispatch, 2, 2, TimeUnit.SECONDS);
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        if (worker != null) worker.shutdownNow();
    }

    private void dispatch() {
        try {
            for (int i = 0; i < 10 && !Thread.currentThread().isInterrupted(); i++) if (!deliverOne()) break;
            reconcileOne();
        } catch (Exception e) {
            LOG.warning("Payment notification dispatch failed: " + e.getClass().getSimpleName());
        }
    }

    private void reconcileOne() throws Exception {
        java.util.UUID id;
        try (Connection c = DatabaseConnection.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement s = c.prepareStatement("SELECT request_id FROM payment_request WHERE status IN ('CREATING','PENDING') "
                    + "AND next_reconcile_at<=CURRENT_TIMESTAMP ORDER BY next_reconcile_at LIMIT 1 FOR UPDATE SKIP LOCKED");
                 ResultSet r = s.executeQuery()) {
                if (!r.next()) {
                    c.commit();
                    return;
                }
                id = r.getObject(1, java.util.UUID.class);
                try (PreparedStatement u = c.prepareStatement("UPDATE payment_request SET next_reconcile_at=CURRENT_TIMESTAMP+INTERVAL '2 minutes' WHERE request_id=?")) {
                    u.setObject(1, id);
                    u.executeUpdate();
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
        boolean failed = false;
        try {
            RazorpayPaymentResource.reconcile(id, true);
        } catch (Exception e) {
            failed = true;
            PaymentMessaging.serverError(id);
        }
        try (Connection c = DatabaseConnection.getConnection(); PreparedStatement s = c.prepareStatement("UPDATE payment_request "
                + "SET next_reconcile_at=CURRENT_TIMESTAMP+INTERVAL '30 seconds',reconcile_errors=CASE WHEN ? THEN reconcile_errors+1 ELSE 0 END WHERE request_id=?")) {
            s.setBoolean(1, failed);
            s.setObject(2, id);
            s.executeUpdate();
        }
    }

    private boolean deliverOne() throws Exception {
        long message, booking;
        int attempt;
        java.util.UUID request;
        String channel, phone, text, event;
        // Commit a one-minute claim before making network requests; checkout and webhooks never wait for a sender.
        try (Connection c = DatabaseConnection.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement s = c.prepareStatement("SELECT m.*,pr.booking_id,pr.amount_paise,pr.payment_link_url,pr.status AS request_status,pr.expires_at,pr.sms_consent,pr.whatsapp_consent "
                    + "FROM payment_message_outbox m JOIN payment_request pr ON pr.request_id=m.request_id "
                    + "WHERE m.status IN ('READY','PROCESSING') AND m.next_attempt_at<=CURRENT_TIMESTAMP "
                    + "ORDER BY m.created_at,m.message_id LIMIT 1 FOR UPDATE OF m SKIP LOCKED"); ResultSet r = s.executeQuery()) {
                if (!r.next()) {
                    c.commit();
                    return false;
                }
                message = r.getLong("message_id");
                booking = r.getLong("booking_id");
                request = r.getObject("request_id", java.util.UUID.class);
                channel = r.getString("channel");
                phone = r.getString("recipient");
                text = r.getString("message_text");
                event = r.getString("event_key");
                attempt = r.getInt("attempts") + 1;
                String state = r.getString("request_status");
                boolean enabled = r.getBoolean(channel.equals("SMS") ? "sms_consent" : "whatsapp_consent");
                boolean valid = state.equals("PENDING") && PaymentMessaging.validLink(r.getString("payment_link_url"), r.getTimestamp("expires_at").toInstant());
                if (channel.equals("WHATSAPP") && event.startsWith("FAILED:") && !valid)
                    text = PaymentMessaging.text("FAILED", booking, r.getLong("amount_paise"), null, r.getTimestamp("expires_at").toInstant());
                boolean stale = !enabled || (state.equals("PAID") && !event.equals("SUCCESS") && !event.startsWith("REVIEW"))
                        || (state.equals("REVIEW") && !event.startsWith("REVIEW"))
                        || (event.equals("LINK") && !valid)
                        || (channel.equals("SMS") && PaymentMessaging.smsLinkEvent(event) && !valid);
                String status = stale ? "SKIPPED" : (attempt > 5 ? "FAILED" : (channel.equals("SMS") && !PaymentMessaging.smsLinkEvent(event) ? "UNSUPPORTED" : "PROCESSING"));
                try (PreparedStatement update = c.prepareStatement("UPDATE payment_message_outbox SET status=?,attempts=?,next_attempt_at=CURRENT_TIMESTAMP+INTERVAL '1 minute' WHERE message_id=?")) {
                    update.setString(1, status);
                    update.setInt(2, attempt);
                    update.setLong(3, message);
                    update.executeUpdate();
                }
                c.commit();
                if (!status.equals("PROCESSING")) return true;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
        String provider = null, error = null;
        try {
            if (channel.equals("SMS")) provider = PaymentLinkService.sendSms(request, phone);
            else if (channel.equals("WHATSAPP")) {
                boolean accepted = event.equals("SUCCESS")
                        ? WhatsAppNotificationService.sendPaymentCheckoutConfirmation(booking, phone)
                        : WhatsAppNotificationService.sendPaymentMessage(booking, phone, text);
                if (!accepted) throw new IllegalStateException("WhatsApp message was not accepted.");
            } else throw new IllegalStateException("Unsupported message channel.");
        } catch (Exception e) {
            error = e.getClass().getSimpleName();
        }
        try (Connection c = DatabaseConnection.getConnection(); PreparedStatement s = c.prepareStatement(
                "UPDATE payment_message_outbox SET status=?,provider_message_id=?,last_error=?,"
                        + "submitted_at=CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,"
                        + "next_attempt_at=CURRENT_TIMESTAMP+(? * INTERVAL '1 second') WHERE message_id=? AND status='PROCESSING' AND attempts=?")) {
            s.setString(1, error == null ? "SUBMITTED" : (attempt >= 5 ? "FAILED" : "READY"));
            s.setString(2, provider);
            s.setString(3, error);
            s.setBoolean(4, error == null);
            s.setInt(5, Math.min(120, attempt * 10));
            s.setLong(6, message);
            s.setInt(7, attempt);
            s.executeUpdate();
        }
        return true;
    }
}
