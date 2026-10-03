package org.example.auth;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.QRCodeWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.example.config.DatabaseConnection;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Path("auth/totp")
@Produces(MediaType.TEXT_HTML)
public class TwoFactorResource {
    private static final String AUTH_COOKIE = "AUTH_TOKEN";

    @GET
    public Response settings(
            @CookieParam(AUTH_COOKIE) String authToken,
            @Context HttpServletRequest request) {
        Map<String, Object> claims = authenticatedClaims(authToken);
        if (claims == null) {
            return loginRequired(request);
        }
        long userId = ((Number) claims.get("user_id")).longValue();
        String base = base(request);
        try (Connection connection = DatabaseConnection.getConnection()) {
            boolean enabled = TwoFactorService.isEnabled(connection, userId);
            String content = "<h2>Two-factor authentication</h2>"
                    + (enabled
                    ? "<p>Status: Enabled. Password and Google sign-in require an authenticator or recovery code.</p>"
                    + "<form method=\"post\" action=\"" + base + "/api/auth/totp/recovery-codes\">"
                    + "<label for=\"code\">Current authenticator or recovery code</label><br>"
                    + "<input id=\"code\" name=\"code\" required><br><br>"
                    + "<button type=\"submit\">Replace recovery codes</button></form>"
                    + "<form method=\"post\" action=\"" + base + "/api/auth/totp/disable\">"
                    + "<label for=\"password\">Current password (for password accounts)</label><br>"
                    + "<input type=\"password\" id=\"password\" name=\"password\"><br>"
                    + "<label for=\"disableCode\">Current authenticator or recovery code</label><br>"
                    + "<input id=\"disableCode\" name=\"code\" required><br><br>"
                    + "<button type=\"submit\">Disable two-factor authentication</button></form>"
                    : "<p>Status: Disabled.</p>"
                    + "<form method=\"post\" action=\"" + base + "/api/auth/totp/setup\">"
                    + "<button type=\"submit\">Set up an authenticator</button></form>")
                    + "<p><a href=\"" + base + "/login.html\">Return to the application</a></p>";
            return page(Response.Status.OK, "Two-factor authentication", content);
        } catch (SQLException exception) {
            return page(Response.Status.INTERNAL_SERVER_ERROR, "Two-factor authentication",
                    "<p>Unable to load security settings.</p>");
        }
    }

    @POST
    @Path("setup")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response setup(
            @CookieParam(AUTH_COOKIE) String authToken,
            @Context HttpServletRequest request) {
        Map<String, Object> claims = authenticatedClaims(authToken);
        if (claims == null) {
            return loginRequired(request);
        }
        long userId = ((Number) claims.get("user_id")).longValue();
        try (Connection connection = DatabaseConnection.getConnection()) {
            if (TwoFactorService.isEnabled(connection, userId)) {
                return redirectSettings(request);
            }
            String secret = TwoFactorService.beginEnrollment(connection, userId);
            String email = String.valueOf(claims.get("email"));
            String qrCode = qrCodeDataUri(TwoFactorService.otpAuthUri(secret, email));
            String content = "<h2>Set up your authenticator</h2>"
                    + "<p>Scan this QR code with a TOTP-compatible authenticator, or manually enter the setup key:</p>"
                    + "<p><img alt=\"Authenticator setup QR code\" src=\"" + qrCode
                    + "\" width=\"240\" height=\"240\"></p>"
                    + "<p><strong>" + escape(secret) + "</strong></p>"
                    + "<p>Account: " + escape(email) + ". Use a time-based code (TOTP) with "
                    + "6 digits and a 30-second interval.</p>"
                    + "<form method=\"post\" action=\"" + base(request) + "/api/auth/totp/enable\">"
                    + "<label for=\"code\">Enter the current 6-digit code to confirm setup</label><br>"
                    + "<input id=\"code\" name=\"code\" inputmode=\"numeric\" pattern=\"[0-9]{6}\" "
                    + "autocomplete=\"one-time-code\" required><br><br>"
                    + "<button type=\"submit\">Enable two-factor authentication</button></form>"
                    + "<p><a href=\"" + base(request) + "/api/auth/totp\">Cancel</a></p>";
            return page(Response.Status.OK, "Set up authenticator", content);
        } catch (SQLException | GeneralSecurityException | WriterException | IOException exception) {
            return page(Response.Status.INTERNAL_SERVER_ERROR, "Set up authenticator",
                    "<p>Unable to start authenticator setup. Verify TOTP_ENCRYPTION_KEY and the database configuration.</p>");
        }
    }

