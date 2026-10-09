package org.example.auth;

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import org.example.config.DatabaseConnection;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

@Path("auth")
public class AuthResource {

    private static final Logger LOGGER =
            Logger.getLogger(AuthResource.class.getName());
    private static final NetHttpTransport HTTP_TRANSPORT = new NetHttpTransport();
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String GOOGLE_CLIENT_ID =
            setting("GOOGLE_CLIENT_ID");
    private static final String GOOGLE_CLIENT_SECRET =
            setting("GOOGLE_CLIENT_SECRET");
    private static final String GOOGLE_REDIRECT_URI =
            setting("GOOGLE_REDIRECT_URI");
    private static final String APP_BASE_URL =
            setting("APP_BASE_URL");

    private static final String AUTH_COOKIE = "AUTH_TOKEN";
    private static final String GOOGLE_SIGNUP_COOKIE = "GOOGLE_SIGNUP_PENDING";
    private static final String TWO_FACTOR_COOKIE = "AUTH_2FA_PENDING";
    private static final long AUTH_TOKEN_TTL_SECONDS = 2 * 60 * 60;        // 2 hours
    private static final long GOOGLE_STATE_TTL_SECONDS = 10 * 60;          // 10 minutes
    private static final long GOOGLE_SIGNUP_TTL_SECONDS = 10 * 60;         // 10 minutes

    @POST
    @Path("login")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response login(
            @FormParam("email") String email,
            @FormParam("password") String password,
            @Context HttpServletRequest request) {

        if (blank(email) || blank(password)) {
            return htmlError(Response.Status.BAD_REQUEST, "Email and password are required.", request);
        }

        String sql = """
            SELECT u.user_id, u.name AS name, u.email, r.role_name, la.password
            FROM "users" u
            JOIN local_auth la ON u.user_id = la.user_id
            JOIN "roles" r ON u.role_id = r.role_id
            WHERE u.email = ?
            """;

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, email);

