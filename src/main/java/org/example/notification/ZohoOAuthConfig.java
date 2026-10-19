package org.example.notification;

import org.example.config.DatabaseConnection;
import org.example.config.SecretEncryption;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ZohoOAuthConfig {
    private static final Logger LOGGER = Logger.getLogger(ZohoOAuthConfig.class.getName());

    private ZohoOAuthConfig() {
    }

    public record Client(String clientId, String clientSecret) {
    }

    public static Client load() throws SQLException {
        String sql = """
                SELECT client_id, encrypted_client_secret
                FROM zoho_oauth_client_config
                WHERE config_id = 1
                """;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            return new Client(result.getString("client_id"),
                    SecretEncryption.decrypt(result.getString("encrypted_client_secret")));
        }
    }

    public static String configuredClientId() throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT client_id
                     FROM zoho_oauth_client_config
                     WHERE config_id = 1
                     """);
             ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getString("client_id") : null;
        }
    }

    public static boolean save(String clientId, String clientSecret, long adminId)
            throws SQLException {
        String normalizedId = clientId == null ? "" : clientId.trim();
        String normalizedSecret = clientSecret == null ? "" : clientSecret.trim();
        if (normalizedId.isBlank() || normalizedId.length() > 255
                || normalizedId.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Enter a valid Zoho OAuth client ID.");
        }
        if (normalizedSecret.isBlank() || normalizedSecret.length() > 1024) {
            throw new IllegalArgumentException("Enter a valid Zoho OAuth client secret.");
        }

        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Client existing = null;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT client_id, encrypted_client_secret
                        FROM zoho_oauth_client_config
                        WHERE config_id = 1
                        FOR UPDATE
                        """);
                     ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        try {
                            existing = new Client(result.getString("client_id"),
                                    SecretEncryption.decrypt(
                                            result.getString("encrypted_client_secret")));
                        } catch (IllegalStateException exception) {
                            LOGGER.log(Level.WARNING,
                                    "Stored Zoho OAuth credentials could not be decrypted while "
                                            + "an administrator attempted to replace them.",
                                    exception);
                        }
                    }
                }
                boolean changed = existing == null
                        || !existing.clientId().equals(normalizedId)
                        || !existing.clientSecret().equals(normalizedSecret);
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO zoho_oauth_client_config
                            (config_id, client_id, encrypted_client_secret,
                             configured_by_user_id, created_at, updated_at)
                        VALUES (1, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        ON CONFLICT (config_id) DO UPDATE SET
                            client_id = EXCLUDED.client_id,
                            encrypted_client_secret = EXCLUDED.encrypted_client_secret,
                            configured_by_user_id = EXCLUDED.configured_by_user_id,
                            updated_at = CURRENT_TIMESTAMP
                        """)) {
                    statement.setString(1, normalizedId);
                    statement.setString(2, SecretEncryption.encrypt(normalizedSecret));
                    statement.setLong(3, adminId);
                    statement.executeUpdate();
                }
                if (changed) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "DELETE FROM zoho_oauth_connection WHERE connection_id = 1")) {
                        statement.executeUpdate();
                    }
                }
                connection.commit();
                return changed;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }
}
