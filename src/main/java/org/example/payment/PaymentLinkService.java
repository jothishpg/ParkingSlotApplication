package org.example.payment;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.config.DatabaseConnection;

import java.sql.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Native Razorpay links: provider-owned payment page, expiry and SMS notification.
 */
public final class PaymentLinkService {
    private PaymentLinkService() {
    }

    public static String linkId(UUID request) throws Exception {
        try (Connection c = DatabaseConnection.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT gateway_payment_link_id FROM payment_request WHERE request_id=?")) {
            s.setObject(1, request);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new IllegalArgumentException("Payment request not found.");
                return r.getString(1);
            }
        }
    }

    public static String phone(UUID request) throws Exception {
        try (Connection c = DatabaseConnection.getConnection(); PreparedStatement s = c.prepareStatement("SELECT customer_phone FROM payment_request WHERE request_id=?")) {
            s.setObject(1, request);
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getString(1);
            }
        }
    }

    public static boolean matches(PaymentService.Checkout request, JsonNode link) {
        return request.id().toString().equals(link.path("reference_id").asText()) && request.paise() == link.path("amount").asLong(-1)
                && link.path("currency").asText().equals("INR") && !link.path("accept_partial").asBoolean(true);
    }

    public static void store(UUID request, JsonNode link) throws Exception {
        PaymentService.Checkout checkout = PaymentService.get(request);
        String id = RazorpayClient.text(link, "id"), url = RazorpayClient.text(link, "short_url");
        if (!id.matches("plink_[A-Za-z0-9]+") || !url.startsWith("https://") || !matches(checkout, link) || link.path("expire_by").asLong() <= 0)
            throw new IllegalStateException("Payment link details do not match this request. Reconcile before collecting again.");
        try (Connection c = DatabaseConnection.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement s = c.prepareStatement("UPDATE payment_request SET gateway_payment_link_id=?,payment_link_url=?,"
                    + "expires_at=?,status=CASE WHEN status='CREATING' THEN 'PENDING' ELSE status END "
                    + "WHERE request_id=? AND (gateway_payment_link_id IS NULL OR gateway_payment_link_id=?)")) {
                s.setString(1, id);
                s.setString(2, url);
                s.setTimestamp(3, Timestamp.from(Instant.ofEpochSecond(link.path("expire_by").asLong())));
                s.setObject(4, request);
                s.setString(5, id);
                if (s.executeUpdate() != 1)
                    throw new IllegalStateException("Conflicting payment link. Ask an administrator to reconcile.");
                PaymentMessaging.enqueue(c, request, "LINK", "LINK");
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    public static String reconcile(UUID id, boolean recover) throws Exception {
        PaymentService.Checkout c = PaymentService.get(id);
        if (c.status().equals("PAID")) return "PAID";
        MerchantConfig config = MerchantConfig.load(c.config());
        String linkId = linkId(id);
        JsonNode link;
        if (linkId == null) {
            if (!recover) return "PENDING";
            JsonNode collection = RazorpayClient.api("payment_links?reference_id=" + id, null, config.authorization());
            link = null;
            JsonNode items = collection.has("payment_links") ? collection.path("payment_links") : collection.path("items");
            for (JsonNode item : items)
                if (matches(c, item)) {
                    if (link != null)
                        throw new IllegalStateException("Multiple matching payment links. Manual review required.");
                    link = item;
                }
            if (link == null)
                throw new IllegalStateException("Payment link creation is unconfirmed. Check Razorpay before collecting again.");
            store(id, link);
            linkId = RazorpayClient.text(link, "id");
        } else link = RazorpayClient.api("payment_links/" + linkId, null, config.authorization());
        if (!linkId.equals(link.path("id").asText()) || !matches(c, link))
            throw new IllegalStateException("Unexpected payment link details.");
        String result = c.status();
        boolean authorized = false;
        boolean foundPayment = false;
        for (JsonNode item : link.path("payments")) {
            String paymentId = item.path("payment_id").asText();
            if (!paymentId.matches("pay_[A-Za-z0-9]+"))
                throw new IllegalStateException("Unexpected payment details on the payment link.");
            JsonNode payment = RazorpayClient.api("payments/" + paymentId, null, config.authorization());
            if (!paymentId.equals(payment.path("id").asText())
                    || (payment.hasNonNull("payment_link_id") && !linkId.equals(payment.path("payment_link_id").asText())))
                throw new IllegalStateException("Unexpected provider payment response.");
            foundPayment = true;
            authorized |= payment.path("status").asText().equals("authorized");
            String processed = PaymentService.process(PaymentService.get(id), payment, null, null);
            if (processed.equals("COMPLETED") || processed.equals("REVIEW")) result = processed;
        }
        if (result.equals("COMPLETED") || result.equals("REVIEW")) return result;
        if (link.path("status").asText().equals("paid") && !foundPayment)
            throw new IllegalStateException("Razorpay reports this link as paid, but has not returned its payment details.");
        if (!authorized && (link.path("status").asText().equals("expired") || link.path("status").asText().equals("cancelled"))) {
            expire(id);
            return PaymentService.get(id).status();
        }
        return PaymentService.get(id).status();
    }

    public static void expire(UUID id) throws Exception {
        try (Connection c = DatabaseConnection.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement s = c.prepareStatement("UPDATE payment_request SET status='EXPIRED' WHERE request_id=? AND status IN ('CREATING','PENDING')")) {
                s.setObject(1, id);
                if (s.executeUpdate() > 0) PaymentMessaging.enqueue(c, id, "EXPIRED", "EXPIRED");
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    public static void rejectCreation(UUID id) throws Exception {
        try (Connection c = DatabaseConnection.getConnection(); PreparedStatement s = c.prepareStatement(
                "UPDATE payment_request SET status='EXPIRED' WHERE request_id=? AND status='CREATING' AND gateway_payment_link_id IS NULL")) {
            s.setObject(1, id);
            s.executeUpdate();
        }
    }

    public static String callbackPayload(String linkId, String reference, String status, String payment) {
        return linkId + "|" + reference + "|" + status + "|" + payment;
    }

    public static String verifyCallback(UUID id, String link, String reference, String status, String payment, String signature) throws Exception {
        PaymentService.Checkout c = PaymentService.get(id);
        MerchantConfig config = MerchantConfig.load(c.config());
        if (!id.toString().equals(reference) || !java.util.Objects.equals(linkId(id), link)
                || payment == null || !payment.matches("pay_[A-Za-z0-9]+") || !"paid".equals(status)
                || !PaymentCrypto.verify(config.secret(), callbackPayload(link, id.toString(), status, payment).getBytes(java.nio.charset.StandardCharsets.UTF_8), signature))
            throw new IllegalArgumentException("Payment link signature verification failed.");
        // A valid redirect is not sufficient: reconcile the link and captured payment through the provider API.
        return reconcile(id, false);
    }

    public static void signedEvent(long config, JsonNode event, String eventId) throws Exception {
        JsonNode link = event.path("payload").path("payment_link").path("entity");
        UUID id;
        try {
            id = UUID.fromString(link.path("reference_id").asText());
        } catch (IllegalArgumentException e) {
            return;
        }
        PaymentService.Checkout c;
        try {
            c = PaymentService.get(id);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (c.config() != config || !matches(c, link))
            throw new IllegalArgumentException("Payment link event mismatch.");
        String known = linkId(id);
        if (known != null && !known.equals(link.path("id").asText()))
            throw new IllegalArgumentException("Payment link ID mismatch.");
        if (known == null) store(id, link);
        JsonNode payment = event.path("payload").path("payment").path("entity");
        if (!payment.isMissingNode() && !payment.isNull() && payment.has("id")) {
            PaymentService.process(PaymentService.get(id), payment, config, eventId);
        } else if (link.path("amount_paid").asLong() == 0
                && (event.path("event").asText().equals("payment_link.expired") || event.path("event").asText().equals("payment_link.cancelled")))
            expire(id);
    }

    public static String sendSms(UUID request, String phone) throws Exception {
        String id = linkId(request);
        if (id == null) throw new IllegalStateException("Payment link is not ready for SMS.");
        PaymentService.Checkout c = PaymentService.get(request);
        if (!c.status().equals("PENDING") || !c.expires().isAfter(Instant.now()))
            throw new IllegalStateException("Payment link is no longer available for SMS.");
        if (!java.util.Objects.equals(phone, phone(request)))
            throw new IllegalStateException("Customer phone changed.");
        MerchantConfig config = MerchantConfig.load(c.config());
        JsonNode response = RazorpayClient.api("payment_links/" + id + "/notify_by/sms", java.util.Map.of(), config.authorization());
        if (response != null && response.has("success") && !response.path("success").asBoolean())
            throw new IllegalStateException("Razorpay did not accept the SMS notification.");
        return id;
    }
}
