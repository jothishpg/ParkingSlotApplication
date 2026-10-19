package org.example.admin;

import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.example.auth.JwtUtil;
import org.example.auth.PasswordHasher;
import org.example.config.DatabaseConnection;
import org.example.notification.WhatsAppNotificationService;

import java.net.URI;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Path("admin/alerts")
@RolesAllowed("ADMIN")
@Produces(MediaType.TEXT_HTML)
public class AdminAlertResource {
    private static final Logger LOGGER =
            Logger.getLogger(AdminAlertResource.class.getName());
    private static final String VERIFICATION_COOKIE = "ADMIN_ALERT_VERIFICATION";
    private static final long VERIFICATION_TTL_SECONDS = 10 * 60L;
    private static final int MAX_MESSAGE_LENGTH = 500;
    private static final SecureRandom RANDOM = new SecureRandom();

    @GET
    public Response page(
            @CookieParam(VERIFICATION_COOKIE) String verificationToken,
            @Context SecurityContext securityContext,
            @Context HttpServletRequest request) {
        long adminId = currentAdminId(securityContext);
        Map<String, Object> claims = verifiedClaims(verificationToken, adminId);
        if (claims == null) {
            return passwordPage(request, null, Response.Status.OK);
        }
        return alertPage(request, null, csrfClaim(claims), Response.Status.OK);
    }

