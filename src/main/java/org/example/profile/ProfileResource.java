package org.example.profile;

import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.example.auth.PasswordHasher;
import org.example.config.DatabaseConnection;

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

@Path("profile")
@RolesAllowed({"ADMIN", "TICKET_ASSIGNER"})
@Produces(MediaType.TEXT_HTML)
public class ProfileResource {

    private static final Logger LOGGER =
            Logger.getLogger(ProfileResource.class.getName());
    private static final String APP_BASE_URL = System.getenv("APP_BASE_URL");

    @GET
    public Response profile(
            @Context SecurityContext securityContext,
            @Context HttpServletRequest request) {
        String base = base(request);
        long userId = currentUserId(securityContext);

        String sql = """
                SELECT name, email, phone_number
                FROM users
                WHERE user_id = ?
                """;

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return error(base, Response.Status.NOT_FOUND, "Your account was not found.");
                }
                String content = """
                        <h2>My Profile</h2>
                        <p>Current account details</p>
                        <ul>
                            <li><strong>Name:</strong> %s</li>
                            <li><strong>Email:</strong> %s</li>
                            <li><strong>Phone Number:</strong> %s</li>
                        </ul>
                        <h3>Update details</h3>
                        <form method="post" action="%s/api/profile/name">
                            <label for="name">New name</label><br>
                            <input type="text" id="name" name="value" maxlength="100"
                                   placeholder="Enter a new name" required>
                            <button type="submit">Update name</button>
                        </form>
                        <form method="post" action="%s/api/profile/email">
                            <label for="email">New email</label><br>
                            <input type="email" id="email" name="value" maxlength="255"
                                   placeholder="Enter a new email" required>
                            <button type="submit">Update email</button>
                        </form>
                        <form method="post" action="%s/api/profile/phone">
                            <label for="phone">New phone number</label><br>
                            <input type="tel" id="phone" name="value" inputmode="numeric"
                                   pattern="[0-9]{10}" maxlength="10"
                                   placeholder="Enter a new 10-digit phone number"
                                   title="Enter exactly 10 digits" required>
                            <button type="submit">Update phone number</button>
                        </form>
                        <h3>Password</h3>
                        <p><a href="%s/api/profile/password">Change password</a></p>
                        <p><a href="%s/api/booking">Booking</a> |
                           <a href="%s/api/auth/totp">Security / Two-factor authentication</a></p>
                        """.formatted(
                        escape(result.getString("name")),
                        escape(result.getString("email")),
                        escape(result.getString("phone_number")),
                        base, base, base, base, base, base
                );
                return page(base, "My Profile", content);
            }
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to load the current user's profile.", exception);
            return error(base, Response.Status.INTERNAL_SERVER_ERROR,
                    "Unable to load your profile.");
        }
    }

    @POST
    @Path("{field}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response updateDetail(
            @PathParam("field") String field,
            @FormParam("value") String submittedValue,
            @Context SecurityContext securityContext,
            @Context HttpServletRequest request) {
        String base = base(request);
        long userId = currentUserId(securityContext);
        ProfileField profileField = ProfileField.fromPath(field);
        if (profileField == null) {
            return error(base, Response.Status.NOT_FOUND, "Profile field not found.");
        }

        String newValue = submittedValue == null ? "" : submittedValue.trim();
        if (newValue.isBlank()) {
            return error(base, Response.Status.BAD_REQUEST, "The new value is required.");
        }
        if (!profileField.isValid(newValue)) {
            return error(base, Response.Status.BAD_REQUEST, profileField.validationMessage);
        }

        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String oldValue = readValue(connection, userId, profileField, true);
                if (oldValue == null) {
                    connection.rollback();
                    return error(base, Response.Status.NOT_FOUND, "Your account was not found.");
                }
                if (profileField == ProfileField.EMAIL
                        && emailInUse(connection, userId, newValue)) {
                    connection.rollback();
                    return error(base, Response.Status.CONFLICT,
                            "That email address is already in use.");
                }
                if (!oldValue.equals(newValue)) {
                    updateValue(connection, userId, profileField, newValue);
                    recordChange(connection, userId, profileField.column, oldValue, newValue);
                }
                connection.commit();
                return redirect(base + "/api/profile");
            } catch (SQLException exception) {
                connection.rollback();
                if ("23505".equals(exception.getSQLState())
                        && profileField == ProfileField.EMAIL) {
                    return error(base, Response.Status.CONFLICT,
                            "That email address is already in use.");
                }
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to update the current user's profile.", exception);
            return error(base, Response.Status.INTERNAL_SERVER_ERROR,
                    "Unable to update your profile.");
        }
    }

    @GET
    @Path("password")
    public Response changePasswordForm(@Context HttpServletRequest request) {
        String base = base(request);
        String content = """
                <h2>Change Password</h2>
                <p>Enter your current password to verify your identity, then choose a new password.</p>
                <form method="post" action="%s/api/profile/password">
                    <label for="currentPassword">Current password</label><br>
                    <input type="password" id="currentPassword" name="currentPassword"
                           autocomplete="current-password" required><br><br>
                    <label for="newPassword">New password</label><br>
                    <input type="password" id="newPassword" name="newPassword"
                           autocomplete="new-password" required><br><br>
                    <label for="confirmPassword">Confirm new password</label><br>
                    <input type="password" id="confirmPassword" name="confirmPassword"
                           autocomplete="new-password" required><br><br>
                    <button type="submit">Change password</button>
                </form>
                <p><a href="%s/api/profile">Cancel</a></p>
                """.formatted(base, base);
        return page(base, "Change Password", content);
    }

    @POST
    @Path("password")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response changePassword(
            @FormParam("currentPassword") String currentPassword,
            @FormParam("newPassword") String newPassword,
            @FormParam("confirmPassword") String confirmPassword,
            @Context SecurityContext securityContext,
            @Context HttpServletRequest request) {
        String base = base(request);
        long userId = currentUserId(securityContext);
        if (blank(currentPassword) || blank(newPassword) || blank(confirmPassword)) {
            return error(base, Response.Status.BAD_REQUEST, "All password fields are required.");
        }
        if (!newPassword.equals(confirmPassword)) {
            return error(base, Response.Status.BAD_REQUEST,
                    "New password and confirmation do not match.");
        }
        if (currentPassword.equals(newPassword)) {
            return error(base, Response.Status.BAD_REQUEST,
                    "The new password must be different from your current password.");
        }

        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String storedPassword = null;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT password FROM local_auth WHERE user_id = ? FOR UPDATE
                        """)) {
                    statement.setLong(1, userId);
                    try (ResultSet result = statement.executeQuery()) {
                        if (result.next()) {
                            storedPassword = result.getString("password");
                        }
                    }
                }
                if (storedPassword == null) {
                    connection.rollback();
                    return error(base, Response.Status.BAD_REQUEST,
                            "This account has no local password to change.");
                }
                if (!PasswordHasher.matches(currentPassword, storedPassword)) {
                    connection.rollback();
                    return error(base, Response.Status.UNAUTHORIZED,
                            "The current password is incorrect.");
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE local_auth SET password = ? WHERE user_id = ?
                        """)) {
                    statement.setString(1, PasswordHasher.hash(newPassword));
                    statement.setLong(2, userId);
                    statement.executeUpdate();
                }
                connection.commit();
                return redirect(base + "/api/profile");
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to change the current user's password.", exception);
            return error(base, Response.Status.INTERNAL_SERVER_ERROR,
                    "Unable to change your password.");
        }
    }

    private String readValue(
            Connection connection, long userId, ProfileField field, boolean lock)
            throws SQLException {
        String sql = "SELECT " + field.column + " FROM users WHERE user_id = ?"
                + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(field.column) : null;
            }
        }
    }

    private void updateValue(
            Connection connection, long userId, ProfileField field, String value)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE users SET " + field.column + " = ? WHERE user_id = ?")) {
            statement.setString(1, value);
            statement.setLong(2, userId);
            statement.executeUpdate();
        }
    }

    private boolean emailInUse(Connection connection, long userId, String email)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM users
                WHERE lower(email) = lower(?) AND user_id <> ?
                """)) {
            statement.setString(1, email);
            statement.setLong(2, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private void recordChange(
            Connection connection, long userId, String field, String oldValue, String newValue)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO user_profile_change
                    (user_id, updated_value, old_value, new_value)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setLong(1, userId);
            statement.setString(2, field);
            statement.setString(3, oldValue);
            statement.setString(4, newValue);
            statement.executeUpdate();
        }
    }

    private long currentUserId(SecurityContext securityContext) {
        if (securityContext == null || securityContext.getUserPrincipal() == null) {
            throw new IllegalStateException("Authenticated user is required.");
        }
        return Long.parseLong(securityContext.getUserPrincipal().getName());
    }

    private String base(HttpServletRequest request) {
        return APP_BASE_URL != null && !APP_BASE_URL.isBlank()
                ? APP_BASE_URL
                : request.getContextPath();
    }

    private Response page(String base, String title, String content) {
        String html = """
                <!doctype html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>Parking Management System - %s</title>
                </head>
                <body>
                    <h1>Parking Management System</h1>
                    %s
                    <form method="post" action="%s/api/auth/logout">
                        <button type="submit">Logout</button>
                    </form>
                </body>
                </html>
                """.formatted(escape(title), content, base);
        return Response.ok(html).type(MediaType.TEXT_HTML).build();
    }

    private Response error(String base, Response.Status status, String message) {
        String content = "<h2>Error</h2><p>" + escape(message) + "</p>"
                + "<p><a href=\"" + base + "/api/profile\">Return to profile</a></p>";
        return Response.status(status)
                .entity(page(base, "Error", content).getEntity())
                .type(MediaType.TEXT_HTML)
                .build();
    }

    private Response redirect(String location) {
        return Response.seeOther(URI.create(location)).build();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private enum ProfileField {
        NAME("name", 100, "Name is required and must not exceed 100 characters."),
        EMAIL("email", 255, "Enter a valid email address of at most 255 characters."),
        PHONE("phone_number", 10, "Phone number must contain exactly 10 digits.");

        private final String column;
        private final int maxLength;
        private final String validationMessage;

        ProfileField(String column, int maxLength, String validationMessage) {
            this.column = column;
            this.maxLength = maxLength;
            this.validationMessage = validationMessage;
        }

        private static ProfileField fromPath(String field) {
            return switch (field) {
                case "name" -> NAME;
                case "email" -> EMAIL;
                case "phone" -> PHONE;
                default -> null;
            };
        }

        private boolean isValid(String value) {
            if (value.length() > maxLength) {
                return false;
            }
            return switch (this) {
                case NAME -> !value.isBlank();
                case EMAIL -> value.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
                case PHONE -> value.matches("\\d{10}");
            };
        }
    }
}
