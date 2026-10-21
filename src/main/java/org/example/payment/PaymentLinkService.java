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
        String orderId = link.path("order_id").asText(null);
        if (!id.matches("plink_[A-Za-z0-9]+") || !url.startsWith("https://") || !matches(checkout, link) || link.path("expire_by").asLong() <= 0)
            throw new IllegalStateException("Payment link details do not match this request. Reconcile before collecting again.");
        if (orderId != null && !orderId.isBlank() && !orderId.matches("order_[A-Za-z0-9]+"))
            throw new IllegalStateException("Payment link has an invalid Razorpay order ID.");
        try (Connection c = DatabaseConnection.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement s = c.prepareStatement("UPDATE payment_request SET gateway_payment_link_id=?,payment_link_url=?,"
                    + "gateway_order_id=COALESCE(gateway_order_id,?),expires_at=?,"
                    + "status=CASE WHEN status='CREATING' THEN 'PENDING' ELSE status END "
                    + "WHERE request_id=? AND (gateway_payment_link_id IS NULL OR gateway_payment_link_id=?) "
                    + "AND (gateway_order_id IS NULL OR ? IS NULL OR gateway_order_id=?)")) {
                s.setString(1, id);
                s.setString(2, url);
                s.setString(3, orderId);
                s.setTimestamp(4, Timestamp.from(Instant.ofEpochSecond(link.path("expire_by").asLong())));
                s.setObject(5, request);
                s.setString(6, id);
                s.setString(7, orderId);
                s.setString(8, orderId);
                if (s.executeUpdate() != 1)
                    throw new IllegalStateException("Conflicting payment link or order. Ask an administrator to reconcile.");
                PaymentMessaging.enqueue(c, request, "LINK", "LINK");
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    public static String processFailedPaymentEvent(
            long configId, JsonNode event, String eventId) throws Exception {
        JsonNode payment = event.path("payload").path("payment").path("entity");
        String paymentId = payment.path("id").asText("");
        String orderId = payment.path("order_id").asText("");
        String eventLinkId = payment.path("payment_link_id").asText("");
        if (!"payment.failed".equals(event.path("event").asText())
                || !"failed".equals(payment.path("status").asText())
                || !paymentId.matches("pay_[A-Za-z0-9]+")
                || (!orderId.isBlank() && !orderId.matches("order_[A-Za-z0-9]+"))
                || (!eventLinkId.isBlank() && !eventLinkId.matches("plink_[A-Za-z0-9]+"))
                || (orderId.isBlank() && eventLinkId.isBlank())) {
            return "IGNORED";
        }

        UUID requestId = null;
        if (!eventLinkId.isBlank()) {
            requestId = requestIdForLink(configId, eventLinkId);
        }
        if (!orderId.isBlank()) {
            UUID orderRequestId = requestIdForOrder(configId, orderId);
            if (requestId != null && orderRequestId != null && !requestId.equals(orderRequestId)) {
                return "IGNORED";
            }
            if (requestId == null) {
                requestId = orderRequestId;
            }
        }

        JsonNode providerOrder = null;
        if (requestId == null && !orderId.isBlank()) {
            MerchantConfig config = MerchantConfig.load(configId);
            providerOrder = RazorpayClient.api("orders/" + orderId, null, config.authorization());
            if (!orderId.equals(providerOrder.path("id").asText())) {
                return "IGNORED";
            }
            requestId = parseRequestId(providerOrder.path("receipt").asText(""));
        }
        if (requestId == null) {
            return "IGNORED";
        }

        PaymentService.Checkout checkout;
        try {
            checkout = PaymentService.get(requestId);
        } catch (IllegalArgumentException exception) {
            return "IGNORED";
        }
        if (checkout.config() != configId) {
            return "IGNORED";
        }

        String savedLinkId = linkId(requestId);
        if (savedLinkId == null) {
            throw new IllegalStateException(
                    "The payment link is not saved yet; retry the failed-payment webhook.");
        }
        if (!eventLinkId.isBlank() && !savedLinkId.equals(eventLinkId)) {
            return "IGNORED";
        }
        String savedOrderId = orderId(requestId);
        if (!orderId.isBlank()) {
            if (savedOrderId != null && !savedOrderId.equals(orderId)) {
                return "IGNORED";
            }
            if (savedOrderId == null) {
                MerchantConfig config = MerchantConfig.load(configId);
                if (providerOrder == null) {
                    providerOrder = RazorpayClient.api("orders/" + orderId, null, config.authorization());
                }
                if (!orderId.equals(providerOrder.path("id").asText())
                        || !requestId.toString().equals(providerOrder.path("receipt").asText())
                        || checkout.paise() != providerOrder.path("amount").asLong(-1)
                        || !"INR".equals(providerOrder.path("currency").asText())) {
                    return "IGNORED";
                }
                JsonNode providerLink = RazorpayClient.api(
                        "payment_links/" + savedLinkId, null, config.authorization());
                if (!savedLinkId.equals(providerLink.path("id").asText())
                        || !orderId.equals(providerLink.path("order_id").asText())
                        || !matches(checkout, providerLink)) {
                    return "IGNORED";
                }
                bindOrderId(requestId, configId, orderId);
            }
        }
        if (!matchesFailedPayment(checkout, payment, savedLinkId, savedOrderId == null
                ? orderId : savedOrderId)) {
            return "IGNORED";
        }
        return PaymentService.process(checkout, payment, configId, eventId);
    }

    static boolean matchesFailedPayment(
            PaymentService.Checkout checkout, JsonNode payment,
            String savedLinkId, String savedOrderId) {
        String paymentLinkId = payment.path("payment_link_id").asText("");
        String paymentOrderId = payment.path("order_id").asText("");
        return "failed".equals(payment.path("status").asText())
                && payment.path("id").asText("").matches("pay_[A-Za-z0-9]+")
                && payment.path("amount").asLong(-1) == checkout.paise()
                && "INR".equals(payment.path("currency").asText())
                && (paymentLinkId.isBlank() || paymentLinkId.equals(savedLinkId))
                && (paymentOrderId.isBlank() || paymentOrderId.equals(savedOrderId));
    }

    private static UUID requestIdForLink(long configId, String linkId) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT request_id FROM payment_request WHERE config_id=? AND gateway_payment_link_id=?")) {
            statement.setLong(1, configId);
            statement.setString(2, linkId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getObject("request_id", UUID.class) : null;
            }
        }
    }

    private static UUID requestIdForOrder(long configId, String orderId) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT request_id FROM payment_request WHERE config_id=? AND gateway_order_id=?")) {
            statement.setLong(1, configId);
            statement.setString(2, orderId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getObject("request_id", UUID.class) : null;
            }
        }
    }

    private static String orderId(UUID requestId) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT gateway_order_id FROM payment_request WHERE request_id=?")) {
            statement.setObject(1, requestId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Payment request disappeared while processing a failure event.");
                }
                return result.getString(1);
            }
        }
    }

    private static void bindOrderId(UUID requestId, long configId, String orderId)
            throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE payment_request SET gateway_order_id=? WHERE request_id=? AND config_id=? "
                             + "AND (gateway_order_id IS NULL OR gateway_order_id=?)")) {
            statement.setString(1, orderId);
            statement.setObject(2, requestId);
            statement.setLong(3, configId);
            statement.setString(4, orderId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Could not bind the verified Razorpay order to its payment request.");
            }
        }
    }

    private static UUID parseRequestId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
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
