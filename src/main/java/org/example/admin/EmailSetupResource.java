package org.example.admin;

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
import org.example.notification.GmailEmailService;

import java.io.IOException;
import java.net.URI;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Path("admin/email-setup")
@RolesAllowed("ADMIN")
public class EmailSetupResource {
    private static final Logger LOGGER = Logger.getLogger(EmailSetupResource.class.getName());
    private static final String STATE_COOKIE = "GMAIL_EMAIL_SETUP_STATE";
    private static final long STATE_TTL_SECONDS = 10 * 60;
    private static final String APP_BASE_URL = setting("APP_BASE_URL");
    private static final String GMAIL_REDIRECT_URI = setting("GMAIL_REDIRECT_URI");

    @GET
    public Response status(@Context HttpServletRequest request) {
        return setupPage(request, null, Response.Status.OK);
    }

    @GET
    @Path("authorize")
    public Response authorize(
            @Context SecurityContext securityContext,
            @Context HttpServletRequest request) {
        String redirectUri = redirectUri(request);
        if (redirectUri == null) {
            return setupPage(request,
                    "Set GMAIL_REDIRECT_URI to this application's public HTTPS callback URL before connecting Gmail.",
                    Response.Status.INTERNAL_SERVER_ERROR);
        }

        try {
            long adminId = currentAdminId(securityContext);
            GmailEmailService.validateSetupRequirements();
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("purpose", "gmail-email-setup");
            claims.put("admin_user_id", adminId);
            String state = JwtUtil.generate(claims, STATE_TTL_SECONDS);
            String authorizationUrl = GmailEmailService.authorizationUrl(redirectUri, state);
            NewCookie stateCookie = new NewCookie.Builder(STATE_COOKIE)
                    .value(state)
                    .path("/")
                    .httpOnly(true)
                    .secure(true)
                    .sameSite(NewCookie.SameSite.LAX)
                    .maxAge((int) STATE_TTL_SECONDS)
                    .build();
            return Response.seeOther(URI.create(authorizationUrl))
                    .cookie(stateCookie)
                    .build();
        } catch (GmailEmailService.EmailSetupException exception) {
            return setupPage(request, exception.getMessage(), Response.Status.SERVICE_UNAVAILABLE);
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
        NewCookie clearState = clearStateCookie();
        Map<String, Object> claims = JwtUtil.verify(state);
        if (claims == null || !state.equals(stateCookie)
                || !"gmail-email-setup".equals(claims.get("purpose"))
                || !isSameAdmin(claims.get("admin_user_id"), securityContext)) {
            return withCookie(setupPage(request,
                    "The Google authorization session is invalid or expired. Please try connecting again.",
                    Response.Status.UNAUTHORIZED), clearState);
        }

        if (oauthError != null && !oauthError.isBlank()) {
            return withCookie(setupPage(request,
                    "Google authorization was cancelled or denied. No email account was connected.",
                    Response.Status.BAD_REQUEST), clearState);
        }
        if (code == null || code.isBlank()) {
            return withCookie(setupPage(request,
                    "Google did not return an authorization code. Please try again.",
                    Response.Status.BAD_REQUEST), clearState);
        }

        String redirectUri = redirectUri(request);
        if (redirectUri == null) {
            return withCookie(setupPage(request, "GMAIL_REDIRECT_URI is not configured.",
                    Response.Status.INTERNAL_SERVER_ERROR), clearState);
        }

        try {
            GmailEmailService.Authorization authorization =
                    GmailEmailService.exchangeAndVerify(code, redirectUri);
            GmailEmailService.saveAuthorization(
                    authorization, currentAdminId(securityContext));
            return withCookie(setupPage(request, "Email sending is connected as "
                    + authorization.senderEmail() + ".", Response.Status.OK), clearState);
        } catch (GmailEmailService.EmailSetupException exception) {
            return withCookie(setupPage(request, exception.getMessage(),
                    Response.Status.BAD_REQUEST), clearState);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return withCookie(setupPage(request,
                    "Google authorization was interrupted. Please try again.",
                    Response.Status.SERVICE_UNAVAILABLE), clearState);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Unable to complete Gmail OAuth authorization.", exception);
            return withCookie(setupPage(request,
                    "Unable to contact Google. Verify the Gmail API and OAuth configuration, then try again.",
                    Response.Status.SERVICE_UNAVAILABLE), clearState);
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to persist Gmail OAuth authorization.", exception);
            return withCookie(setupPage(request,
                    "Google authorization succeeded, but the sender could not be saved. Check the database migration and retry.",
                    Response.Status.INTERNAL_SERVER_ERROR), clearState);
        } catch (Exception exception) {
            LOGGER.log(Level.SEVERE, "Unable to save Google email authorization.", exception);
            return withCookie(setupPage(request,
                    "Unable to save the Gmail authorization. Check the database and try again.",
                    Response.Status.INTERNAL_SERVER_ERROR), clearState);
        }
    }

    @POST
    @Path("disconnect")
    public Response disconnect(@Context HttpServletRequest request) {
        try {
            GmailEmailService.disconnect();
            return setupPage(request, "The system Gmail sender has been disconnected.",
                    Response.Status.OK);
        } catch (Exception exception) {
            LOGGER.log(Level.SEVERE, "Unable to disconnect the system Gmail sender.", exception);
            return setupPage(request, "Unable to disconnect the Gmail sender.",
                    Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    @POST
    @Path("reply-to")
    public Response saveReplyTo(
            @FormParam("replyToEmail") String replyToEmail,
            @Context HttpServletRequest request) {
        String normalizedEmail = replyToEmail == null ? "" : replyToEmail.trim();
        if (!normalizedEmail.isEmpty()) {
            try {
                new jakarta.mail.internet.InternetAddress(normalizedEmail, true).validate();
            } catch (jakarta.mail.internet.AddressException exception) {
                return setupPage(request, "Enter a valid Reply-To email address.",
                        Response.Status.BAD_REQUEST);
            }
        }

        try {
            GmailEmailService.saveReplyToEmail(normalizedEmail);
            String message = normalizedEmail.isEmpty()
                    ? "The Reply-To address has been removed."
                    : "Reply-To address saved as " + normalizedEmail + ".";
            return setupPage(request, message, Response.Status.OK);
        } catch (GmailEmailService.EmailSetupException exception) {
            return setupPage(request, exception.getMessage(), Response.Status.BAD_REQUEST);
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to save the Gmail Reply-To address.", exception);
            return setupPage(request,
                    "Unable to save the Reply-To address. Verify the reply_to_email database column exists.",
                    Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    private Response setupPage(HttpServletRequest request, String message, Response.Status status) {
        String base = base(request);
        String sender;
        String replyToEmail;
        try {
            sender = GmailEmailService.validateStoredAuthorization();
        } catch (GmailEmailService.EmailSetupException exception) {
            LOGGER.log(Level.WARNING, "Gmail sender authorization is unavailable.", exception);
            sender = null;
            if (message == null) {
                message = exception.getMessage();
                status = Response.Status.SERVICE_UNAVAILABLE;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.WARNING, "Gmail sender status validation was interrupted.", exception);
            sender = null;
            if (message == null) {
                message = "Unable to verify the Gmail connection right now.";
                status = Response.Status.SERVICE_UNAVAILABLE;
            }
        } catch (Exception exception) {
            LOGGER.log(Level.WARNING, "Unable to read Gmail email setup status.", exception);
            sender = null;
            if (message == null) {
                message = "Email setup status is unavailable. Check the database migration and token-encryption key.";
                status = Response.Status.SERVICE_UNAVAILABLE;
            }
        }

        try {
            replyToEmail = GmailEmailService.getReplyToEmail();
        } catch (SQLException exception) {
            LOGGER.log(Level.WARNING, "Unable to read the Gmail Reply-To address.", exception);
            replyToEmail = null;
            if (message == null) {
                message = "Reply-To settings are unavailable. Verify the reply_to_email database column exists.";
                status = Response.Status.SERVICE_UNAVAILABLE;
            }
        }

        String content = "<h2>Email Setup</h2>"
                + "<p>This is the global sender mailbox used for forgot-password emails. "
                + "Any administrator can connect a Google account; connecting another account replaces the current sender.</p>"
                + (message == null ? "" : "<p role=\"status\">" + escape(message) + "</p>")
                + (sender == null
                ? "<p>Status: Not connected. Email password reset is unavailable until an administrator connects Gmail.</p>"
                : "<p>Status: Connected as <strong>" + escape(sender) + "</strong>.</p>")
                + "<p><a href=\"" + escape(base) + "/api/admin/email-setup/authorize\">"
                + (sender == null ? "Connect Gmail" : "Reconnect / change sender") + "</a></p>"
                + "<h3>Reply-To address</h3>"
                + "<p>Optionally choose where recipients' replies should go. Leave blank to omit the Reply-To header.</p>"
                + "<form method=\"post\" action=\"" + escape(base)
                + "/api/admin/email-setup/reply-to\">"
                + "<label for=\"replyToEmail\">Reply-To email</label> "
                + "<input type=\"email\" id=\"replyToEmail\" name=\"replyToEmail\" maxlength=\"320\" value=\""
                + escape(replyToEmail) + "\"> "
                + "<button type=\"submit\">Save Reply-To</button></form>"
                + (sender == null ? "" : "<form method=\"post\" action=\""
                + escape(base) + "/api/admin/email-setup/disconnect\">"
                + "<button type=\"submit\">Disconnect email sending</button></form>")
                + "<p><a href=\"" + escape(base) + "/api/admin\">Admin Dashboard</a></p>"
                + "<form method=\"post\" action=\"" + escape(base) + "/api/auth/logout\">"
                + "<button type=\"submit\">Logout</button></form>";
        String html = "<!doctype html><html lang=\"en\"><head><meta charset=\"UTF-8\">"
                + "<title>Email Setup</title></head><body><h1>Parking Management System</h1>"
                + content + "</body></html>";
        return Response.status(status).type(MediaType.TEXT_HTML).entity(html).build();
    }

    private String redirectUri(HttpServletRequest request) {
        if (GMAIL_REDIRECT_URI != null && !GMAIL_REDIRECT_URI.isBlank()) {
            return GMAIL_REDIRECT_URI;
        }
        if (APP_BASE_URL != null && !APP_BASE_URL.isBlank()) {
            return APP_BASE_URL + "/api/admin/email-setup/callback";
        }
        if (request.isSecure()) {
            return request.getScheme() + "://" + request.getServerName()
                    + portSuffix(request) + request.getContextPath()
                    + "/api/admin/email-setup/callback";
        }
        return null;
    }

    private String base(HttpServletRequest request) {
        if (APP_BASE_URL != null && !APP_BASE_URL.isBlank()) {
            return APP_BASE_URL;
        }
        return request.getContextPath();
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

    private NewCookie clearStateCookie() {
        return new NewCookie.Builder(STATE_COOKIE)
                .value("")
                .path("/")
                .httpOnly(true)
                .secure(true)
                .sameSite(NewCookie.SameSite.LAX)
                .maxAge(0)
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
