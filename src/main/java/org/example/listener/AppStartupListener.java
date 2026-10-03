package org.example.listener;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import org.example.auth.PasswordHasher;
import org.example.config.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;

@WebListener
public class AppStartupListener implements ServletContextListener {

    private static final Logger LOGGER =
            Logger.getLogger(AppStartupListener.class.getName());

    @Override
    public void contextInitialized(ServletContextEvent event) {
        LOGGER.info("Application starting up — checking default admin user.");

        String email = setting("ADMIN_EMAIL");
        String name = setting("ADMIN_NAME");
        String phoneNumber = setting("ADMIN_PHONE_NUMBER");
        String password = setting("ADMIN_PASSWORD");

        if (email == null || name == null || phoneNumber == null || password == null) {
            LOGGER.warning("Admin bootstrap skipped: one or more ADMIN_* environment "
                    + "variables (ADMIN_EMAIL, ADMIN_NAME, ADMIN_PHONE_NUMBER, ADMIN_PASSWORD) "
                    + "are not set.");
            return;
        }

        try {
            ensureAdminUser(email, name, phoneNumber, PasswordHasher.hash(password));
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to verify or create default admin user.", exception);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        LOGGER.info("Application shutting down.");
    }

    private void ensureAdminUser(
            String email,
            String name,
            String phoneNumber,
            String password) throws SQLException {
        String checkSql = "SELECT 1 FROM users WHERE email = ?";
        String roleSql = "SELECT role_id FROM roles WHERE role_name = 'ADMIN'";
        String insertUserSql = """
                INSERT INTO users (email, name, phone_number, role_id)
                VALUES (?, ?, ?, ?)
                """;
        String insertAuthSql = "INSERT INTO local_auth (user_id, password) VALUES (?, ?)";

        try (Connection connection = DatabaseConnection.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(checkSql)) {
                statement.setString(1, email);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        LOGGER.info("Admin user already exists for email " + email + "; skipping creation.");
                        return;
                    }
                }
            }

            long roleId;
            try (PreparedStatement statement = connection.prepareStatement(roleSql);
                 ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    LOGGER.severe("Admin bootstrap failed: ADMIN role not found in roles table.");
                    return;
                }
                roleId = result.getLong("role_id");
            }

            connection.setAutoCommit(false);
            try {
                long userId;
                try (PreparedStatement statement = connection.prepareStatement(
                        insertUserSql, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, email);
                    statement.setString(2, name);
                    statement.setString(3, phoneNumber);
                    statement.setLong(4, roleId);
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("Admin user ID was not generated.");
                        }
                        userId = keys.getLong(1);
                    }
                }

                try (PreparedStatement statement = connection.prepareStatement(insertAuthSql)) {
                    statement.setLong(1, userId);
                    statement.setString(2, password);
                    statement.executeUpdate();
                }

                connection.commit();
                LOGGER.info("Default admin user created successfully for email " + email + ".");
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private String setting(String environmentName) {
        String value = System.getenv(environmentName);
        return value == null || value.isBlank() ? null : value.trim();
    }
}