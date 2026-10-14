package org.example.admin;

import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.example.config.DatabaseConnection;
import org.example.payment.MerchantConfig;
import org.example.payment.RazorpayClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

@Path("admin/razorpay-setup")
@RolesAllowed("ADMIN")
@Produces(MediaType.TEXT_HTML)
public class RazorpaySetupResource {
    private static final Logger LOGGER = Logger.getLogger(RazorpaySetupResource.class.getName());

    @GET
    public Response page(@Context HttpServletRequest request) {
        String status = "No Razorpay API keys configured.";
        try (Connection connection = DatabaseConnection.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT config_id, public_key FROM razorpay_config WHERE active")) {
            if (result.next()) {
                status = "Connected in " + MerchantConfig.keyMode(result.getString("public_key"))
                        + " mode. Webhook URL: " + base(request)
                        + "/api/payments/razorpay/webhook/" + result.getLong("config_id");
            }
        } catch (Exception exception) {
            LOGGER.log(Level.WARNING, "Unable to load Razorpay setup.", exception);
            return failure(new IllegalStateException("Unable to load Razorpay setup. Check the database migrations."));
        }
        String base = html(base(request));
        return Response.ok("""
                <!doctype html><meta charset="utf-8"><title>Razorpay Setup</title>
                <h1>Razorpay Setup</h1><p>%s</p>
                <form method="post" action="%s/api/admin/razorpay-setup/keys">
                <label>Key ID <input name="keyId" required></label><br><br>
                <label>Key Secret <input type="password" name="keySecret" required autocomplete="new-password"></label><br><br>
                <button>Save Razorpay keys</button></form>
                <p>Use both keys from the same Razorpay test or live account. The Key Secret is stored encrypted.</p>
                <p>Enable automatic payment capture. For webhooks, select payment_link.paid,
                payment_link.expired and payment_link.cancelled.
                Use the webhook signing secret configured on the server.</p>
                <a href="%s/api/admin">Admin Dashboard</a>
                """.formatted(html(status), base, base)).header("Cache-Control", "no-store").build();
    }

    @POST
    @Path("keys")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response keys(@FormParam("keyId") String keyId, @FormParam("keySecret") String keySecret,
                         @Context SecurityContext security, @Context HttpServletRequest request) {
        try {
            keyId = keyId == null ? null : keyId.trim();
            MerchantConfig.keyMode(keyId);
            if (keySecret == null || keySecret.isBlank()) {
                throw new IllegalArgumentException("Key Secret is required.");
            }
            String authorization = "Basic " + Base64.getEncoder().encodeToString(
                    (keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
            RazorpayClient.api("payment_links?count=1", null, authorization);
            MerchantConfig.save(keyId, keySecret, Long.parseLong(security.getUserPrincipal().getName()));
            return Response.seeOther(URI.create(base(request) + "/api/admin/razorpay-setup")).build();
        } catch (Exception exception) {
            return failure(exception);
        }
    }

    public static String html(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    public static String base(HttpServletRequest request) {
        String base = System.getenv("APP_BASE_URL");
        return base == null || base.isBlank() ? request.getContextPath() : base.replaceAll("/$", "");
    }

    private static Response failure(Exception exception) {
        String message = exception instanceof IllegalArgumentException || exception instanceof IllegalStateException
                ? exception.getMessage() : "Unable to save Razorpay keys. Check the configuration and retry.";
        return Response.status(400).entity("<p>" + html(message)
                + "</p><p><a href=\"javascript:history.back()\">Back</a></p>").build();
    }
}
