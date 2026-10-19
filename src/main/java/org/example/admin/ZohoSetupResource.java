package org.example.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.example.auth.JwtUtil;
import org.example.notification.ZohoOAuthConfig;
import org.example.notification.ZohoOAuthService;
import org.example.notification.ZohoOAuthService.ZohoSetupException;

import java.io.IOException;
import java.net.URI;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Path("admin/zoho-setup")
@RolesAllowed("ADMIN")
public class ZohoSetupResource {
    private static final Logger LOGGER = Logger.getLogger(ZohoSetupResource.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String STATE_COOKIE = "ZOHO_CLIQ_SETUP_STATE";
    private static final long STATE_TTL_SECONDS = 10 * 60;
    private static final String APP_BASE_URL = setting("APP_BASE_URL");
    private static final String ZOHO_REDIRECT_URI = setting("ZOHO_REDIRECT_URI");

    @GET
    public Response status(@Context HttpServletRequest request) {
        return setupPage(request, null, Response.Status.OK);
    }

    @POST
    @Path("client")
    public Response saveClient(
            @FormParam("clientId") String clientId,
            @FormParam("clientSecret") String clientSecret,
            @Context SecurityContext securityContext,
            @Context HttpServletRequest request) {
        try {
            boolean changed = ZohoOAuthConfig.save(
                    clientId, clientSecret, currentAdminId(securityContext));
            String message = changed
                    ? "Zoho OAuth client credentials saved. Connect Zoho to authorize the Cliq bot."
                    : "Zoho OAuth client credentials saved; the existing Zoho connection was preserved.";
            return setupPage(request, message, Response.Status.OK);
        } catch (IllegalArgumentException exception) {
            return setupPage(request, exception.getMessage(), Response.Status.BAD_REQUEST);
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to save the Zoho OAuth client configuration.", exception);
            return setupPage(request,
                    "Unable to save Zoho OAuth settings. Check that the V17 database migration has been applied.",
                    Response.Status.INTERNAL_SERVER_ERROR);
        } catch (RuntimeException exception) {
            LOGGER.log(Level.SEVERE, "Unable to encrypt the Zoho OAuth client secret.", exception);
            return setupPage(request,
                    "Unable to encrypt Zoho credentials. Verify the GMAIL_TOKEN_ENCRYPTION_KEY database setting is configured.",
                    Response.Status.SERVICE_UNAVAILABLE);
        }
    }

    @POST
    @Path("bot")
    public Response createBot(
            @FormParam("name") String name,
            @FormParam("description") String description,
            @Context SecurityContext securityContext,
            @Context HttpServletRequest request) {
        String webhookUrl = webhookUrl(request);
        if (webhookUrl == null) {
            return setupPage(request,
                    "Set APP_BASE_URL to this application's public HTTPS URL before creating the webhook bot.",
                    Response.Status.INTERNAL_SERVER_ERROR);
        }

        ZohoOAuthService.Bot bot;
        try {
            bot = ZohoOAuthService.createWebhookBot(
                    name, description, webhookUrl, currentAdminId(securityContext));
        } catch (ZohoSetupException exception) {
            return setupPage(request, exception.getMessage(), Response.Status.BAD_REQUEST);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return setupPage(request, "Zoho bot creation was interrupted. Please check Zoho Cliq before retrying.",
                    Response.Status.SERVICE_UNAVAILABLE);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Unable to create the Zoho Cliq bot.", exception);
            return setupPage(request,
                    "Unable to contact Zoho Cliq. Check the connection and try again.",
                    Response.Status.SERVICE_UNAVAILABLE);
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to save the created Zoho Cliq bot.", exception);
            return setupPage(request,
                    "Zoho bot creation could not be saved locally. Check the V19 database migration before retrying.",
                    Response.Status.INTERNAL_SERVER_ERROR);
        }

        try {
            ZohoOAuthService.configureMessageHandler(bot.botId());
            return setupPage(request,
                    "Created " + bot.name() + " in Zoho Cliq. Incoming messages are routed to "
                            + webhookUrl + " and receive a welcome reply in the same conversation.",
                    Response.Status.OK);
        } catch (ZohoSetupException exception) {
            return setupPage(request,
                    "The bot was created as " + bot.name() + ", but its message handler is not configured: "
                            + exception.getMessage()
                            + " Use Retry message handler below; do not create another bot.",
                    Response.Status.SERVICE_UNAVAILABLE);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return setupPage(request,
                    "The bot was created, but handler setup was interrupted. Use Retry message handler below.",
                    Response.Status.SERVICE_UNAVAILABLE);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING,
                    "The Zoho Cliq bot was created, but its message handler could not be configured.",
                    exception);
            return setupPage(request,
                    "The bot was created, but Zoho Cliq could not configure its message handler. "
                            + "Use Retry message handler below.",
                    Response.Status.SERVICE_UNAVAILABLE);
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE,
                    "The Zoho Cliq bot was created, but handler status could not be saved.", exception);
            return setupPage(request,
                    "The bot was created and the handler request was sent, but its status could not be saved. "
                            + "Check the V19 database migration before retrying.",
                    Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    @POST
    @Path("bot/handler")
    public Response retryMessageHandler(@Context HttpServletRequest request) {
        try {
            ZohoOAuthService.Bot bot = ZohoOAuthService.configuredBot();
            if (bot == null) {
                return setupPage(request, "Create the webhook bot before configuring its message handler.",
                        Response.Status.BAD_REQUEST);
            }
            ZohoOAuthService.configureMessageHandler(bot.botId());
            return setupPage(request, "The Zoho Cliq message handler is configured.",
                    Response.Status.OK);
        } catch (ZohoSetupException exception) {
            return setupPage(request, exception.getMessage(), Response.Status.BAD_REQUEST);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return setupPage(request,
                    "Zoho message-handler setup was interrupted. Please try again.",
                    Response.Status.SERVICE_UNAVAILABLE);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Unable to configure the Zoho Cliq message handler.", exception);
            return setupPage(request,
                    "Unable to contact Zoho Cliq to configure the message handler.",
                    Response.Status.SERVICE_UNAVAILABLE);
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to read or update Zoho Cliq bot status.", exception);
            return setupPage(request,
                    "Unable to update bot status. Check the V19 database migration.",
                    Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    @POST
    @Path("bot/details")
    public Response fetchBotDetails(@Context HttpServletRequest request) {
        try {
            String details = JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(ZohoOAuthService.fetchConfiguredBotDetails());
            return setupPage(request,
                    "Fetched bot details directly from Zoho Cliq using GET /api/v3/bots/{BOT_ID}.",
                    Response.Status.OK, details);
        } catch (ZohoSetupException exception) {
            return setupPage(request, exception.getMessage(), Response.Status.BAD_REQUEST);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return setupPage(request,
                    "Fetching Zoho bot details was interrupted. Please try again.",
                    Response.Status.SERVICE_UNAVAILABLE);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Unable to fetch Zoho Cliq bot details.", exception);
            return setupPage(request,
                    "Unable to contact Zoho Cliq to fetch bot details.",
                    Response.Status.SERVICE_UNAVAILABLE);
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to read Zoho Cliq bot configuration.", exception);
            return setupPage(request,
                    "Unable to read the local bot configuration. Check the database migrations.",
                    Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    @POST
    @Path("bot/delete")
    public Response deleteBot(@Context HttpServletRequest request) {
        try {
            ZohoOAuthService.Bot deletedBot = ZohoOAuthService.deleteConfiguredBot();
            return setupPage(request,
                    "Deleted " + deletedBot.name() + " from Zoho Cliq and removed its local configuration.",
                    Response.Status.OK);
        } catch (ZohoSetupException exception) {
            return setupPage(request, exception.getMessage(), Response.Status.BAD_REQUEST);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return setupPage(request,
                    "Zoho bot deletion was interrupted. Check Cliq before trying again.",
                    Response.Status.SERVICE_UNAVAILABLE);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Unable to delete the Zoho Cliq bot.", exception);
            return setupPage(request,
                    "Unable to contact Zoho Cliq to delete the bot. Its local configuration was retained.",
                    Response.Status.SERVICE_UNAVAILABLE);
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to delete the Zoho Cliq bot configuration.", exception);
            return setupPage(request,
                    "Unable to complete bot deletion locally. Check the database and Zoho Cliq bot status.",
                    Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    @GET
    @Path("authorize")
    public Response authorize(
            @Context SecurityContext securityContext,
            @Context HttpServletRequest request) {
        String redirectUri = redirectUri(request);
        if (redirectUri == null) {
            return setupPage(request,
                    "Set ZOHO_REDIRECT_URI to this application's public HTTPS callback URL before connecting Zoho.",
                    Response.Status.INTERNAL_SERVER_ERROR);
        }
        try {
            long adminId = currentAdminId(securityContext);
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("purpose", "zoho-cliq-setup");
            claims.put("admin_user_id", adminId);
            String state = JwtUtil.generate(claims, STATE_TTL_SECONDS);
            String authorizationUrl = ZohoOAuthService.authorizationUrl(redirectUri, state);
            return Response.seeOther(URI.create(authorizationUrl))
                    .cookie(stateCookie(state, (int) STATE_TTL_SECONDS))
                    .build();
        } catch (ZohoSetupException exception) {
            return setupPage(request, exception.getMessage(), Response.Status.SERVICE_UNAVAILABLE);
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to load the Zoho OAuth configuration.", exception);
            return setupPage(request,
                    "Unable to read Zoho OAuth settings. Check that the V17 database migration has been applied.",
                    Response.Status.SERVICE_UNAVAILABLE);
        }
    }

    @GET
    @Path("callback")
    public Response callback(
            @QueryParam("code") String code,
            @QueryParam("state") String state,
            @QueryParam("error") String oauthError,
            @CookieParam(STATE_COOKIE) String stateCookie,
            @Context SecurityContext securityContext,
            @Context HttpServletRequest request) {
        NewCookie clearState = stateCookie("", 0);
        Map<String, Object> claims = JwtUtil.verify(state);
        if (claims == null || !state.equals(stateCookie)
                || !"zoho-cliq-setup".equals(claims.get("purpose"))
                || !isSameAdmin(claims.get("admin_user_id"), securityContext)) {
            return withCookie(setupPage(request,
                    "The Zoho authorization session is invalid or expired. Please try connecting again.",
                    Response.Status.UNAUTHORIZED), clearState);
        }
        if (oauthError != null && !oauthError.isBlank()) {
            return withCookie(setupPage(request,
                    "Zoho authorization was cancelled or denied. No Cliq connection was saved.",
                    Response.Status.BAD_REQUEST), clearState);
        }
        if (code == null || code.isBlank()) {
            return withCookie(setupPage(request,
                    "Zoho did not return an authorization code. Please try again.",
                    Response.Status.BAD_REQUEST), clearState);
        }

        String redirectUri = redirectUri(request);
        if (redirectUri == null) {
            return withCookie(setupPage(request, "ZOHO_REDIRECT_URI is not configured.",
                    Response.Status.INTERNAL_SERVER_ERROR), clearState);
        }
        try {
            ZohoOAuthService.Authorization authorization =
                    ZohoOAuthService.exchangeAuthorizationCode(code, redirectUri);
            ZohoOAuthService.saveAuthorization(
                    authorization, currentAdminId(securityContext));
            return withCookie(setupPage(request,
                    "Zoho Cliq OAuth is connected and ready for bot creation.",
                    Response.Status.OK), clearState);
        } catch (ZohoSetupException exception) {
            return withCookie(setupPage(request, exception.getMessage(),
                    Response.Status.BAD_REQUEST), clearState);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return withCookie(setupPage(request,
                    "Zoho authorization was interrupted. Please try again.",
                    Response.Status.SERVICE_UNAVAILABLE), clearState);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Unable to complete Zoho OAuth authorization.", exception);
            return withCookie(setupPage(request,
                    "Unable to contact Zoho. Verify the OAuth client settings, then try again.",
                    Response.Status.SERVICE_UNAVAILABLE), clearState);
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to persist the Zoho OAuth authorization.", exception);
            return withCookie(setupPage(request,
                    "Zoho authorization succeeded, but the connection could not be saved. Check the V17 database migration.",
                    Response.Status.INTERNAL_SERVER_ERROR), clearState);
        } catch (RuntimeException exception) {
            LOGGER.log(Level.SEVERE, "Unable to save Zoho OAuth authorization.", exception);
            return withCookie(setupPage(request,
                    "Unable to save the Zoho authorization. Check the database and token-encryption key.",
                    Response.Status.INTERNAL_SERVER_ERROR), clearState);
        }
    }

    @POST
    @Path("disconnect")
    public Response disconnect(@Context HttpServletRequest request) {
        try {
            ZohoOAuthService.disconnect();
            return setupPage(request, "The Zoho Cliq connection has been removed.",
                    Response.Status.OK);
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to disconnect the Zoho Cliq connection.", exception);
            return setupPage(request, "Unable to disconnect the Zoho Cliq connection.",
                    Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    private Response setupPage(
            HttpServletRequest request, String message, Response.Status status) {
        return setupPage(request, message, status, null);
    }

    private Response setupPage(
            HttpServletRequest request, String message, Response.Status status, String botDetails) {
        String base = base(request);
        String clientId = null;
        boolean connected = false;
        ZohoOAuthService.Bot bot = null;
        try {
            clientId = ZohoOAuthConfig.configuredClientId();
            connected = ZohoOAuthService.isConnected();
            bot = ZohoOAuthService.configuredBot();
        } catch (SQLException exception) {
            LOGGER.log(Level.WARNING, "Unable to read Zoho OAuth setup status.", exception);
            if (message == null) {
                message = "Zoho setup status is unavailable. Apply the V17 database migration.";
                status = Response.Status.SERVICE_UNAVAILABLE;
            }
        }

        String callback = redirectUri(request);
        String webhookUrl = webhookUrl(request);
        String content = "<h2>Zoho Cliq Setup</h2>"
                + "<p>Configure the Zoho OAuth client used to create the Cliq bot. "
                + "This setup uses Zoho India. The OAuth grant includes bot listing, bot creation, "
                + "and handler configuration.</p>"
                + (callback == null
                ? "<p>Callback URL: Set APP_BASE_URL or ZOHO_REDIRECT_URI to display it.</p>"
                : "<p>Register this exact callback URL in your Zoho API Console client: <code>"
                + escape(callback) + "</code></p>")
                + (clientId == null
                ? "<p>Zoho OAuth client is not configured.</p>"
                : "<p>Configured client ID: <strong>" + escape(clientId) + "</strong></p>")
                + "<form method=\"post\" action=\"" + escape(base)
                + "/api/admin/zoho-setup/client\">"
                + "<label for=\"clientId\">Zoho OAuth client ID</label> "
                + "<input type=\"text\" id=\"clientId\" name=\"clientId\" maxlength=\"255\" required value=\""
                + escape(clientId) + "\"><br>"
                + "<label for=\"clientSecret\">Zoho OAuth client secret</label> "
                + "<input type=\"password\" id=\"clientSecret\" name=\"clientSecret\" maxlength=\"1024\" "
                + "autocomplete=\"new-password\" required>"
                + "<button type=\"submit\">Save Zoho OAuth client</button></form>"
                + (message == null ? "" : "<p role=\"status\">" + escape(message) + "</p>")
                + (connected
                ? "<p>Status: Connected. OAuth tokens are stored encrypted and refreshed when needed.</p>"
                : "<p>Status: Not connected.</p>")
                + "<p><a href=\"" + escape(base) + "/api/admin/zoho-setup/authorize\">"
                + (connected ? "Reconnect Zoho" : "Connect Zoho") + "</a></p>"
                + botSection(base, bot, connected, webhookUrl, botDetails)
                + (connected ? "<form method=\"post\" action=\"" + escape(base)
                + "/api/admin/zoho-setup/disconnect\"><button type=\"submit\">Disconnect Zoho</button></form>"
                : "")
                + "<p><a href=\"" + escape(base) + "/api/admin\">Admin Dashboard</a></p>"
                + "<form method=\"post\" action=\"" + escape(base) + "/api/auth/logout\">"
                + "<button type=\"submit\">Logout</button></form>";
        String html = "<!doctype html><html lang=\"en\"><head><meta charset=\"UTF-8\">"
                + "<title>Zoho Cliq Setup</title></head><body><h1>Parking Management System</h1>"
                + content + "</body></html>";
        return Response.status(status).type(MediaType.TEXT_HTML).entity(html).build();
    }

    private String botSection(
            String base,
            ZohoOAuthService.Bot bot,
            boolean connected,
            String webhookUrl,
            String botDetails) {
        if (bot != null) {
            String handlerStatus = bot.messageHandlerConfigured()
                    ? "Message handler configured; incoming messages receive booking information in the same conversation."
                    : "Bot created, but the message handler is not configured.";
            return "<h3>Cliq bot</h3>"
                    + "<p>Name: <strong>" + escape(bot.name()) + "</strong>; unique name: <code>"
                    + escape(bot.uniqueName()) + "</code>; Zoho bot ID: <code>"
                    + escape(bot.botId()) + "</code>; access: " + escape(bot.scope()) + ".</p>"
                    + "<p>" + escape(handlerStatus) + "</p>"
                    + (bot.messageHandlerConfigured() ? ""
                    : "<form method=\"post\" action=\"" + escape(base)
                    + "/api/admin/zoho-setup/bot/handler\">"
                    + "<button type=\"submit\">Retry message handler</button></form>")
                    + "<form method=\"post\" action=\"" + escape(base)
                    + "/api/admin/zoho-setup/bot/details\">"
                    + "<button type=\"submit\">Fetch bot details from Zoho</button></form>"
                    + "<p>Deleting the bot is permanent and also removes its handlers and configuration in Zoho Cliq.</p>"
                    + "<form method=\"post\" action=\"" + escape(base)
                    + "/api/admin/zoho-setup/bot/delete\">"
                    + "<button type=\"submit\">Delete bot from Zoho Cliq</button></form>"
                    + (botDetails == null ? "" : "<h4>Zoho API response</h4><pre>"
                    + escape(botDetails) + "</pre>")
                    + "<p>Webhook URL: <code>" + escape(webhookUrl) + "</code>. "
                    + "The webhook replies in the current conversation with a greeting or matching booking details.</p>";
        }
        if (!connected) {
            return "<h3>Create Cliq bot</h3><p>Connect Zoho before creating a bot.</p>";
        }
        if (webhookUrl == null) {
            return "<h3>Create Cliq bot</h3><p>Set APP_BASE_URL to a public HTTPS URL before creating a bot.</p>";
        }

        return "<h3>Create webhook bot</h3>"
                + "<p>Enter the bot name and description. Bot access is set to organization. "
                + "The application supplies this webhook URL: <code>"
                + escape(webhookUrl) + "</code></p>"
                + "<form method=\"post\" action=\"" + escape(base)
                + "/api/admin/zoho-setup/bot\">"
                + "<label for=\"botName\">Bot name (maximum 20 characters)</label> "
                + "<input type=\"text\" id=\"botName\" name=\"name\" maxlength=\"20\" required><br>"
                + "<label for=\"botDescription\">Description (maximum 300 characters)</label> "
                + "<textarea id=\"botDescription\" name=\"description\" maxlength=\"300\" required></textarea><br>"
                + "<button type=\"submit\">Create Cliq bot</button></form>"
                + "<p>Zoho assigns the bot's unique name. Incoming messages will be routed to the existing "
                + "webhook, which can return a greeting or matching booking details.</p>";
    }

    private String redirectUri(HttpServletRequest request) {
        if (ZOHO_REDIRECT_URI != null && !ZOHO_REDIRECT_URI.isBlank()) {
            return ZOHO_REDIRECT_URI;
        }
        if (APP_BASE_URL != null && !APP_BASE_URL.isBlank()) {
            return APP_BASE_URL + "/api/admin/zoho-setup/callback";
        }
        if (request.isSecure()) {
            return request.getScheme() + "://" + request.getServerName()
                    + portSuffix(request) + request.getContextPath()
                    + "/api/admin/zoho-setup/callback";
        }
        return null;
    }

    private String webhookUrl(HttpServletRequest request) {
        if (APP_BASE_URL != null && !APP_BASE_URL.isBlank()) {
            return APP_BASE_URL.replaceAll("/+$", "") + "/api/cliq/webhook";
        }
        if (request.isSecure()) {
            return request.getScheme() + "://" + request.getServerName()
                    + portSuffix(request) + request.getContextPath()
                    + "/api/cliq/webhook";
        }
        return null;
    }

    private String base(HttpServletRequest request) {
        return APP_BASE_URL != null && !APP_BASE_URL.isBlank()
                ? APP_BASE_URL : request.getContextPath();
    }

    private String portSuffix(HttpServletRequest request) {
        int port = request.getServerPort();
        return (port == 80 || port == 443) ? "" : ":" + port;
    }

    private long currentAdminId(SecurityContext securityContext) {
        if (securityContext == null || securityContext.getUserPrincipal() == null) {
            throw new IllegalStateException("Authenticated administrator is required.");
        }
        return Long.parseLong(securityContext.getUserPrincipal().getName());
    }

    private boolean isSameAdmin(Object stateAdminId, SecurityContext securityContext) {
        if (!(stateAdminId instanceof Number adminId)
                || securityContext == null
                || securityContext.getUserPrincipal() == null) {
            return false;
        }
        try {
            return adminId.longValue()
                    == Long.parseLong(securityContext.getUserPrincipal().getName());
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private NewCookie stateCookie(String value, int maxAge) {
        return new NewCookie.Builder(STATE_COOKIE)
                .value(value)
                .path("/")
                .httpOnly(true)
                .secure(true)
                .sameSite(NewCookie.SameSite.LAX)
                .maxAge(maxAge)
                .build();
    }

    private Response withCookie(Response response, NewCookie cookie) {
        return Response.fromResponse(response).cookie(cookie).build();
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String setting(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
