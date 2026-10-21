package org.example.payment;

import org.example.config.DatabaseConnection;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;

public final class PaymentMessaging {
    private PaymentMessaging() {
    }

    public static String text(String event, long booking, long paise, String link, Instant expiry) {
        String prefix = "Parking booking #" + booking + ": INR " + BigDecimal.valueOf(paise, 2) + ". ";
        return prefix + switch (event) {
            case "LINK" -> "Pay securely: " + link + " . Payment window ends " + expiry + ".";
            case "SUCCESS" -> "Payment successful. Checkout completed.";
            case "FAILED" ->
                    "Razorpay confirmed a payment attempt failed. Checkout is not confirmed. " + retryText(link, expiry);
            case "FAILED_NETWORK" ->
                    "Razorpay confirmed a failed payment attempt with a connection or timeout problem. Checkout is not confirmed. " + retryText(link, expiry);
            case "FAILED_SERVER" ->
                    "Razorpay confirmed a failed payment attempt because its payment service encountered an error. Checkout is not confirmed. " + retryText(link, expiry);
            case "CLOSED" ->
                    "Payment window closed. Payment may still be processing; checkout is not confirmed. Check payment status.";
            case "CLIENT_ERROR" ->
                    "Checkout encountered an error. Payment status is unconfirmed. Check status before retrying.";
            case "SERVER_ERROR" ->
                    "The payment service is temporarily unavailable. Payment status is unconfirmed. Do not pay again until the parking staff checks the status.";
            case "EXPIRED" -> "Payment window expired. Check status before starting another payment.";
            case "REVIEW" -> "Payment needs administrator review. Do not pay again until it is resolved.";
            default -> throw new IllegalArgumentException("Unsupported payment message event.");
        };
    }

    public static boolean validLink(String link, Instant expiry) {
        return link != null && !link.isBlank() && expiry != null && expiry.isAfter(Instant.now());
    }

    public static boolean smsLinkEvent(String eventKey) {
        return eventKey.startsWith("FAILED:");
    }

    private static String retryText(String link, Instant expiry) {
        return validLink(link, expiry)
                ? "We are resending the same payment link: " + link + " . Retry before " + expiry + "; the expiry has not been extended."
                : "The payment link is no longer available. Ask parking staff to check status and send a fresh link.";
    }

    public static void enqueue(Connection c, UUID id, String key, String event) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT * FROM payment_request WHERE request_id=?")) {
            s.setObject(1, id);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) return;
                if (r.getString("customer_phone") == null) return;
                String requestStatus = r.getString("status");
                if ((event.equals("CLOSED") || event.equals("CLIENT_ERROR") || event.equals("EXPIRED"))
                        && (requestStatus.equals("PAID") || requestStatus.equals("REVIEW"))) return;
                if (key.startsWith("FAILED:")
                        && (requestStatus.equals("PAID") || requestStatus.equals("REVIEW")
                        || requestStatus.equals("EXPIRED"))) return;
                String message = text(event, r.getLong("booking_id"), r.getLong("amount_paise"),
                        r.getString("payment_link_url"), r.getTimestamp("expires_at").toInstant());
                for (String channel : new String[]{"SMS", "WHATSAPP"}) {
                    // Razorpay notification API sends links, not arbitrary success/error SMS.
                    if (channel.equals("SMS") && (!smsLinkEvent(key)
                            || !validLink(r.getString("payment_link_url"), r.getTimestamp("expires_at").toInstant())
                            || !requestStatus.equals("PENDING"))) continue;
                    if (!r.getBoolean(channel.equals("SMS") ? "sms_consent" : "whatsapp_consent")) continue;
                    try (PreparedStatement insert = c.prepareStatement("INSERT INTO payment_message_outbox(request_id,event_key,channel,recipient,message_text) "
                            + "VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING")) {
                        insert.setObject(1, id);
                        insert.setString(2, key);
                        insert.setString(3, channel);
                        insert.setString(4, r.getString("customer_phone"));
                        insert.setString(5, message);
                        insert.executeUpdate();
                    }
                }
            }
        }
    }

    public static String failureEvent(com.fasterxml.jackson.databind.JsonNode payment) {
        String reason = (payment.path("error_code").asText() + " " + payment.path("error_reason").asText())
                .toLowerCase(java.util.Locale.ROOT);
        if (reason.contains("network") || reason.contains("timeout") || reason.contains("timed_out"))
            return "FAILED_NETWORK";
        if (reason.contains("server_error") || reason.contains("server_failure")) return "FAILED_SERVER";
        return "FAILED";
    }

    public static void browserEvent(UUID id, String event) throws Exception {
        if (!event.equals("CLOSED") && !event.equals("CLIENT_ERROR"))
            throw new IllegalArgumentException("Invalid checkout event.");
        // A browser signal can only send an unconfirmed status. It can never mark a payment failed or successful.
        try (Connection c = DatabaseConnection.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement s = c.prepareStatement("SELECT request_id FROM payment_request WHERE request_id=? FOR UPDATE")) {
                s.setObject(1, id);
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next()) throw new IllegalArgumentException("Payment request not found.");
                }
                enqueue(c, id, event, event);
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    public static void serverError(UUID id) {
        try (Connection c = DatabaseConnection.getConnection()) {
            c.setAutoCommit(false);
            try {
                enqueue(c, id, "SERVER_ERROR", "SERVER_ERROR");
                c.commit();
            } catch (Exception e) {
                c.rollback();
            }
        } catch (
                Exception ignored) { /* A database outage cannot persist a message; background reconciliation retries later. */ }
    }
}