            try (ResultSet result = statement.executeQuery()) {
                if(!result.next()){
                    PasswordHasher.burnTime("");
                    return htmlError(Response.Status.UNAUTHORIZED, "Invalid email or password.", request);
                }
                if (!passwordMatches(password, result.getString("password"))) {
                    return htmlError(Response.Status.UNAUTHORIZED, "Invalid email or password.", request);
                }

                AuthenticatedUser user = userFromResult(result);

                LOGGER.info("LOGIN user_id=" + user.userId() + " role=" + user.role());

                if (TwoFactorService.isEnabled(connection, user.userId())) {
                    return beginTwoFactorLogin(user, request);
                }
                cancelOpenResetRequests(connection, user.userId());
                return redirectToRole(user, request);
            }

        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Database error during login.", exception);
            return htmlError(Response.Status.INTERNAL_SERVER_ERROR, "Unable to log in.", request);
        }
    }

    @POST
    @Path("signup")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response signup(
            @FormParam("name") String name,
            @FormParam("email") String email,
            @FormParam("phoneNumber") String phoneNumber,
            @FormParam("password") String password,
            @FormParam("confirmPassword") String confirmPassword,
            @Context HttpServletRequest request) {

        if (blank(name) || blank(email) || blank(phoneNumber)
                || blank(password) || blank(confirmPassword)) {
            return htmlError(Response.Status.BAD_REQUEST, "All signup fields are required.", request);
        }
        if (!validPhoneNumber(phoneNumber)) {
            return htmlError(Response.Status.BAD_REQUEST,
                    "Phone number must contain exactly 10 digits.", request);
        }

        if (!password.equals(confirmPassword)) {
            return htmlError(Response.Status.BAD_REQUEST,
                    "Password and confirm password do not match.", request);
        }

        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);

            try {
                if (emailExists(connection, email)) {
                    connection.rollback();
                    return htmlError(Response.Status.CONFLICT, "Email already exists.", request);
                }

                long userId = insertUser(
                        connection,
                        name,
                        email,
                        normalizePhoneNumber(phoneNumber),
                        userRoleId(connection)
                );

                insertAuthentication(
                        connection,
                        userId,
                        "LOCAL",
                        null,
                        PasswordHasher.hash(password)
                );

                connection.commit();

                AuthenticatedUser user =
                        new AuthenticatedUser(userId, name, email, "USER");

                return redirectToRole(user, request);

            } catch (SQLException exception) {
                connection.rollback();

                if ("23505".equals(exception.getSQLState())) {
                    return htmlError(Response.Status.CONFLICT, "Email already exists.", request);
                }

                throw exception;

            } finally {
                connection.setAutoCommit(true);
            }

        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Database error during signup.", exception);
            return htmlError(
                    Response.Status.INTERNAL_SERVER_ERROR,
                    "Unable to register user.", request
            );
        }
    }

    @GET
    @Path("google/start")
    @Produces(MediaType.TEXT_HTML)
    public Response startGoogleLogin(
            @QueryParam("mode") String mode,
            @Context HttpServletRequest request) {

        if (!googleConfigured()) {
            return htmlError(
                    Response.Status.INTERNAL_SERVER_ERROR,
                    "Google server configuration is incomplete.", request
            );
        }

        String googleMode = "signup".equalsIgnoreCase(mode) ? "signup" : "login";
        Map<String, Object> stateClaims = new LinkedHashMap<>();
        stateClaims.put("nonce", newNonce());
        stateClaims.put("mode", googleMode);
        String state = JwtUtil.generate(stateClaims, GOOGLE_STATE_TTL_SECONDS);

        String url =
                "https://accounts.google.com/o/oauth2/v2/auth"
                        + "?client_id=" + encode(GOOGLE_CLIENT_ID)
                        + "&redirect_uri=" + encode(googleRedirectUri(request))
                        + "&response_type=code"
                        + "&scope=" + encode("openid email profile")
                        + "&state=" + encode(state)
                        + "&prompt=select_account";

        return Response.seeOther(URI.create(url)).build();
    }

    @GET
    @Path("google/callback")
    @Produces(MediaType.TEXT_HTML)
    public Response googleCallback(
            @QueryParam("code") String code,
            @QueryParam("state") String state,
            @QueryParam("error") String oauthError,
            @Context HttpServletRequest request) {

        Map<String, Object> stateClaims = JwtUtil.verify(state);
        if (stateClaims == null) {
            return htmlError(
                    Response.Status.UNAUTHORIZED,
                    "Invalid or expired Google sign-in state.", request
            );
        }
        String googleMode = String.valueOf(stateClaims.get("mode"));

        if (!blank(oauthError) || blank(code)) {
            return htmlError(
                    Response.Status.UNAUTHORIZED,
                    "Google sign-in was cancelled or failed.", request
            );
        }

        if (!googleConfigured()) {
            return htmlError(
                    Response.Status.INTERNAL_SERVER_ERROR,
                    "Google server configuration is incomplete.", request
            );
        }

        try {
            GoogleTokenResponse tokenResponse =
                    new GoogleAuthorizationCodeTokenRequest(
                            HTTP_TRANSPORT,
                            JSON_FACTORY,
                            GOOGLE_CLIENT_ID,
                            GOOGLE_CLIENT_SECRET,
                            code,
                            googleRedirectUri(request)
                    ).execute();

            GoogleIdToken token =
                    googleVerifier().verify(tokenResponse.getIdToken());

            if (token == null) {
                return htmlError(
                        Response.Status.UNAUTHORIZED,
                        "Invalid Google authentication.", request
                );
            }

            GoogleIdToken.Payload payload = token.getPayload();

            String googleSub = payload.getSubject();
            String email = payload.getEmail();
            String name = (String) payload.get("name");

            if (blank(googleSub) || blank(email) || blank(name)) {
                return htmlError(
                        Response.Status.BAD_REQUEST,
                        "Google account did not provide a name and email.", request
                );
            }

            return completeGoogleLogin(
                    googleSub,
                    email,
                    name,
                    "signup".equals(googleMode),
                    null,
                    request
            );

        } catch (GeneralSecurityException | IOException exception) {
            return htmlError(
                    Response.Status.UNAUTHORIZED,
                    "Invalid Google authentication.", request
            );
        }
    }

    @POST
    @Path("google/complete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response completeGoogleSignup(
            @FormParam("phoneNumber") String phoneNumber,
            @CookieParam(GOOGLE_SIGNUP_COOKIE) String pendingToken,
            @Context HttpServletRequest request) {

        if (!validPhoneNumber(phoneNumber)) {
            return googlePhoneForm(
                    Response.Status.BAD_REQUEST,
                    "Phone number must contain exactly 10 digits.", request
            );
        }

        Map<String, Object> pending = JwtUtil.verify(pendingToken);
        if (pending == null) {
            return htmlError(
                    Response.Status.UNAUTHORIZED,
                    "Google signup session has expired. Please try again.", request
            );
        }

        String googleSub = String.valueOf(pending.get("sub"));
        String email = String.valueOf(pending.get("email"));
        String name = String.valueOf(pending.get("name"));

        return completeGoogleLogin(
                googleSub,
                email,
                name,
                true,
                phoneNumber,
                request
        );
    }

    private Response completeGoogleLogin(
            String googleSub,
            String email,
            String name,
            boolean signup,
            String phoneNumber,
            HttpServletRequest request) {

        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);

            try {
                AuthenticatedUser existingGoogleUser =
                        findGoogleUser(connection, googleSub);

                if (!signup && (existingGoogleUser != null)) {
                    boolean twoFactorEnabled =
                            TwoFactorService.isEnabled(connection, existingGoogleUser.userId());
                    connection.commit();
                    if (twoFactorEnabled) {
                        return beginTwoFactorLogin(existingGoogleUser, request);
                    }
                    return redirectToRole(existingGoogleUser, request);
                }

                if (emailExists(connection, email)) {
                    connection.rollback();
                    return htmlError(
                            Response.Status.CONFLICT,
                            signup
                                    ? "Email already exists."
                                    : "This email already has an account. Please log in using your existing login method.",
                            request
                    );
                }

                if (signup && !validPhoneNumber(phoneNumber)) {
                    connection.rollback();

                    Map<String, Object> pendingClaims = new LinkedHashMap<>();
                    pendingClaims.put("sub", googleSub);
                    pendingClaims.put("email", email);
                    pendingClaims.put("name", name);
                    String pendingToken =
                            JwtUtil.generate(pendingClaims, GOOGLE_SIGNUP_TTL_SECONDS);

                    NewCookie pendingCookie = new NewCookie.Builder(GOOGLE_SIGNUP_COOKIE)
                            .value(pendingToken)
                            .path("/")
                            .httpOnly(true)
                            .secure(true)
                            .sameSite(NewCookie.SameSite.LAX)
                            .maxAge((int) GOOGLE_SIGNUP_TTL_SECONDS)
                            .build();

                    return Response.status(Response.Status.OK)
                            .entity(googlePhoneFormHtml(null, request))
                            .type(MediaType.TEXT_HTML)
                            .cookie(pendingCookie)
                            .build();
                }

                long userId = insertUser(
                        connection,
                        name,
                        email,
                        normalizePhoneNumber(phoneNumber),
                        userRoleId(connection)
                );

                insertAuthentication(
                        connection,
                        userId,
                        "GOOGLE",
                        googleSub,
                        null
                );

                connection.commit();

                AuthenticatedUser user =
                        new AuthenticatedUser(
                                userId,
                                name,
                                email,
                                "USER"
                        );

                return redirectToRole(user, request, clearGoogleSignupCookie());

            } catch (SQLException exception) {
                connection.rollback();

                if ("23505".equals(exception.getSQLState())) {
                    return htmlError(
                            Response.Status.CONFLICT,
                            "An account with this Google identity or email already exists.", request
                    );
                }

                throw exception;

            } finally {
                connection.setAutoCommit(true);
            }

        } catch (SQLException exception) {
            return htmlError(
                    Response.Status.INTERNAL_SERVER_ERROR,
                    "Unable to authenticate with Google.", request
            );
        }
    }

    private Response googlePhoneForm(
            Response.Status status,
            String message,
            HttpServletRequest request) {
        return Response.status(status)
                .type(MediaType.TEXT_HTML)
                .entity(googlePhoneFormHtml(message, request))
                .build();
    }

    private String googlePhoneFormHtml(String message, HttpServletRequest request) {
        String base = base(request);
        String notice = blank(message)
                ? ""
                : "<p>" + escapeHtml(message) + "</p>";

        return "<!doctype html>"
                + "<html lang=\"en\">"
                + "<head>"
                + "<meta charset=\"UTF-8\">"
                + "<title>Complete Google Sign Up</title>"
                + "</head>"
                + "<body>"
                + "<h1>Parking Management System</h1>"
                + "<h2>Complete Sign Up</h2>"
                + notice
                + "<p>Enter your phone number to complete registration.</p>"
                + "<form method=\"post\" action=\"" + base + "/api/auth/google/complete\">"
                + "<label for=\"phoneNumber\">Phone Number</label><br>"
                + "<input type=\"tel\" id=\"phoneNumber\" name=\"phoneNumber\" "
                + "inputmode=\"numeric\" pattern=\"[0-9]{10}\" maxlength=\"10\" "
                + "title=\"Enter exactly 10 digits\" required>"
                + "<br><br>"
                + "<button type=\"submit\">Complete Sign Up</button>"
                + "</form>"
                + "</body>"
                + "</html>";
    }

    @POST
    @Path("logout")
    @Produces(MediaType.TEXT_HTML)
    public Response logout(@Context HttpServletRequest request) {
        return Response.seeOther(
                        URI.create(base(request) + "/login.html")
                )
                .cookie(clearAuthCookie())
                .cookie(clearTwoFactorCookie())
                .build();
    }

    @GET
    @Path("2fa")
    @Produces(MediaType.TEXT_HTML)
    public Response twoFactorLoginPage(
            @CookieParam(TWO_FACTOR_COOKIE) String pendingToken,
            @Context HttpServletRequest request) {
        Map<String, Object> claims = JwtUtil.verify(pendingToken);
        if (!isPendingTwoFactor(claims)) {
            return withCookie(htmlError(Response.Status.UNAUTHORIZED,
                    "The two-factor login session is invalid or expired. Please sign in again.",
                    request), clearTwoFactorCookie());
        }
        String message = "Enter the code from your authenticator app, or use a recovery code.";
        String body = "<h1>Two-factor verification</h1><p>" + message + "</p>"
                + "<form method=\"post\" action=\"" + base(request) + "/api/auth/2fa/verify\">"
                + "<label for=\"code\">Authenticator or recovery code</label><br>"
                + "<input id=\"code\" name=\"code\" autocomplete=\"one-time-code\" "
                + "required autofocus><br><br><button type=\"submit\">Verify and sign in</button>"
                + "</form><p><a href=\"" + base(request)
                + "/api/forgot-password/authenticator\">Lost authenticator and recovery codes?</a></p>"
                + "<p><a href=\"" + base(request)
                + "/login.html\">Cancel and return to login</a></p>";
        return Response.ok(simplePage("Two-factor verification", body))
                .type(MediaType.TEXT_HTML)
                .header("Cache-Control", "no-store")
                .build();
    }

    @POST
    @Path("2fa/verify")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response verifyTwoFactorLogin(
            @FormParam("code") String code,
            @CookieParam(TWO_FACTOR_COOKIE) String pendingToken,
            @Context HttpServletRequest request) {
        Map<String, Object> claims = JwtUtil.verify(pendingToken);
        if (!isPendingTwoFactor(claims)) {
            return withCookie(htmlError(Response.Status.UNAUTHORIZED,
                    "The two-factor login session is invalid or expired. Please sign in again.",
                    request), clearTwoFactorCookie());
        }

        try {
            UUID challengeId = UUID.fromString(String.valueOf(claims.get("challenge_id")));
            try (Connection connection = DatabaseConnection.getConnection()) {
                if (!TwoFactorService.verifyLoginCode(connection, challengeId, code)) {
                    return twoFactorLoginError(request,
                            "That code is invalid, already used, or the login session has expired.");
                }
                AuthenticatedUser user = findUserById(connection,
                        ((Number) claims.get("user_id")).longValue());
                if (user == null || !TwoFactorService.isEnabled(connection, user.userId())) {
                    return withCookie(htmlError(Response.Status.UNAUTHORIZED,
                            "Two-factor sign-in is no longer available. Please sign in again.",
                            request), clearTwoFactorCookie());
                }
                cancelOpenResetRequests(connection, user.userId());
                return redirectToRole(user, request, clearTwoFactorCookie());
            }
        } catch (IllegalArgumentException exception) {
            return withCookie(htmlError(Response.Status.UNAUTHORIZED,
                    "The two-factor login session is invalid. Please sign in again.",
                    request), clearTwoFactorCookie());
        } catch (SQLException | java.security.GeneralSecurityException exception) {
            LOGGER.log(Level.SEVERE, "Unable to verify two-factor login.", exception);
            return htmlError(Response.Status.INTERNAL_SERVER_ERROR,
                    "Unable to verify the sign-in code.", request);
        }
    }

    @GET
    @Path("me")
    @Produces(MediaType.APPLICATION_JSON)
    public Response me(@CookieParam(AUTH_COOKIE) String authToken) {

        Map<String, Object> claims = JwtUtil.verify(authToken);

        if (claims == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of(
                            "success", false,
                            "message", "Not authenticated"
                    ))
                    .build();
        }

        return Response.ok(Map.of(
                "success", true,
                "user", Map.of(
                        "userId", claims.get("user_id"),
                        "name", claims.get("name"),
                        "email", claims.get("email"),
                        "role", claims.get("role")
                )
        )).build();
    }

    private boolean emailExists(Connection connection, String email)
            throws SQLException {

        try (PreparedStatement statement =
                     connection.prepareStatement(
                             "SELECT 1 FROM users WHERE lower(email) = lower(?)")) {

            statement.setString(1, email);

            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }

        }
    }

    private boolean validPhoneNumber(String value) {
        return value != null && value.trim().matches("\\d{10}");
    }

    private String normalizePhoneNumber(String value) {
        return value.trim();
    }

    private long userRoleId(Connection connection)
            throws SQLException {

        try (PreparedStatement statement =
                     connection.prepareStatement(
                             "SELECT role_id FROM roles WHERE role_name = ?")) {

            statement.setString(1, "USER");

            try (ResultSet result = statement.executeQuery()) {

                if (!result.next()) {
                    throw new SQLException("User role does not exist");
                }

                return result.getLong("role_id");
            }
        }
    }

    private long insertUser(
            Connection connection,
            String name,
            String email,
            String phoneNumber,
            long roleId) throws SQLException {

        String sql =
                "INSERT INTO users "
                        + "(name, email, phone_number, role_id) "
                        + "VALUES (?, ?, ?, ?)";

        try (PreparedStatement statement =
                     connection.prepareStatement(
                             sql,
                             Statement.RETURN_GENERATED_KEYS)) {

            statement.setString(1, name);
            statement.setString(2, email);

            if (phoneNumber == null) {
                statement.setNull(3, java.sql.Types.VARCHAR);
            } else {
                statement.setString(3, phoneNumber);
            }

            statement.setLong(4, roleId);
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {

                if (!keys.next()) {
                    throw new SQLException("User ID was not generated");
                }

                return keys.getLong(1);
            }
        }
    }

    private void insertAuthentication(
            Connection connection,
            long userId,
            String provider,
            String providerId,
            String password) throws SQLException {

        if ("LOCAL".equalsIgnoreCase(provider)) {
            try (PreparedStatement statement =
                         connection.prepareStatement(
                                 "INSERT INTO local_auth (user_id, password) VALUES (?, ?)")) {
                statement.setLong(1, userId);
                statement.setString(2, password);
                statement.executeUpdate();
            }
        } else {
            try (PreparedStatement statement =
                         connection.prepareStatement(
                                 "INSERT INTO oauth_auth "
                                         + "(user_id, provider, provider_user_id) "
                                         + "VALUES (?, ?, ?)")) {
                statement.setLong(1, userId);
                statement.setString(2, provider.toLowerCase());
                statement.setString(3, providerId);
                statement.executeUpdate();
            }
        }
    }

    private AuthenticatedUser findGoogleUser(
            Connection connection,
            String googleSub) throws SQLException {

        String sql =
                "SELECT u.user_id, u.name AS name, u.email, r.role_name "
                        + "FROM oauth_auth oa "
                        + "JOIN users u ON oa.user_id = u.user_id "
                        + "JOIN roles r ON u.role_id = r.role_id "
                        + "WHERE oa.provider = 'google' "
                        + "AND oa.provider_user_id = ?";

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(1, googleSub);

            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? userFromResult(result)
                        : null;
            }
        }
    }

    private AuthenticatedUser userFromResult(ResultSet result)
            throws SQLException {

        return new AuthenticatedUser(
                result.getLong("user_id"),
                result.getString("name"),
                result.getString("email"),
                result.getString("role_name")
        );
    }

    private AuthenticatedUser findUserById(Connection connection, long userId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT u.user_id, u.name, u.email, r.role_name
                FROM users u JOIN roles r ON u.role_id = r.role_id
                WHERE u.user_id = ?
                """)) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? userFromResult(result) : null;
            }
        }
    }

    private Response beginTwoFactorLogin(
            AuthenticatedUser user, HttpServletRequest request) throws SQLException {
        UUID challengeId;
        try (Connection connection = DatabaseConnection.getConnection()) {
            challengeId = TwoFactorService.createLoginChallenge(connection, user.userId());
        }
        if (challengeId == null) {
            return htmlError(Response.Status.TOO_MANY_REQUESTS,
                    "Two-factor verification is temporarily locked after too many failed attempts. Try again later.",
                    request);
        }
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("purpose", "two-factor-login");
        claims.put("user_id", user.userId());
        claims.put("challenge_id", challengeId.toString());
        String pendingToken = JwtUtil.generate(claims, TwoFactorService.loginTtlSeconds());
        NewCookie pendingCookie = new NewCookie.Builder(TWO_FACTOR_COOKIE)
                .value(pendingToken)
                .path("/")
                .httpOnly(true)
                .secure(true)
                .sameSite(NewCookie.SameSite.LAX)
                .maxAge(TwoFactorService.loginTtlSeconds())
                .build();
        return Response.seeOther(URI.create(base(request) + "/api/auth/2fa"))
                .cookie(clearAuthCookie())
                .cookie(pendingCookie)
                .build();
    }

    private boolean isPendingTwoFactor(Map<String, Object> claims) {
        return claims != null
                && "two-factor-login".equals(claims.get("purpose"))
                && claims.get("user_id") instanceof Number
                && claims.get("challenge_id") instanceof String;
    }

    private Response twoFactorLoginError(HttpServletRequest request, String message) {
        String body = "<h1>Two-factor verification</h1><p>" + escapeHtml(message) + "</p>"
                + "<form method=\"post\" action=\"" + base(request) + "/api/auth/2fa/verify\">"
                + "<label for=\"code\">Authenticator or recovery code</label><br>"
                + "<input id=\"code\" name=\"code\" autocomplete=\"one-time-code\" required>"
                + "<br><br><button type=\"submit\">Verify and sign in</button></form>"
                + "<p><a href=\"" + base(request) + "/login.html\">Return to login</a></p>";
        return Response.status(Response.Status.UNAUTHORIZED)
                .type(MediaType.TEXT_HTML)
                .header("Cache-Control", "no-store")
                .entity(simplePage("Two-factor verification", body))
                .build();
    }

    private Response withCookie(Response response, NewCookie cookie) {
        return Response.fromResponse(response).cookie(cookie).build();
    }

    private String simplePage(String title, String body) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"UTF-8\"><title>"
                + escapeHtml(title) + "</title></head><body><h1>Parking Management System</h1>"
                + body + "</body></html>";
    }

    private void cancelOpenResetRequests(Connection connection, long userId) {
        try {
            ForgotPasswordResource.cancelOpenRequests(connection, userId);
        } catch (SQLException exception) {
            LOGGER.log(Level.WARNING, "Could not cancel reset requests.", exception);
        }
    }

    private NewCookie authCookie(AuthenticatedUser user) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("user_id", user.userId());
        claims.put("name", user.name());
        claims.put("email", user.email());
        claims.put("role", user.role());

        String token = JwtUtil.generate(claims, AUTH_TOKEN_TTL_SECONDS);

        return new NewCookie.Builder(AUTH_COOKIE)
                .value(token)
                .path("/")
                .httpOnly(true)
                .secure(true)
                .sameSite(NewCookie.SameSite.LAX)
                .maxAge((int) AUTH_TOKEN_TTL_SECONDS)
                .build();
    }

    private NewCookie clearAuthCookie() {
        return new NewCookie.Builder(AUTH_COOKIE)
                .value("")
                .path("/")
                .httpOnly(true)
                .secure(true)
                .sameSite(NewCookie.SameSite.LAX)
                .maxAge(0)
                .build();
    }

    private NewCookie clearGoogleSignupCookie() {
        return new NewCookie.Builder(GOOGLE_SIGNUP_COOKIE)
                .value("")
                .path("/")
                .httpOnly(true)
                .secure(true)
                .sameSite(NewCookie.SameSite.LAX)
                .maxAge(0)
                .build();
    }

    private NewCookie clearTwoFactorCookie() {
        return new NewCookie.Builder(TWO_FACTOR_COOKIE)
                .value("")
                .path("/")
                .httpOnly(true)
                .secure(true)
                .sameSite(NewCookie.SameSite.LAX)
                .maxAge(0)
                .build();
    }

    private String base(HttpServletRequest request) {
        if (!blank(APP_BASE_URL)) {
            return APP_BASE_URL;
        }
        return request.getScheme() + "://" + request.getServerName() + request.getContextPath();
    }

    private String redirectUrlForRole(String role, HttpServletRequest request) {
        String base = base(request);
        return switch (role) {
            case "USER" -> base + "/api/pages/user";
            case "TICKET_ASSIGNER" -> base + "/api/pages/ticket-assigner";
            case "ADMIN" -> base + "/api/pages/admin";
            default -> null;
        };
    }

    private Response redirectToRole(AuthenticatedUser user, HttpServletRequest request) {
        return redirectToRole(user, request, null);
    }

    private Response redirectToRole(
            AuthenticatedUser user,
            HttpServletRequest request,
            NewCookie extraCookie) {

        String url = redirectUrlForRole(user.role(), request);
        if (url == null) {
            return htmlError(Response.Status.FORBIDDEN, "Unknown user role.", request);
        }

        Response.ResponseBuilder builder = Response.seeOther(URI.create(url))
                .cookie(authCookie(user));

        if (extraCookie != null) {
            builder.cookie(extraCookie);
        }

        return builder.build();
    }

    private Response htmlError(
            Response.Status status,
            String message,
            HttpServletRequest request) {

        String html =
                "<!doctype html>"
                        + "<html lang=\"en\">"
                        + "<head>"
                        + "<meta charset=\"UTF-8\">"
                        + "<title>Error</title>"
                        + "</head>"
                        + "<body>"
                        + "<h1>Parking Management System</h1>"
                        + "<p>" + escapeHtml(message) + "</p>"
                        + "<p><a href=\"" + base(request) + "/login.html\">"
                        + "Return to login"
                        + "</a></p>"
                        + "</body>"
                        + "</html>";

        return Response.status(status)
                .type(MediaType.TEXT_HTML)
                .entity(html)
                .build();
    }

    private String escapeHtml(String value) {

        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private boolean googleConfigured() {
        return !blank(GOOGLE_CLIENT_ID)
                && !blank(GOOGLE_CLIENT_SECRET);
    }

    private String googleRedirectUri(HttpServletRequest request) {
        if (!blank(GOOGLE_REDIRECT_URI)) {
            return GOOGLE_REDIRECT_URI;
        }

        StringBuilder uri = new StringBuilder()
                .append(request.getScheme())
                .append("://")
                .append(request.getServerName());

        int port = request.getServerPort();
        if (port != 80 && port != 443) {
            uri.append(':').append(port);
        }

        return uri.append(request.getContextPath())
                .append("/api/auth/google/callback")
                .toString();
    }

    private static String setting(String environmentName) {
        String environmentValue = System.getenv(environmentName);
        return environmentValue == null ? null : environmentValue;
    }

    private GoogleIdTokenVerifier googleVerifier() {

        return new GoogleIdTokenVerifier.Builder(
                HTTP_TRANSPORT,
                JSON_FACTORY
        )
                .setAudience(
                        Collections.singletonList(GOOGLE_CLIENT_ID)
                )
                .build();
    }

    private String newNonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String encode(String value) {
        return URLEncoder.encode(
                value,
                StandardCharsets.UTF_8
        );
    }

    private boolean passwordMatches(String plainPassword, String storedHash) {
        if (storedHash == null || storedHash.isEmpty()) {
            return false;
        }
        try {
            return PasswordHasher.matches(plainPassword, storedHash);
        } catch (IllegalArgumentException exception) {
            LOGGER.warning("Stored password is not a valid bcrypt hash.");
            return false;
        }
    }

    public record AuthenticatedUser(
            long userId,
            String name,
            String email,
            String role) {
    }
}