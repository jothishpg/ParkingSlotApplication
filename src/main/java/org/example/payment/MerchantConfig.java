package org.example.payment;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.config.DatabaseConnection;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public record MerchantConfig(long id, String type, String mode, String accountId, String publicKey,
                             String secret, String signatureSecret, boolean active) {
    public static MerchantConfig load(Long id) throws Exception {
        try (Connection c = DatabaseConnection.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement s = c.prepareStatement("SELECT * FROM razorpay_config WHERE "
                    + (id == null ? "active = TRUE" : "config_id = ?") + " FOR UPDATE")) {
                if (id != null) s.setLong(1, id);
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next()) throw new IllegalStateException("An admin must connect Razorpay first.");
                    String secret = PaymentCrypto.decrypt(r.getString("encrypted_secret"));
                    String publicKey = r.getString("public_key");
                    Timestamp expires = r.getTimestamp("access_expires_at");
                    if (r.getString("auth_type").equals("OAUTH") && expires != null
                            && expires.toInstant().isBefore(Instant.now().plusSeconds(300))) {
                        JsonNode token = RazorpayClient.oauth(Map.of("client_id", RazorpayClient.required("RAZORPAY_CLIENT_ID"),
                                "client_secret", RazorpayClient.required("RAZORPAY_CLIENT_SECRET"),
                                "grant_type", "refresh_token", "refresh_token",
                                PaymentCrypto.decrypt(r.getString("encrypted_refresh_token"))));
                        secret = RazorpayClient.text(token, "access_token");
                        publicKey = RazorpayClient.text(token, "public_token");
                        try (PreparedStatement update = c.prepareStatement("UPDATE razorpay_config SET encrypted_secret=?, "
                                + "encrypted_refresh_token=?, public_key=?, access_expires_at=? WHERE config_id=?")) {
                            update.setString(1, PaymentCrypto.encrypt(secret));
                            update.setString(2, PaymentCrypto.encrypt(RazorpayClient.text(token, "refresh_token")));
                            update.setString(3, publicKey);
                            update.setTimestamp(4, Timestamp.from(Instant.now().plusSeconds(token.path("expires_in").asLong())));
                            update.setLong(5, r.getLong("config_id")); update.executeUpdate();
                        }
                    }
                    MerchantConfig result = new MerchantConfig(r.getLong("config_id"), r.getString("auth_type"),
                            r.getString("mode"), r.getString("account_id"), publicKey, secret,
                            PaymentCrypto.decrypt(r.getString("encrypted_signature_secret")), r.getBoolean("active"));
                    c.commit(); return result;
                }
            } catch (Exception e) { c.rollback(); throw e; }
        }
    }
    public String authorization() {
        return type.equals("OAUTH") ? "Bearer " + secret : "Basic " + Base64.getEncoder()
                .encodeToString((publicKey + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }
    public static long save(String type, String mode, String account, String publicKey, String secret,
                            String refresh, Long expires, String signatureSecret, long admin) throws Exception {
        RazorpayClient.webhookSecret();
        try (Connection c = DatabaseConnection.getConnection()) {
            c.setAutoCommit(false);
            try {
                // Serialise setup changes; pending requests retain their original credentials.
                try (Statement s = c.createStatement()) { s.execute("LOCK TABLE razorpay_config IN EXCLUSIVE MODE"); }
                try (Statement s = c.createStatement()) { s.executeUpdate("UPDATE razorpay_config SET active=FALSE WHERE active"); }
                try (PreparedStatement s = c.prepareStatement("INSERT INTO razorpay_config "
                        + "(auth_type,mode,account_id,public_key,encrypted_secret,encrypted_refresh_token,access_expires_at,"
                        + "encrypted_signature_secret,configured_by) VALUES (?,?,?,?,?,?,?,?,?) RETURNING config_id")) {
                    s.setString(1,type); s.setString(2,mode); s.setString(3,account); s.setString(4,publicKey);
                    s.setString(5,PaymentCrypto.encrypt(secret)); s.setString(6,refresh == null ? null : PaymentCrypto.encrypt(refresh));
                    s.setTimestamp(7,expires == null ? null : Timestamp.from(Instant.now().plusSeconds(expires)));
                    s.setString(8,PaymentCrypto.encrypt(signatureSecret)); s.setLong(9,admin);
                    try (ResultSet r = s.executeQuery()) { r.next(); long id=r.getLong(1); c.commit(); return id; }
                }
            } catch (Exception e) { c.rollback(); throw e; }
        }
    }
    public static Map<String,Object> tokenRequest(String grant) {
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("client_id", RazorpayClient.required("RAZORPAY_CLIENT_ID"));
        body.put("client_secret", RazorpayClient.required("RAZORPAY_CLIENT_SECRET"));
        body.put("grant_type", grant); return body;
    }
}