    @POST
    @Path("verify")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response verifyPassword(
            @FormParam("password") String password,
            @Context SecurityContext securityContext,
            @Context HttpServletRequest request) {
        long adminId = currentAdminId(securityContext);
        if (password == null || password.isBlank()) {
            return passwordPage(request, "Enter your admin password.", Response.Status.BAD_REQUEST);
        }

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT password FROM local_auth WHERE user_id = ?")) {
            statement.setLong(1, adminId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    PasswordHasher.burnTime(password);
                    return passwordPage(request, "Invalid password.", Response.Status.UNAUTHORIZED);
                }
                if (!PasswordHasher.matches(password, result.getString("password"))) {
                    return passwordPage(request, "Invalid password.", Response.Status.UNAUTHORIZED);
                }
            }
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to verify the administrator password for alerts.", exception);
            return passwordPage(
                    request, "Unable to verify your password right now. Please try again.",
                    Response.Status.SERVICE_UNAVAILABLE);
        }

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("purpose", "admin-alert-verification");
        claims.put("user_id", adminId);
        claims.put("csrf_token", newCsrfToken());
        String token = JwtUtil.generate(claims, VERIFICATION_TTL_SECONDS);
        Response response = Response.seeOther(URI.create(RazorpaySetupResource.base(request)
                + "/api/admin/alerts")).header("Cache-Control", "no-store").build();
        return withVerificationCookie(response, verificationCookie(token));
    }

    @POST
    @Path("send")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response sendAlert(
            @FormParam("target") String targetValue,
            @FormParam("floorName") String floorName,
            @FormParam("slotName") String slotName,
            @FormParam("message") String message,
            @FormParam("csrfToken") String csrfToken,
            @CookieParam(VERIFICATION_COOKIE) String verificationToken,
            @Context SecurityContext securityContext,
            @Context HttpServletRequest request) {
        long adminId = currentAdminId(securityContext);
        Map<String, Object> claims = verifiedClaims(verificationToken, adminId);
        if (claims == null) {
            return passwordPage(request, "Please verify your password before sending an alert.",
                    Response.Status.UNAUTHORIZED);
        }
        if (!validCsrfToken(claims, csrfToken)) {
            return alertPage(request, "The alert form expired. Review it and submit again.",
                    csrfClaim(claims), Response.Status.FORBIDDEN);
        }

        AlertTarget target = AlertTarget.parse(targetValue);
        String normalizedFloor = trimToNull(floorName);
        String normalizedSlot = trimToNull(slotName);
        String normalizedMessage = trimToNull(message);
        if (target == null || normalizedMessage == null
                || normalizedMessage.length() > MAX_MESSAGE_LENGTH
                || (normalizedFloor != null && normalizedFloor.length() > 50)
                || (normalizedSlot != null && normalizedSlot.length() > 2)
                || (target != AlertTarget.ALL && normalizedFloor == null)
                || (target == AlertTarget.SLOT && normalizedSlot == null)) {
            return alertPage(request,
                    "Choose a valid recipient scope and enter a message (up to "
                            + MAX_MESSAGE_LENGTH + " characters). Floor and slot are required "
                            + "for the corresponding alert scope.",
                    csrfClaim(claims),
                    Response.Status.BAD_REQUEST);
        }

        try {
            List<AlertRecipient> recipients = findRecipients(target, normalizedFloor, normalizedSlot);
            if (recipients.isEmpty()) {
                return alertPage(request,
                        "No current bookings match that alert scope; no messages were sent.",
                        csrfClaim(claims), Response.Status.OK);
            }

            int sent = 0;
            for (AlertRecipient recipient : recipients) {
                if (WhatsAppNotificationService.sendAlertMessage(
                        recipient.phoneNumber(), normalizedMessage,
                        target == AlertTarget.ALL ? "Parking" : normalizedFloor,
                        target == AlertTarget.SLOT ? normalizedSlot : "Alert")) {
                    sent++;
                }
            }
            String result = "Alert processing finished. Sent: " + sent + "; failed: "
                    + (recipients.size() - sent) + "; recipients: " + recipients.size() + ".";
            return alertPage(request, result, csrfClaim(claims), Response.Status.OK);
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to find current booking recipients for an alert.", exception);
            return alertPage(request, "Unable to load current booking recipients. No alert was sent.",
                    csrfClaim(claims), Response.Status.SERVICE_UNAVAILABLE);
        }
    }

    private Response passwordPage(
            HttpServletRequest request, String message, Response.Status status) {
        String base = RazorpaySetupResource.html(RazorpaySetupResource.base(request));
        String notice = message == null ? "" : "<p role=\"alert\">"
                + RazorpaySetupResource.html(message) + "</p>";
        String html = """
                <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport"
                content="width=device-width,initial-scale=1"><title>Verify for alerts</title>
                <h1>Verify admin password</h1>%s
                <p>Enter your signed-in admin password to access booking alerts. Verification lasts 10 minutes.</p>
                <form method="post" action="%s/api/admin/alerts/verify">
                    <label for="password">Admin password</label><br>
                    <input id="password" name="password" type="password" autocomplete="current-password"
                           required maxlength="1024"><br><br>
                    <button type="submit">Continue</button>
                </form>
                <p><a href="%s/api/admin">Admin Dashboard</a></p>
                </html>
                """.formatted(notice, base, base);
        return Response.status(status).entity(html)
                .header("Cache-Control", "no-store").build();
    }

    private Response alertPage(
            HttpServletRequest request, String message, String csrfToken, Response.Status status) {
        String base = RazorpaySetupResource.html(RazorpaySetupResource.base(request));
        csrfToken = RazorpaySetupResource.html(csrfToken);
        String notice = message == null ? "" : "<p role=\"status\">"
                + RazorpaySetupResource.html(message) + "</p>";
        String html = """
                <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport"
                content="width=device-width,initial-scale=1"><title>Alert current bookings</title>
                <h1>Alert current bookings</h1>%s
                <p>Messages are sent to distinct phone numbers with active bookings using the currently assigned
                booking confirmation WhatsApp template. The alert text is added to that template.</p>
                <form method="post" action="%s/api/admin/alerts/send">
                    <input type="hidden" name="csrfToken" value="%s">
                    <label for="target">Recipients</label><br>
                    <select id="target" name="target" required>
                        <option value="ALL">All current booking users</option>
                        <option value="FLOOR">Current booking users on a floor</option>
                        <option value="SLOT">Current booking user at a slot</option>
                    </select><br><br>
                    <label for="floorName">Floor name (required for floor and slot alerts)</label><br>
                    <input id="floorName" name="floorName" maxlength="50"><br><br>
                    <label for="slotName">Slot name (required for slot alerts)</label><br>
                    <input id="slotName" name="slotName" maxlength="2"><br><br>
                    <label for="message">Alert message</label><br>
                    <textarea id="message" name="message" maxlength="%d" required></textarea><br><br>
                    <button type="submit">Send alert</button>
                </form>
                <p><a href="%s/api/admin">Admin Dashboard</a></p>
                </html>
                """.formatted(notice, base, csrfToken, MAX_MESSAGE_LENGTH, base);
        return Response.status(status).entity(html)
                .header("Cache-Control", "no-store").build();
    }

    private List<AlertRecipient> findRecipients(
            AlertTarget target, String floorName, String slotName) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT b.phone_number
                FROM booking b
                JOIN slot s ON s.slot_id = b.slot_id
                JOIN floor f ON f.floor_id = s.floor_id
                LEFT JOIN duration_amount da ON da.booking_id = b.booking_id
                WHERE da.end_time IS NULL
                """);
        if (target != AlertTarget.ALL) {
            sql.append(" AND LOWER(TRIM(f.floor_name)) = LOWER(?)");
        }
        if (target == AlertTarget.SLOT) {
            sql.append(" AND LOWER(TRIM(s.slot_name)) = LOWER(?)");
        }
        sql.append(" ORDER BY b.phone_number");

        List<AlertRecipient> recipients = new ArrayList<>();
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int parameterIndex = 1;
            if (target != AlertTarget.ALL) {
                statement.setString(parameterIndex++, floorName);
            }
            if (target == AlertTarget.SLOT) {
                statement.setString(parameterIndex, slotName);
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    recipients.add(new AlertRecipient(result.getString("phone_number")));
                }
            }
        }
        return recipients;
    }

    private Map<String, Object> verifiedClaims(String token, long adminId) {
        Map<String, Object> claims = JwtUtil.verify(token);
        if (claims == null
                || !"admin-alert-verification".equals(claims.get("purpose"))
                || !(claims.get("user_id") instanceof Number storedAdminId)
                || storedAdminId.longValue() != adminId
                || csrfClaim(claims) == null) {
            return null;
        }
        return claims;
    }

    private boolean validCsrfToken(Map<String, Object> claims, String providedToken) {
        String storedToken = csrfClaim(claims);
        return storedToken != null && storedToken.equals(providedToken);
    }

    private String csrfClaim(Map<String, Object> claims) {
        Object token = claims.get("csrf_token");
        return token instanceof String value ? value : null;
    }

    private String newCsrfToken() {
        byte[] token = new byte[32];
        RANDOM.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    private NewCookie verificationCookie(String token) {
        return new NewCookie.Builder(VERIFICATION_COOKIE)
                .value(token)
                .path("/")
                .httpOnly(true)
                .secure(true)
                .sameSite(NewCookie.SameSite.LAX)
                .maxAge((int) VERIFICATION_TTL_SECONDS)
                .build();
    }

    private Response withVerificationCookie(Response response, NewCookie cookie) {
        return Response.fromResponse(response).cookie(cookie).build();
    }

    private long currentAdminId(SecurityContext securityContext) {
        if (securityContext == null || securityContext.getUserPrincipal() == null) {
            throw new IllegalStateException("Authenticated administrator required.");
        }
        return Long.parseLong(securityContext.getUserPrincipal().getName());
    }

    private String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private record AlertRecipient(String phoneNumber) {
    }

    private enum AlertTarget {
        ALL,
        FLOOR,
        SLOT;

        private static AlertTarget parse(String value) {
            if (value == null) {
                return null;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }
    }
}
