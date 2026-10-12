package org.example.payment;

import org.example.config.DatabaseConnection;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.Base64;

public record MerchantConfig(long id, String publicKey, String secret, boolean active) {
    public static MerchantConfig load(Long id) throws Exception {
        String query = "SELECT config_id, public_key, encrypted_secret, active FROM razorpay_config WHERE "
                + (id == null ? "active = TRUE" : "config_id = ?");
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(query)) {
            if (id != null) statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("An admin must set up Razorpay API keys first.");
                return new MerchantConfig(result.getLong("config_id"), result.getString("public_key"),
                        PaymentCrypto.decrypt(result.getString("encrypted_secret")), result.getBoolean("active"));
            }
        }
    }

    public static String keyMode(String keyId) {
        if (keyId != null && keyId.matches("rzp_(test|live)_[A-Za-z0-9]+")) {
            return keyId.startsWith("rzp_test_") ? "test" : "live";
        }
        throw new IllegalArgumentException("Enter a valid Razorpay test or live Key ID.");
    }

    public String authorization() {
        return "Basic " + Base64.getEncoder().encodeToString(
                (publicKey + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    public static long save(String keyId, String keySecret, long admin) throws Exception {
        keyMode(keyId);
        if (keySecret == null || keySecret.isBlank()) {
            throw new IllegalArgumentException("Key Secret is required.");
        }
        String encryptedSecret = PaymentCrypto.encrypt(keySecret);
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // Keep previous keys for payments that were already started with them.
                try (Statement statement = connection.createStatement()) {
                    statement.execute("LOCK TABLE razorpay_config IN EXCLUSIVE MODE");
                    statement.executeUpdate("UPDATE razorpay_config SET active = FALSE WHERE active");
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO razorpay_config (public_key, encrypted_secret, configured_by) "
                                + "VALUES (?, ?, ?) RETURNING config_id")) {
                    statement.setString(1, keyId);
                    statement.setString(2, encryptedSecret);
                    statement.setLong(3, admin);
                    try (ResultSet result = statement.executeQuery()) {
                        result.next();
                        long id = result.getLong(1);
                        connection.commit();
                        return id;
                    }
                }
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }
}
