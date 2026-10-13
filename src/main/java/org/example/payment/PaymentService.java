package org.example.payment;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.booking.BookingResource;
import org.example.config.DatabaseConnection;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

public final class PaymentService {
    public static final int PAYMENT_WINDOW_SECONDS = 15 * 60;
    public static final int PAYMENT_LINK_TRANSIT_SECONDS = 30;

    private PaymentService() {
    }

    public record Checkout(UUID id, long booking, long config, long paise, long minutes, Instant cutoff,
                           Instant expires, String order, String status) {
    }

    public static Checkout read(ResultSet r) throws SQLException {
        return new Checkout(r.getObject("request_id", UUID.class), r.getLong("booking_id"), r.getLong("config_id"),
                r.getLong("amount_paise"), r.getLong("duration_minutes"), r.getTimestamp("pricing_cutoff").toInstant(),
                r.getTimestamp("expires_at").toInstant(), r.getString("gateway_order_id"), r.getString("status"));
    }

    public static Checkout get(UUID id) throws Exception {
        try (Connection c = DatabaseConnection.getConnection(); PreparedStatement s = c.prepareStatement("SELECT * FROM payment_request WHERE request_id=?")) {
            s.setObject(1, id);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new IllegalArgumentException("Payment request not found.");
                return read(r);
            }
        }
    }

    public static Checkout start(long booking, long user, boolean sms, boolean whatsapp) throws Exception {
        String publicBase = RazorpayClient.required("APP_BASE_URL").replaceAll("/$", "");
        if (!publicBase.startsWith("https://"))
            throw new IllegalStateException("Use a public HTTPS APP_BASE_URL for payment links.");
        MerchantConfig config = MerchantConfig.load(null);
        Checkout checkout;
        try (Connection c = DatabaseConnection.getConnection()) {
            c.setAutoCommit(false);
            try {
                int vehicleType;
                try (PreparedStatement s = c.prepareStatement("SELECT f.vehicle_type_id FROM booking b JOIN slot s ON s.slot_id=b.slot_id "
                        + "JOIN floor f ON f.floor_id=s.floor_id WHERE b.booking_id=? FOR UPDATE OF b")) {
                    s.setLong(1, booking);
                    try (ResultSet r = s.executeQuery()) {
                        if (!r.next()) throw new IllegalArgumentException("Booking not found.");
                        vehicleType = r.getInt(1);
                    }
                }
                ensureActive(c, booking);
                try (PreparedStatement s = c.prepareStatement("SELECT * FROM payment_request WHERE booking_id=? AND status IN ('CREATING','PENDING','REVIEW') ORDER BY pricing_cutoff DESC FOR UPDATE")) {
                    s.setLong(1, booking);
                    try (ResultSet r = s.executeQuery()) {
                        if (r.next()) {
                            Checkout previous = read(r);
                            if (previous.status.equals("REVIEW") || previous.status.equals("CREATING"))
                                throw new IllegalStateException("This booking requires payment reconciliation. Ask an admin to check Razorpay before collecting again.");
                            if (previous.expires.isAfter(Instant.now())) {
                                c.commit();
                                return previous;
                            }
                            // Resolve the earlier request outside this transaction before collecting again.
                            String previousLink = r.getString("gateway_payment_link_id");
                            if (previousLink != null)
                                throw new IllegalStateException("The previous payment link expired. Check payment status before collecting again.");
                            JsonNode payments = RazorpayClient.api("orders/" + previous.order + "/payments", null, MerchantConfig.load(previous.config).authorization());
                            for (JsonNode p : payments.path("items")) {
                                if (p.path("status").asText().equals("captured") || p.path("status").asText().equals("authorized"))
                                    throw new IllegalStateException("An earlier payment is awaiting reconciliation. Check payment status before retrying.");
                            }
                            try (PreparedStatement u = c.prepareStatement("UPDATE payment_request SET status='EXPIRED' WHERE request_id=?")) {
                                u.setObject(1, previous.id);
                                u.executeUpdate();
                            }
                        }
                    }
                }
                Instant cutoff;
                long minutes;
                try (PreparedStatement s = c.prepareStatement("SELECT CURRENT_TIMESTAMP AS cutoff, "
                        + "CEIL(GREATEST(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP-start_time))/60,1))::bigint AS minutes FROM booking WHERE booking_id=?")) {
                    s.setLong(1, booking);
                    try (ResultSet r = s.executeQuery()) {
                        r.next();
                        cutoff = r.getTimestamp(1).toInstant();
                        minutes = r.getLong(2);
                    }
                }
                long paise = BookingResource.calculateAmount(c, vehicleType, minutes).movePointRight(2).longValueExact();
                String phone;
                try (PreparedStatement s = c.prepareStatement("SELECT phone_number FROM booking WHERE booking_id=?")) {
                    s.setLong(1, booking);
                    try (ResultSet r = s.executeQuery()) {
                        r.next();
                        phone = r.getString(1);
                    }
                }
                if ((sms || whatsapp) && (phone == null || !phone.matches("\\d{10}")))
                    throw new IllegalArgumentException("A valid 10-digit customer phone number is required for messages.");
                checkout = new Checkout(UUID.randomUUID(), booking, config.id(), paise, minutes, cutoff, cutoff.plusSeconds(PAYMENT_WINDOW_SECONDS), null, "CREATING");
                try (PreparedStatement s = c.prepareStatement("INSERT INTO payment_request(request_id,booking_id,config_id,initiated_by,amount_paise,pricing_cutoff,duration_minutes,expires_at,"
                        + "customer_phone,sms_consent,whatsapp_consent) VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
                    s.setObject(1, checkout.id);
                    s.setLong(2, booking);
                    s.setLong(3, config.id());
                    s.setLong(4, user);
                    s.setLong(5, paise);
                    s.setTimestamp(6, Timestamp.from(cutoff));
                    s.setLong(7, minutes);
                    s.setTimestamp(8, Timestamp.from(checkout.expires));
                    s.setString(9, phone);
                    s.setBoolean(10, sms);
                    s.setBoolean(11, whatsapp);
                    s.executeUpdate();
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
        // Persist the intent first. A timeout may still create a link; reconcile by reference instead of recreating it.
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("amount", checkout.paise);
            body.put("currency", "INR");
            body.put("accept_partial", false);
            body.put("reference_id", checkout.id.toString());
            body.put("description", "Parking booking #" + booking);
            body.put("expire_by", Instant.now().plusSeconds(PAYMENT_WINDOW_SECONDS + PAYMENT_LINK_TRANSIT_SECONDS).getEpochSecond());
            body.put("notify", paymentLinkNotifications());
            body.put("reminder_enable", false);
            String customerPhone = PaymentLinkService.phone(checkout.id);
            if (customerPhone != null && customerPhone.matches("\\d{10}"))
                body.put("customer", Map.of("contact", "+91" + customerPhone));
            body.put("callback_url", publicBase + "/api/payments/razorpay/payment-link/" + checkout.id + "/callback");
            body.put("callback_method", "get");
            JsonNode link = RazorpayClient.api("payment_links", body, config.authorization());
            PaymentLinkService.store(checkout.id, link);
        } catch (Exception e) {
            if (e instanceof RazorpayClient.ApiException api && api.definitiveRejection())
                PaymentLinkService.rejectCreation(checkout.id);
            PaymentMessaging.serverError(checkout.id);
            throw e;
        }
        return get(checkout.id);
    }

    public static void ensureActive(Connection c, long booking) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT 1 FROM duration_amount WHERE booking_id=?")) {
            s.setLong(1, booking);
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) throw new IllegalStateException("This booking has already checked out.");
            }
        }
    }

    static Map<String, Boolean> paymentLinkNotifications() {
        return Map.of("sms", true, "email", false);
    }

    public static String verify(UUID request, String order, String payment, String signature) throws Exception {
        if (payment == null || !payment.matches("pay_[A-Za-z0-9]+"))
            throw new IllegalArgumentException("Invalid payment ID.");
        Checkout checkout = get(request);
        MerchantConfig config = MerchantConfig.load(checkout.config);
        if (!java.util.Objects.equals(checkout.order, order) || !PaymentCrypto.verify(config.secret(),
                (checkout.order + "|" + payment).getBytes(java.nio.charset.StandardCharsets.UTF_8), signature))
            throw new IllegalArgumentException("Payment signature verification failed.");
        JsonNode provider = RazorpayClient.api("payments/" + payment, null, config.authorization());
        if (!payment.equals(provider.path("id").asText()))
            throw new IllegalArgumentException("Unexpected payment response.");
        return process(checkout, provider, null, null);
    }

    public static String process(Checkout checkout, JsonNode provider, Long eventConfig, String eventId) throws Exception {
        String result;
        try (Connection c = DatabaseConnection.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement s = c.prepareStatement("SELECT booking_id FROM booking WHERE booking_id=? FOR UPDATE")) {
                    s.setLong(1, checkout.booking);
                    try (ResultSet r = s.executeQuery()) {
                        if (!r.next()) throw new IllegalStateException("Booking not found.");
                    }
                }
                try (PreparedStatement s = c.prepareStatement("SELECT * FROM payment_request WHERE request_id=? FOR UPDATE")) {
                    s.setObject(1, checkout.id);
                    try (ResultSet r = s.executeQuery()) {
                        r.next();
                        checkout = read(r);
                    }
                }
                if (eventId != null) {
                    try (PreparedStatement s = c.prepareStatement("INSERT INTO payment_webhook_event(config_id,event_id) VALUES (?,?) ON CONFLICT DO NOTHING")) {
                        s.setLong(1, eventConfig);
                        s.setString(2, eventId);
                        if (s.executeUpdate() == 0) {
                            c.commit();
                            return "ALREADY_PROCESSED";
                        }
                    }
                }
                String payment = RazorpayClient.text(provider, "id");
                boolean already = false;
                try (PreparedStatement s = c.prepareStatement("SELECT 1 FROM payment_razorpay_detail d "
                        + "WHERE d.gateway_payment_id=? AND d.request_id=?")) {
                    s.setString(1, payment);
                    s.setObject(2, checkout.id);
                    try (ResultSet r = s.executeQuery()) {
                        already = r.next();
                    }
                }
                if (already) {
                    c.commit();
                    return "COMPLETED";
                }
                boolean closed;
                try (PreparedStatement s = c.prepareStatement("SELECT 1 FROM duration_amount WHERE booking_id=?")) {
                    s.setLong(1, checkout.booking);
                    try (ResultSet r = s.executeQuery()) {
                        closed = r.next();
                    }
                }
                result = disposition(checkout, provider, closed);
                try (PreparedStatement s = c.prepareStatement("INSERT INTO payment_attempt(gateway_payment_id,request_id,provider_status,provider_method,amount_paise,disposition) "
                        + "VALUES (?,?,?,?,?,?) ON CONFLICT(gateway_payment_id) DO UPDATE SET provider_status=EXCLUDED.provider_status,disposition=EXCLUDED.disposition,updated_at=CURRENT_TIMESTAMP WHERE payment_attempt.request_id=EXCLUDED.request_id")) {
                    s.setString(1, payment);
                    s.setObject(2, checkout.id);
                    s.setString(3, provider.path("status").asText());
                    s.setString(4, provider.path("method").asText());
                    s.setLong(5, provider.path("amount").asLong());
                    s.setString(6, result);
                    s.executeUpdate();
                }
                if (result.equals("COMPLETED")) {
                    long assigner;
                    try (PreparedStatement s = c.prepareStatement("SELECT initiated_by FROM payment_request WHERE request_id=?")) {
                        s.setObject(1, checkout.id);
                        try (ResultSet r = s.executeQuery()) {
                            r.next();
                            assigner = r.getLong(1);
                        }
                    }
                    try (PreparedStatement s = c.prepareStatement("INSERT INTO duration_amount(booking_id,end_time,total_duration,amount) VALUES (?,?,?,?)")) {
                        s.setLong(1, checkout.booking);
                        s.setTimestamp(2, Timestamp.from(checkout.cutoff));
                        s.setLong(3, checkout.minutes);
                        s.setBigDecimal(4, BigDecimal.valueOf(checkout.paise, 2));
                        s.executeUpdate();
                    }
                    long paymentId;
                    try (PreparedStatement s = c.prepareStatement("INSERT INTO payment(booking_id,payment_method_id,verified_by,payment_status) "
                            + "VALUES (?,(SELECT payment_method_id FROM payment_method WHERE method_code=?),?,'SUCCESS') RETURNING payment_id")) {
                        s.setLong(1, checkout.booking);
                        s.setString(2, "RAZORPAY");
                        s.setLong(3, assigner);
                        try (ResultSet r = s.executeQuery()) {
                            if (!r.next()) throw new SQLException("Payment insert did not return an ID.");
                            paymentId = r.getLong("payment_id");
                        }
                    }
                    try (PreparedStatement s = c.prepareStatement("INSERT INTO payment_razorpay_detail(payment_id,request_id,gateway_payment_id,gateway_method) VALUES (?,?,?,?)")) {
                        s.setLong(1, paymentId);
                        s.setObject(2, checkout.id);
                        s.setString(3, payment);
                        s.setString(4, provider.path("method").asText());
                        s.executeUpdate();
                    }
                    try (PreparedStatement s = c.prepareStatement("UPDATE slot SET state='AVAILABLE' WHERE slot_id=(SELECT slot_id FROM booking WHERE booking_id=?)")) {
                        s.setLong(1, checkout.booking);
                        s.executeUpdate();
                    }
                    try (PreparedStatement s = c.prepareStatement("UPDATE payment_request SET status='PAID' WHERE request_id=?")) {
                        s.setObject(1, checkout.id);
                        s.executeUpdate();
                    }
                    PaymentMessaging.enqueue(c, checkout.id, "SUCCESS", "SUCCESS");
                } else if (result.equals("REVIEW") && !checkout.status.equals("PAID")) {
                    try (PreparedStatement s = c.prepareStatement("UPDATE payment_request SET status='REVIEW' WHERE request_id=?")) {
                        s.setObject(1, checkout.id);
                        s.executeUpdate();
                    }
                }
                if (result.equals("FAILED") || result.equals("REVIEW"))
                    PaymentMessaging.enqueue(c, checkout.id, result + ":" + payment,
                            result.equals("FAILED") ? PaymentMessaging.failureEvent(provider) : result);
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
        return result;
    }

    public static boolean matches(Checkout c, JsonNode p) {
        return c.order != null && c.order.equals(p.path("order_id").asText()) && c.paise == p.path("amount").asLong(-1)
                && p.path("currency").asText().equals("INR") && p.path("amount_refunded").asLong() == 0;
    }

    public static String disposition(Checkout checkout, JsonNode provider, boolean bookingClosed) {
        boolean captured = "captured".equals(provider.path("status").asText()) && provider.path("captured").asBoolean();
        if (!captured) return "failed".equals(provider.path("status").asText()) ? "FAILED" : "PENDING";
        return matches(checkout, provider) && withinWindow(checkout, provider.path("created_at").asLong())
                && !bookingClosed && !checkout.status.equals("PAID") ? "COMPLETED" : "REVIEW";
    }

    public static boolean withinWindow(Checkout c, long paymentCreatedAt) {
        return paymentCreatedAt >= c.cutoff.getEpochSecond() && paymentCreatedAt < c.expires.getEpochSecond();
    }
}
