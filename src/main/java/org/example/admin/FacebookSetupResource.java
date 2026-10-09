package org.example.admin;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.example.notification.MetaOAuthService;
import org.example.notification.MetaTokenCrypto;

import java.io.IOException;
import java.net.URI;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Path("admin/facebook-setup")
@RolesAllowed("ADMIN")
public class FacebookSetupResource {
    private static final Logger LOGGER = Logger.getLogger(FacebookSetupResource.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String STATE_COOKIE = "META_FACEBOOK_SETUP_STATE";
    private static final String TEMP_COOKIE = "META_FACEBOOK_SETUP_TEMP";
    private static final long FLOW_TTL_SECONDS = 10 * 60;
    private static final String REDIRECT_URI = setting("META_FACEBOOK_REDIRECT_URI");
    private static final String APP_BASE_URL = setting("APP_BASE_URL");

    @GET
    public Response status(@Context HttpServletRequest request) {
        try {
            MetaOAuthService.AppConfig config = MetaOAuthService.status();
            return setupPage(request, config, null, Response.Status.OK);
        } catch (SQLException | RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Unable to read Facebook setup status.", exception);
            return setupPage(request, null,
                    "Facebook setup status is unavailable. Check the database configuration.",
                    Response.Status.SERVICE_UNAVAILABLE);
        }
    }

    @POST
    @Path("credentials")
    public Response saveCredentials(
            @FormParam("appId") String appId,
            @FormParam("appSecret") String appSecret,
            @Context SecurityContext securityContext,
            @Context HttpServletRequest request) {
        try {
            MetaOAuthService.validateAndSaveAppCredentials(
                    appId, appSecret, currentAdminId(securityContext));
            return setupPage(request, MetaOAuthService.status(),
                    "Meta App credentials were validated and saved.", Response.Status.OK);
        } catch (MetaOAuthService.MetaApiException exception) {
            return setupPage(request, getConfigSafely(), exception.getMessage(),
                    Response.Status.BAD_REQUEST);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return setupPage(request, getConfigSafely(),
                    "Meta credential validation was interrupted. Please try again.",
                    Response.Status.SERVICE_UNAVAILABLE);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Unable to validate Meta App credentials.", exception);
            return setupPage(request, getConfigSafely(),
                    "Unable to contact Meta to validate these credentials. Check the App ID, "
                            + "App Secret, and network connection.",
                    Response.Status.SERVICE_UNAVAILABLE);
        } catch (SQLException | RuntimeException exception) {
            LOGGER.log(Level.SEVERE, "Unable to save validated Meta App credentials.", exception);
            return setupPage(request, getConfigSafely(),
                    "Credentials could not be saved. Check the database and META_TOKEN_ENCRYPTION_KEY.",
                    Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    @GET
    @Path("authorize")
    public Response authorize(
            @Context SecurityContext securityContext,
            @Context HttpServletRequest request) {
        if (REDIRECT_URI == null) {
            return setupPage(request, getConfigSafely(),
                    "Set META_FACEBOOK_REDIRECT_URI to this application's registered public HTTPS callback URL.",
                    Response.Status.INTERNAL_SERVER_ERROR);
        }
        try {
            long adminId = currentAdminId(securityContext);
            MetaTokenCrypto.validateKey();
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("purpose", "meta-facebook-setup");
            claims.put("admin_user_id", adminId);
            String state = JwtUtil.generate(claims, FLOW_TTL_SECONDS);
            String authorizationUrl = MetaOAuthService.authorizationUrl(REDIRECT_URI, state);
            NewCookie stateCookie = cookie(STATE_COOKIE, state, (int) FLOW_TTL_SECONDS);
            return Response.seeOther(URI.create(authorizationUrl)).cookie(stateCookie).build();
        } catch (MetaOAuthService.MetaApiException | SQLException | RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Unable to start Facebook OAuth.", exception);
            return setupPage(request, getConfigSafely(), exception.getMessage(),
                    Response.Status.SERVICE_UNAVAILABLE);
        }
    }

    @GET
    @Path("callback")
    public Response callback(
            @QueryParam("code") String code,
            @QueryParam("state") String state,
            @QueryParam("error") String oauthError,
            @QueryParam("error_description") String errorDescription,
            @CookieParam(STATE_COOKIE) String stateCookie,
            @Context SecurityContext securityContext,
            @Context HttpServletRequest request) {
        NewCookie clearState = cookie(STATE_COOKIE, "", 0);
        Map<String, Object> claims = JwtUtil.verify(state);
        if (state == null || stateCookie == null || !state.equals(stateCookie)
                || claims == null
                || !"meta-facebook-setup".equals(claims.get("purpose"))
                || !isSameAdmin(claims.get("admin_user_id"), securityContext)) {
            return withCookies(setupPage(request, getConfigSafely(),
                    "The Meta authorization session is invalid or expired. Start setup again.",
                    Response.Status.UNAUTHORIZED), clearState, cookie(TEMP_COOKIE, "", 0));
        }
        if (oauthError != null && !oauthError.isBlank()) {
            String message = (errorDescription == null || errorDescription.isBlank())
                    ? "Meta authorization was cancelled or denied."
                    : "Meta authorization failed: " + errorDescription;
            return withCookies(setupPage(request, getConfigSafely(), message,
                    Response.Status.BAD_REQUEST), clearState, cookie(TEMP_COOKIE, "", 0));
        }
        if (code == null || code.isBlank() || REDIRECT_URI == null) {
            return withCookies(setupPage(request, getConfigSafely(),
                    "Meta did not return an authorization code, or the callback URL is not configured.",
                    Response.Status.BAD_REQUEST), clearState, cookie(TEMP_COOKIE, "", 0));
        }
        try {
            String userToken = MetaOAuthService.exchangeTokenValue(code, REDIRECT_URI);
            MetaOAuthService.validateUserToken(userToken);
            List<MetaOAuthService.Business> businesses = MetaOAuthService.businesses(userToken);
            if (businesses.isEmpty()) {
                return withCookies(setupPage(request, getConfigSafely(),
                        "Authorization succeeded, but this Facebook account has no accessible "
                                + "Business Portfolio. Grant business access and try again.",
                        Response.Status.BAD_REQUEST), clearState, cookie(TEMP_COOKIE, "", 0));
            }
            String temporary = encryptedFlow(currentAdminId(securityContext), userToken, null, null);
            Response response = businessSelectionPage(request, businesses);
            return withCookies(response, clearState,
                    cookie(TEMP_COOKIE, temporary, (int) FLOW_TTL_SECONDS));
        } catch (MetaOAuthService.MetaApiException exception) {
            return withCookies(setupPage(request, getConfigSafely(), exception.getMessage(),
                    Response.Status.BAD_REQUEST), clearState, cookie(TEMP_COOKIE, "", 0));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return withCookies(setupPage(request, getConfigSafely(),
                    "Meta authorization was interrupted. Please try again.",
                    Response.Status.SERVICE_UNAVAILABLE), clearState, cookie(TEMP_COOKIE, "", 0));
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Unable to complete Meta OAuth callback.", exception);
            return withCookies(setupPage(request, getConfigSafely(),
                    "Unable to contact Meta to complete authorization.",
                    Response.Status.SERVICE_UNAVAILABLE), clearState, cookie(TEMP_COOKIE, "", 0));
        } catch (SQLException | RuntimeException exception) {
            LOGGER.log(Level.SEVERE, "Unable to process Meta OAuth callback.", exception);
            return withCookies(setupPage(request, getConfigSafely(),
                    "Unable to complete Facebook setup. Check the database and encryption key.",
                    Response.Status.INTERNAL_SERVER_ERROR), clearState, cookie(TEMP_COOKIE, "", 0));
        }
    }

    @POST
    @Path("business")
    public Response selectBusiness(
            @FormParam("businessId") String businessId,
            @CookieParam(TEMP_COOKIE) String temporaryCookie,
            @Context SecurityContext securityContext,
            @Context HttpServletRequest request) {
        Flow flow = readFlow(temporaryCookie, securityContext);
        if (flow == null) {
            return withCookies(setupPage(request, getConfigSafely(),
                    "The temporary Meta authorization expired. Connect again.",
                    Response.Status.UNAUTHORIZED), cookie(TEMP_COOKIE, "", 0));
        }
        try {
            List<MetaOAuthService.Business> businesses = MetaOAuthService.businesses(flow.userToken());
            MetaOAuthService.Business selected = businesses.stream()
                    .filter(item -> item.id().equals(businessId)).findFirst().orElse(null);
            if (selected == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .type(MediaType.TEXT_HTML)
                        .entity(simplePage("Select Business Portfolio",
                                "<p>The selected Business Portfolio is not available to this account.</p>"
                                        + backLink(request)))
                        .build();
            }
            List<MetaOAuthService.Waba> wabas =
                    MetaOAuthService.whatsappAccounts(selected.id(), flow.userToken());
            if (wabas.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .type(MediaType.TEXT_HTML)
                        .entity(simplePage("Select WhatsApp Account",
                                "<p>No WhatsApp Business Accounts were found in this portfolio. "
                                        + "Connect a WABA in Meta Business Settings and retry.</p>"
                                        + backLink(request)))
                        .build();
            }
            String temporary = encryptedFlow(flow.adminId(), flow.userToken(),
                    selected.id(), selected.name());
            return withCookies(wabaSelectionPage(request, selected, wabas),
                    cookie(TEMP_COOKIE, temporary, (int) FLOW_TTL_SECONDS));
        } catch (MetaOAuthService.MetaApiException exception) {
            return setupPage(request, getConfigSafely(), exception.getMessage(),
                    Response.Status.BAD_REQUEST);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return setupPage(request, getConfigSafely(),
                    "Meta account lookup was interrupted. Please try again.",
                    Response.Status.SERVICE_UNAVAILABLE);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Unable to load WhatsApp Business Accounts.", exception);
            return setupPage(request, getConfigSafely(),
                    "Unable to load WhatsApp Business Accounts from Meta.",
                    Response.Status.SERVICE_UNAVAILABLE);
        } catch (RuntimeException exception) {
            LOGGER.log(Level.SEVERE, "Unable to process Meta business selection.", exception);
            return setupPage(request, getConfigSafely(),
                    "Unable to continue Facebook setup. Check the database and encryption key.",
                    Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    @POST
    @Path("complete")
    public Response complete(
            @FormParam("wabaId") List<String> wabaIds,
            @CookieParam(TEMP_COOKIE) String temporaryCookie,
            @Context SecurityContext securityContext,
            @Context HttpServletRequest request) {
        Flow flow = readFlow(temporaryCookie, securityContext);
        if (flow == null || flow.businessId() == null) {
            return withCookies(setupPage(request, getConfigSafely(),
                    "The temporary Meta authorization expired. Connect again.",
                    Response.Status.UNAUTHORIZED), cookie(TEMP_COOKIE, "", 0));
        }
        if (wabaIds == null || wabaIds.isEmpty()) {
            return setupPage(request, getConfigSafely(),
                    "Select at least one WhatsApp Business Account.",
                    Response.Status.BAD_REQUEST);
        }
        try {
            List<MetaOAuthService.Waba> available =
                    MetaOAuthService.whatsappAccounts(flow.businessId(), flow.userToken());
            Map<String, MetaOAuthService.Waba> byId = new LinkedHashMap<>();
            for (MetaOAuthService.Waba waba : available) {
                byId.put(waba.id(), waba);
            }
            List<MetaOAuthService.Waba> selected = new ArrayList<>();
            for (String id : wabaIds) {
                MetaOAuthService.Waba waba = byId.get(id);
                if (waba == null) {
                    return setupPage(request, getConfigSafely(),
                            "A selected WhatsApp Business Account is not accessible. Refresh setup and try again.",
                            Response.Status.BAD_REQUEST);
                }
                if (selected.stream().noneMatch(existing -> existing.id().equals(id))) {
                    selected.add(waba);
                }
            }
            Map<String, List<MetaOAuthService.PhoneNumber>> phonesByWaba =
                    new LinkedHashMap<>();
            for (MetaOAuthService.Waba waba : selected) {
                phonesByWaba.put(waba.id(),
                        MetaOAuthService.phoneNumbers(waba.id(), flow.userToken()));
            }
            boolean hasPhoneNumber = phonesByWaba.values().stream().anyMatch(list -> !list.isEmpty());
            if (!hasPhoneNumber) {
                return setupPage(request, getConfigSafely(),
                        "The selected WhatsApp Business Accounts have no phone numbers. "
                                + "Register a WhatsApp phone number and retry.",
                        Response.Status.BAD_REQUEST);
            }
            List<MetaOAuthService.SystemUserAccess> access =
                    MetaOAuthService.persistCompletedSetup(flow.adminId(), flow.businessId(),
                    flow.businessName(), selected, phonesByWaba, flow.userToken());
            return withCookies(setupPage(request, MetaOAuthService.status(),
                    "Facebook and WhatsApp setup completed successfully.",
                    Response.Status.OK, access), cookie(TEMP_COOKIE, "", 0));
        } catch (MetaOAuthService.MetaApiException exception) {
            return setupPage(request, getConfigSafely(), exception.getMessage(),
                    Response.Status.BAD_REQUEST);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return setupPage(request, getConfigSafely(),
                    "Meta system-user provisioning was interrupted. Start setup again.",
                    Response.Status.SERVICE_UNAVAILABLE);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Unable to provision Meta system users.", exception);
            return setupPage(request, getConfigSafely(),
                    "Unable to complete Meta system-user provisioning. Check app access and permissions.",
                    Response.Status.SERVICE_UNAVAILABLE);
        } catch (SQLException | RuntimeException exception) {
            LOGGER.log(Level.SEVERE, "Unable to persist completed Meta setup.", exception);
            return setupPage(request, getConfigSafely(),
                    "Meta setup could not be saved. Check the database schema and encryption key.",
                    Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    private Response setupPage(HttpServletRequest request, MetaOAuthService.AppConfig config,
                               String message, Response.Status status) {
        return setupPage(request, config, message, status, List.of());
    }

    private Response setupPage(HttpServletRequest request, MetaOAuthService.AppConfig config,
                               String message, Response.Status status,
                               List<MetaOAuthService.SystemUserAccess> access) {
        String base = base(request);
        String connected = config != null && config.connected()
                ? "<p>Status: Connected to Business Portfolio <strong>"
                + escape(config.businessName()) + "</strong> (ID: "
                + escape(config.businessId()) + ").</p>"
                : "<p>Status: Not connected. Validate the customer's Meta App credentials, "
                + "then connect a Business Portfolio.</p>";
        String credentials = config == null
                ? "<p>No validated App credentials are stored.</p>"
                : "<p>Validated Meta App ID: <strong>" + escape(config.appId())
                + "</strong>. The App Secret is stored encrypted and is never displayed.</p>";
        String messageHtml = message == null ? ""
                : "<p role=\"status\">" + escape(message) + "</p>";
        String form = "<h2>Meta App Credentials</h2>"
                + "<p>Enter the Meta App ID and App Secret owned by this customer. "
                + "The callback URL is configured by the application.</p>"
                + "<form method=\"post\" action=\"" + escape(base)
                + "/api/admin/facebook-setup/credentials\">"
                + "<label for=\"appId\">App ID</label><br>"
                + "<input id=\"appId\" name=\"appId\" inputmode=\"numeric\" required><br><br>"
                + "<label for=\"appSecret\">App Secret</label><br>"
                + "<input id=\"appSecret\" name=\"appSecret\" type=\"password\" "
                + "autocomplete=\"new-password\" required><br><br>"
                + "<button type=\"submit\">Validate and Save Credentials</button></form>";
        String authorize = config == null ? ""
                : "<p><a href=\"" + escape(base)
                + "/api/admin/facebook-setup/authorize\">Connect Facebook / WhatsApp</a></p>";
        String templates = config != null && config.connected()
                ? "<p><a href=\"" + escape(base)
                + "/api/admin/facebook-setup/templates\">Templates</a></p>"
                : "";
        StringBuilder accessHtml = new StringBuilder();
        if (!access.isEmpty()) {
            accessHtml.append("<h2>System users</h2>")
                    .append("<p>Done means MANAGE or DEVELOP access to every selected WABA. ")
                    .append("Grant missing access in Meta Business Settings, then run setup again to generate tokens.</p>")
                    .append("<table><thead><tr><th>System user</th><th>ID</th><th>Role</th>")
                    .append("<th>WABA access</th><th>Missing WABAs</th></tr></thead><tbody>");
            for (MetaOAuthService.SystemUserAccess user : access) {
                accessHtml.append("<tr><td>").append(escape(user.name())).append("</td><td>")
                        .append(escape(user.id())).append("</td><td>")
                        .append(user.admin() ? "Admin" : "Employee").append("</td><td>")
                        .append(user.done() ? "Done" : "Not done").append("</td><td>");
                List<String> missing = new ArrayList<>();
                for (MetaOAuthService.Waba waba : user.missingWabas()) {
                    missing.add(escape(waba.name()) + " (ID: " + escape(waba.id()) + ")");
                }
                accessHtml.append(missing.isEmpty() ? "None" : String.join(", ", missing))
                        .append("</td></tr>");
            }
            accessHtml.append("</tbody></table>");
        }
        String html = "<h1>Parking Management System</h1>"
                + "<h2>Facebook / WhatsApp Setup</h2>" + messageHtml + connected
                + accessHtml + credentials + form + authorize + templates
                + "<p><a href=\"" + escape(base) + "/api/admin\">Admin Dashboard</a></p>"
                + "<form method=\"post\" action=\"" + escape(base) + "/api/auth/logout\">"
                + "<button type=\"submit\">Logout</button></form>";
        return Response.status(status).type(MediaType.TEXT_HTML)
                .header("Cache-Control", "no-store")
                .entity(simplePage("Facebook / WhatsApp Setup", html)).build();
    }

    private Response businessSelectionPage(HttpServletRequest request,
                                           List<MetaOAuthService.Business> businesses) {
        StringBuilder options = new StringBuilder();
        for (MetaOAuthService.Business business : businesses) {
            options.append("<option value=\"").append(escape(business.id())).append("\">")
                    .append(escape(business.name())).append(" (")
                    .append(escape(business.id())).append(")</option>");
        }
        String base = base(request);
        String content = "<h1>Select Business Portfolio</h1>"
                + "<p>Select the portfolio that owns the WhatsApp accounts for this customer.</p>"
                + "<form method=\"post\" action=\"" + escape(base)
                + "/api/admin/facebook-setup/business\">"
                + "<label for=\"businessId\">Business Portfolio</label><br>"
                + "<select id=\"businessId\" name=\"businessId\" required>" + options + "</select><br><br>"
                + "<button type=\"submit\">Continue</button></form>";
        return Response.ok(simplePage("Select Business Portfolio", content))
                .type(MediaType.TEXT_HTML).header("Cache-Control", "no-store").build();
    }

    private Response wabaSelectionPage(HttpServletRequest request,
                                       MetaOAuthService.Business business,
                                       List<MetaOAuthService.Waba> wabas) {
        StringBuilder choices = new StringBuilder();
        for (MetaOAuthService.Waba waba : wabas) {
            choices.append("<label><input type=\"checkbox\" name=\"wabaId\" value=\"")
                    .append(escape(waba.id())).append("\" checked> ")
                    .append(escape(waba.name())).append(" (")
                    .append(escape(waba.id())).append(")</label><br>");
        }
        String base = base(request);
        String content = "<h1>Select WhatsApp Business Accounts</h1><p>Business Portfolio: <strong>"
                + escape(business.name()) + " (" + escape(business.id()) + ")</strong></p>"
                + "<p>Select the WABAs and phone numbers the system should use.</p>"
                + "<form method=\"post\" action=\"" + escape(base)
                + "/api/admin/facebook-setup/complete\">" + choices
                + "<br><button type=\"submit\">Provision System Users and Finish Setup</button></form>";
        return Response.ok(simplePage("Select WhatsApp Business Accounts", content))
                .type(MediaType.TEXT_HTML).header("Cache-Control", "no-store").build();
    }

    private String encryptedFlow(long adminId, String userToken,
                                 String businessId, String businessName) {
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("admin_user_id", adminId);
            value.put("user_token", userToken);
            value.put("business_id", businessId);
            value.put("business_name", businessName);
            value.put("expires_at", System.currentTimeMillis() / 1000 + FLOW_TTL_SECONDS);
            return MetaTokenCrypto.encrypt(JSON.writeValueAsString(value));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to prepare temporary Meta setup state.", exception);
        }
    }

    private Flow readFlow(String cookieValue, SecurityContext securityContext) {
        if (cookieValue == null || cookieValue.isBlank()) {
            return null;
        }
        try {
            JsonNode value = JSON.readTree(MetaTokenCrypto.decrypt(cookieValue));
            long adminId = value.path("admin_user_id").asLong(-1);
            long expiresAt = value.path("expires_at").asLong(0);
            if (adminId < 1 || expiresAt < System.currentTimeMillis() / 1000
                    || !isSameAdmin(adminId, securityContext)) {
                return null;
            }
            String token = value.path("user_token").asText("");
            if (token.isBlank()) {
                return null;
            }
            return new Flow(adminId, token, nullableText(value, "business_id"),
                    nullableText(value, "business_name"));
        } catch (IOException | RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Unable to verify temporary Meta setup state.", exception);
            return null;
        }
    }

    private String nullableText(JsonNode node, String field) {
        String value = node.path(field).asText("");
        return value.isBlank() ? null : value;
    }

    private MetaOAuthService.AppConfig getConfigSafely() {
        try {
            return MetaOAuthService.status();
        } catch (SQLException exception) {
            LOGGER.log(Level.WARNING, "Unable to read Meta setup status after an error.", exception);
            return null;
        }
    }

    private long currentAdminId(SecurityContext securityContext) {
        if (securityContext == null || securityContext.getUserPrincipal() == null) {
            throw new IllegalStateException("Authenticated administrator is required.");
        }
        return Long.parseLong(securityContext.getUserPrincipal().getName());
    }

    private boolean isSameAdmin(Object stateAdminId, SecurityContext securityContext) {
        if (!(stateAdminId instanceof Number adminId)
                || securityContext == null || securityContext.getUserPrincipal() == null) {
            return false;
        }
        try {
            return adminId.longValue()
                    == Long.parseLong(securityContext.getUserPrincipal().getName());
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private boolean isSameAdmin(long stateAdminId, SecurityContext securityContext) {
        return isSameAdmin(Long.valueOf(stateAdminId), securityContext);
    }

    private NewCookie cookie(String name, String value, int maxAge) {
        return new NewCookie.Builder(name)
                .value(value)
                .path("/")
                .httpOnly(true)
                .secure(true)
                .sameSite(NewCookie.SameSite.LAX)
                .maxAge(maxAge)
                .build();
    }

    private Response withCookies(Response response, NewCookie... cookies) {
        Response.ResponseBuilder builder = Response.fromResponse(response);
        for (NewCookie cookie : cookies) {
            builder.cookie(cookie);
        }
        return builder.build();
    }

    private String base(HttpServletRequest request) {
        return APP_BASE_URL == null ? request.getContextPath() : APP_BASE_URL;
    }

    private String backLink(HttpServletRequest request) {
        return "<p><a href=\"" + escape(base(request)) + "/api/admin/facebook-setup\">Back to setup</a></p>";
    }

    private String simplePage(String title, String content) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"UTF-8\"><title>"
                + escape(title) + "</title></head><body>" + content + "</body></html>";
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String setting(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record Flow(long adminId, String userToken, String businessId, String businessName) { }
}