    @POST
    @Path("enable")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response enable(
            @FormParam("code") String code,
            @CookieParam(AUTH_COOKIE) String authToken,
            @Context HttpServletRequest request) {
        Map<String, Object> claims = authenticatedClaims(authToken);
        if (claims == null) {
            return loginRequired(request);
        }
        long userId = ((Number) claims.get("user_id")).longValue();
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            List<String> recoveryCodes = TwoFactorService.confirmEnrollment(connection, userId, code);
            if (recoveryCodes == null) {
                connection.rollback();
                return page(Response.Status.BAD_REQUEST, "Confirm authenticator",
                        "<p>The code is invalid or setup expired. Restart setup and try again.</p>"
                                + "<p><a href=\"" + base(request) + "/api/auth/totp\">Back</a></p>");
            }
            connection.commit();
            return recoveryCodesPage(request, recoveryCodes,
                    "Two-factor authentication is now enabled.");
        } catch (SQLException | GeneralSecurityException exception) {
            return page(Response.Status.INTERNAL_SERVER_ERROR, "Confirm authenticator",
                    "<p>Unable to enable two-factor authentication.</p>");
        }
    }

    @POST
    @Path("recovery-codes")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response replaceRecoveryCodes(
            @FormParam("code") String code,
            @CookieParam(AUTH_COOKIE) String authToken,
            @Context HttpServletRequest request) {
        Map<String, Object> claims = authenticatedClaims(authToken);
        if (claims == null) {
            return loginRequired(request);
        }
        long userId = ((Number) claims.get("user_id")).longValue();
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            if (!TwoFactorService.consumeFactor(connection, userId, code)) {
                connection.rollback();
                return page(Response.Status.UNAUTHORIZED, "Replace recovery codes",
                        "<p>The authenticator or recovery code is invalid.</p>");
            }
            List<String> codes = TwoFactorService.replaceRecoveryCodes(connection, userId);
            connection.commit();
            return recoveryCodesPage(request, codes,
                    "Your previous recovery codes have been replaced.");
        } catch (SQLException | GeneralSecurityException exception) {
            return page(Response.Status.INTERNAL_SERVER_ERROR, "Replace recovery codes",
                    "<p>Unable to replace recovery codes.</p>");
        }
    }

    @POST
    @Path("disable")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response disable(
            @FormParam("password") String password,
            @FormParam("code") String code,
            @CookieParam(AUTH_COOKIE) String authToken,
            @Context HttpServletRequest request) {
        Map<String, Object> claims = authenticatedClaims(authToken);
        if (claims == null) {
            return loginRequired(request);
        }
        long userId = ((Number) claims.get("user_id")).longValue();
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            String passwordHash = localPasswordHash(connection, userId);
            if (passwordHash != null && !passwordMatches(password, passwordHash)) {
                connection.rollback();
                return page(Response.Status.UNAUTHORIZED, "Disable two-factor authentication",
                        "<p>Current password is incorrect.</p>");
            }
            if (!TwoFactorService.consumeFactor(connection, userId, code)) {
                connection.rollback();
                return page(Response.Status.UNAUTHORIZED, "Disable two-factor authentication",
                        "<p>The authenticator or recovery code is invalid.</p>");
            }
            TwoFactorService.disable(connection, userId);
            connection.commit();
            return page(Response.Status.OK, "Two-factor authentication",
                    "<p>Two-factor authentication is disabled.</p><p><a href=\""
                            + base(request) + "/api/auth/totp\">Return to security settings</a></p>");
        } catch (SQLException | GeneralSecurityException exception) {
            return page(Response.Status.INTERNAL_SERVER_ERROR, "Disable two-factor authentication",
                    "<p>Unable to disable two-factor authentication.</p>");
        }
    }

    private String localPasswordHash(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT password FROM local_auth WHERE user_id = ?")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                String passwordHash = result.getString(1);
                return passwordHash == null ? "" : passwordHash;
            }
        }
    }

    private boolean passwordMatches(String password, String hash) {
        try {
            return password != null && PasswordHasher.matches(password, hash);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private Response recoveryCodesPage(
            HttpServletRequest request, List<String> codes, String message) {
        StringBuilder list = new StringBuilder("<ul>");
        for (String code : codes) {
            list.append("<li><code>").append(escape(code)).append("</code></li>");
        }
        list.append("</ul>");
        return page(Response.Status.OK, "Save recovery codes",
                "<h2>" + escape(message) + "</h2><p>Save these codes securely. Each works "
                        + "once, and they will not be shown again.</p>" + list
                        + "<p><a href=\"" + base(request) + "/api/auth/totp\">Continue</a></p>");
    }

    private Map<String, Object> authenticatedClaims(String token) {
        Map<String, Object> claims = JwtUtil.verify(token);
        if (claims == null || !(claims.get("user_id") instanceof Number)
                || claims.get("email") == null) {
            return null;
        }
        return claims;
    }

    private Response loginRequired(HttpServletRequest request) {
        return Response.seeOther(URI.create(base(request) + "/login.html")).build();
    }

    private Response redirectSettings(HttpServletRequest request) {
        return Response.seeOther(URI.create(base(request) + "/api/auth/totp")).build();
    }

    private Response page(Response.Status status, String title, String content) {
        String html = "<!doctype html><html lang=\"en\"><head><meta charset=\"UTF-8\"><title>"
                + escape(title) + "</title></head><body><h1>Parking Management System</h1>"
                + content + "</body></html>";
        return Response.status(status).type(MediaType.TEXT_HTML)
                .header("Cache-Control", "no-store")
                .entity(html).build();
    }

    private String base(HttpServletRequest request) {
        String configured = System.getenv("APP_BASE_URL");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return request.getScheme() + "://" + request.getServerName() + request.getContextPath();
    }

    private String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String qrCodeDataUri(String value) throws WriterException, IOException {
        var matrix = new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 240, 240);
        BufferedImage image = new BufferedImage(240, 240, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 240; y++) {
            for (int x = 0; x < 240; x++) {
                image.setRGB(x, y, matrix.get(x, y) ? 0xff000000 : 0xffffffff);
            }
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "PNG", output);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        }
    }

}
