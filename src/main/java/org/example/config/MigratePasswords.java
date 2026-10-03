package org.example.config;

import org.example.auth.PasswordHasher;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;


public final class MigratePasswords {

    private MigratePasswords() {
    }

    public static void main(String[] args) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection()) {
            migrate(connection);
            System.out.println("All passwords have been hashed.");
        }
    }

    static void migrate(Connection connection) throws SQLException {
        Map<Long, String> hashes = new LinkedHashMap<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT user_id, password FROM local_auth");
             ResultSet result = select.executeQuery()) {
            while (result.next()) {
                String password = result.getString("password");
                if (password != null && !password.isEmpty()) {
                    hashes.put(result.getLong("user_id"), PasswordHasher.hash(password));
                }
            }
        }

        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE local_auth SET password = ? WHERE user_id = ?")) {
            for (Map.Entry<Long, String> entry : hashes.entrySet()) {
                update.setString(1, entry.getValue());
                update.setLong(2, entry.getKey());
                update.addBatch();
            }
            update.executeBatch();
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }
}