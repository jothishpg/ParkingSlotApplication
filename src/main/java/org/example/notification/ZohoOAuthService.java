package org.example.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.DatabaseConnection;
import org.example.config.SecretEncryption;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ZohoOAuthService {
    public static final String BOT_READ_SCOPE = "ZohoCliq.Bots.READ";
    public static final String BOT_CREATE_SCOPE = "ZohoCliq.Bots.CREATE";
    public static final String BOT_UPDATE_SCOPE = "ZohoCliq.Bots.UPDATE";
    public static final String BOT_DELETE_SCOPE = "ZohoCliq.Bots.DELETE";

    private static final String ACCOUNTS_SERVER = "accounts.zoho.in";
    private static final String CLIQ_API_BASE_URL = "https://cliq.zoho.in/api/v3";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10)).build();
    private static final int ACCESS_TOKEN_REFRESH_SKEW_SECONDS = 60;

    private ZohoOAuthService() {
    }

    public record Authorization(
            String accessToken,
            String refreshToken,
            Instant accessTokenExpiresAt,
            List<String> scopes) {
    }

    public record Bot(
            String botId,
            String uniqueName,
            String name,
            String description,
            String scope,
            boolean messageHandlerConfigured) {
    }

    public static String authorizationUrl(String redirectUri, String state)
            throws ZohoSetupException, SQLException {
        ZohoOAuthConfig.Client client = requireClient();
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("client_id", client.clientId());
        parameters.put("response_type", "code");
        parameters.put("redirect_uri", redirectUri);
        parameters.put("scope",
                String.join(",", BOT_CREATE_SCOPE, BOT_READ_SCOPE,
                        BOT_UPDATE_SCOPE, BOT_DELETE_SCOPE));
        parameters.put("access_type", "offline");
        parameters.put("prompt", "consent");
        parameters.put("state", state);
        return "https://" + ACCOUNTS_SERVER + "/oauth/v2/auth?" + form(parameters);
    }

    public static Authorization exchangeAuthorizationCode(String code, String redirectUri)
            throws IOException, InterruptedException, SQLException, ZohoSetupException {
        ZohoOAuthConfig.Client client = requireClient();
        JsonNode response = postForm("https://" + ACCOUNTS_SERVER + "/oauth/v2/token",
                Map.of(
                        "code", code,
                        "client_id", client.clientId(),
                        "client_secret", client.clientSecret(),
                        "redirect_uri", redirectUri,
                        "grant_type", "authorization_code"));
        String accessToken = requiredText(response, "access_token");
        String refreshToken = requiredText(response, "refresh_token");
        long expiresIn = expiresInSeconds(response);
        List<String> scopes = parseScopes(requiredText(response, "scope"));
        if (!scopes.containsAll(List.of(
                BOT_CREATE_SCOPE, BOT_READ_SCOPE, BOT_UPDATE_SCOPE, BOT_DELETE_SCOPE))) {
            throw new ZohoSetupException(
                    "Zoho did not grant the Cliq bot read, create, update, and delete permissions. "
                            + "Recheck the OAuth client scopes and authorize again.");
        }
        return new Authorization(accessToken, refreshToken,
                Instant.now().plusSeconds(expiresIn), scopes);
    }

    public static void saveAuthorization(Authorization authorization, long adminUserId)
            throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM zoho_oauth_connection WHERE connection_id = 1")) {
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO zoho_oauth_connection
                            (connection_id, config_id, connected_by_user_id, connected_at,
                             created_at, updated_at)
                        VALUES (1, 1, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)) {
                    statement.setLong(1, adminUserId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO zoho_oauth_token
                            (connection_id, encrypted_access_token, encrypted_refresh_token,
                             access_token_expires_at, updated_at)
                        VALUES (1, ?, ?, ?, CURRENT_TIMESTAMP)
                        """)) {
                    statement.setString(1, SecretEncryption.encrypt(authorization.accessToken()));
                    statement.setString(2, SecretEncryption.encrypt(authorization.refreshToken()));
                    statement.setTimestamp(3,
                            Timestamp.from(authorization.accessTokenExpiresAt()));
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO zoho_oauth_connection_scope (connection_id, scope_name)
                        VALUES (1, ?)
                        """)) {
                    for (String scope : authorization.scopes()) {
                        statement.setString(1, scope);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public static Bot configuredBot() throws SQLException {
        String sql = """
                SELECT zoho_bot_id, bot_unique_name, bot_name, description, scope,
                       message_handler_configured
                FROM zoho_cliq_bot_config
                WHERE config_id = 1
                """;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            return new Bot(
                    result.getString("zoho_bot_id"),
                    result.getString("bot_unique_name"),
                    result.getString("bot_name"),
                    result.getString("description"),
                    result.getString("scope"),
                    result.getBoolean("message_handler_configured"));
        }
    }

    public static JsonNode fetchConfiguredBotDetails()
            throws IOException, InterruptedException, SQLException, ZohoSetupException {
        Bot bot = configuredBot();
        if (bot == null) {
            throw new ZohoSetupException("Create a Cliq bot before fetching its details.");
        }
        requireGrantedScopes(List.of(BOT_READ_SCOPE));
        return apiRequest("GET",
                CLIQ_API_BASE_URL + "/bots/" + encode(bot.botId()), null);
    }

    public static Bot deleteConfiguredBot()
            throws IOException, InterruptedException, SQLException, ZohoSetupException {
        Bot bot = configuredBot();
        if (bot == null) {
            throw new ZohoSetupException("There is no locally configured Cliq bot to delete.");
        }
        requireGrantedScopes(List.of(BOT_DELETE_SCOPE));
        apiRequest("DELETE", CLIQ_API_BASE_URL + "/bots/" + encode(bot.botId()), null);

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM zoho_cliq_bot_config WHERE config_id = 1 AND zoho_bot_id = ?")) {
            statement.setString(1, bot.botId());
            if (statement.executeUpdate() != 1) {
                throw new SQLException(
                        "Zoho deleted the bot, but its local configuration could not be removed.");
            }
        }
        return bot;
    }

    public static Bot createWebhookBot(
            String name,
            String description,
            String executionUrl,
            long adminUserId)
            throws IOException, InterruptedException, SQLException, ZohoSetupException {
        validateBotInput(name, description, executionUrl);
        if (configuredBot() != null) {
            throw new ZohoSetupException(
                    "A Cliq bot is already configured for this application.");
        }
        requireGrantedScopes(List.of(BOT_CREATE_SCOPE, BOT_UPDATE_SCOPE));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name.trim());
        body.put("description", description.trim());
        body.put("scope", "organization");
        body.put("execution_type", "webhook");
        body.put("execution_url", executionUrl);
        body.put("channel_participation", List.of("listen_message"));
        JsonNode response = apiRequest("POST", CLIQ_API_BASE_URL + "/bots", body);
        JsonNode data = response.path("data");
        String botId = requiredText(data, "id");
        String uniqueName = requiredText(data, "unique_name");
        String returnedName = optionalText(data, "name");
        String returnedDescription = optionalText(data, "description");
        String returnedScope = optionalText(data, "scope");
        Bot bot = new Bot(botId, uniqueName,
                returnedName == null ? name.trim() : returnedName,
                returnedDescription == null ? description.trim() : returnedDescription,
                returnedScope == null ? "organization" : returnedScope,
                false);
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO zoho_cliq_bot_config
                         (config_id, zoho_bot_id, bot_unique_name, bot_name, description,
                          scope, message_handler_configured, created_by_user_id,
                          created_at, updated_at)
                     VALUES (1, ?, ?, ?, ?, ?, FALSE, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                     """)) {
            statement.setString(1, bot.botId());
            statement.setString(2, bot.uniqueName());
            statement.setString(3, bot.name());
            statement.setString(4, bot.description());
            statement.setString(5, bot.scope());
            statement.setLong(6, adminUserId);
            statement.executeUpdate();
        }
        return bot;
    }

    public static Bot configureMessageHandler(String botId)
            throws IOException, InterruptedException, SQLException, ZohoSetupException {
        requireGrantedScopes(List.of(BOT_UPDATE_SCOPE));
        Bot bot = configuredBot();
        if (bot == null || !bot.botId().equals(botId)) {
            throw new ZohoSetupException(
                    "The bot is not recorded as configured. Create the bot before adding its message handler.");
        }
        if (bot.messageHandlerConfigured()) {
            return bot;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "message_handler");
        body.put("permissions", List.of("message", "user", "chat"));
        try {
            apiRequest("POST", CLIQ_API_BASE_URL + "/bots/" + encode(botId) + "/handlers", body);
        } catch (ZohoSetupException exception) {
            if (!"execution_handler_already_exists".equals(exception.errorCode())) {
                throw exception;
            }
        }
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE zoho_cliq_bot_config
                     SET message_handler_configured = TRUE,
                         updated_at = CURRENT_TIMESTAMP
                     WHERE config_id = 1 AND zoho_bot_id = ?
                     """)) {
            statement.setString(1, botId);
            if (statement.executeUpdate() != 1) {
                throw new ZohoSetupException(
                        "Zoho accepted the message handler, but the bot status could not be updated.");
            }
        }
        return new Bot(bot.botId(), bot.uniqueName(), bot.name(), bot.description(),
                bot.scope(), true);
    }

    public static boolean isConnected() throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM zoho_oauth_connection WHERE connection_id = 1");
             ResultSet result = statement.executeQuery()) {
            return result.next();
        }
    }

    public static void disconnect() throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM zoho_oauth_connection WHERE connection_id = 1")) {
            statement.executeUpdate();
        }
    }

    public static String currentAccessToken()
            throws SQLException, IOException, InterruptedException, ZohoSetupException {
        ZohoOAuthConfig.Client client = requireClient();
        String encryptedAccessToken;
        String encryptedRefreshToken;
        Instant expiresAt;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT encrypted_access_token, encrypted_refresh_token,
                            access_token_expires_at
                     FROM zoho_oauth_token
                     WHERE connection_id = 1
                     """);
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new ZohoSetupException(
                        "Connect the Zoho Cliq organization before using its API.");
            }
            encryptedAccessToken = result.getString("encrypted_access_token");
            encryptedRefreshToken = result.getString("encrypted_refresh_token");
            expiresAt = result.getTimestamp("access_token_expires_at").toInstant();
        }
        if (expiresAt.isAfter(Instant.now().plusSeconds(ACCESS_TOKEN_REFRESH_SKEW_SECONDS))) {
            return SecretEncryption.decrypt(encryptedAccessToken);
        }

        String refreshToken = SecretEncryption.decrypt(encryptedRefreshToken);
        JsonNode refreshed = postForm(
                "https://" + ACCOUNTS_SERVER + "/oauth/v2/token",
                Map.of(
                        "refresh_token", refreshToken,
                        "client_id", client.clientId(),
                        "client_secret", client.clientSecret(),
                        "grant_type", "refresh_token"));
        String accessToken = requiredText(refreshed, "access_token");
        Instant refreshedExpiry = Instant.now().plusSeconds(expiresInSeconds(refreshed));
        String nextRefreshToken = optionalText(refreshed, "refresh_token");
        if (nextRefreshToken == null) {
            nextRefreshToken = refreshToken;
        }
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE zoho_oauth_token
                     SET encrypted_access_token = ?,
                         encrypted_refresh_token = ?,
                         access_token_expires_at = ?,
                         updated_at = CURRENT_TIMESTAMP
                     WHERE connection_id = 1
                     """)) {
            statement.setString(1, SecretEncryption.encrypt(accessToken));
            statement.setString(2, SecretEncryption.encrypt(nextRefreshToken));
            statement.setTimestamp(3, Timestamp.from(refreshedExpiry));
            if (statement.executeUpdate() != 1) {
                throw new ZohoSetupException(
                        "The Zoho connection was removed while its access token was refreshing.");
            }
        }
        return accessToken;
    }

    private static ZohoOAuthConfig.Client requireClient()
            throws SQLException, ZohoSetupException {
        ZohoOAuthConfig.Client client = ZohoOAuthConfig.load();
        if (client == null) {
            throw new ZohoSetupException(
                    "Zoho OAuth is not configured. An administrator must save the client credentials in Zoho Setup.");
        }
        return client;
    }

    private static void requireGrantedScopes(List<String> requiredScopes)
            throws SQLException, ZohoSetupException {
        List<String> grantedScopes = new ArrayList<>();
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT scope_name
                     FROM zoho_oauth_connection_scope
                     WHERE connection_id = 1
                     """);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                grantedScopes.add(result.getString("scope_name"));
            }
        }
        if (!grantedScopes.containsAll(requiredScopes)) {
            throw new ZohoSetupException(
                    "Reconnect Zoho to grant the required Cliq bot permissions.");
        }
    }

    private static void validateBotInput(
            String name, String description, String executionUrl)
            throws ZohoSetupException {
        if (name == null || name.isBlank() || name.trim().length() > 20) {
            throw new ZohoSetupException("Bot name is required and must be at most 20 characters.");
        }
        if (description == null || description.isBlank() || description.trim().length() > 300) {
            throw new ZohoSetupException(
                    "Bot description is required and must be at most 300 characters.");
        }
        if (executionUrl == null || !executionUrl.startsWith("https://")) {
            throw new ZohoSetupException(
                    "Set APP_BASE_URL to a public HTTPS URL before creating the webhook bot.");
        }
    }

    private static JsonNode apiRequest(String method, String endpoint, Object body)
            throws IOException, InterruptedException, ZohoSetupException, SQLException {
        String accessToken = currentAccessToken();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(java.time.Duration.ofSeconds(30))
                .header("Authorization", "Zoho-oauthtoken " + accessToken)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
        HttpResponse<String> response =
                HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode responseBody;
        try {
            responseBody = response.body() == null || response.body().isBlank()
                    ? JSON.createObjectNode()
                    : JSON.readTree(response.body());
        } catch (IOException exception) {
            throw new ZohoSetupException("Zoho Cliq returned an unreadable API response.", exception);
        }
        String errorCode = responseBody == null ? null : optionalText(responseBody, "code");
        String status = responseBody == null ? null : optionalText(responseBody, "status");
        if (response.statusCode() < 200 || response.statusCode() >= 300
                || responseBody == null
                || (errorCode != null && !"success".equalsIgnoreCase(errorCode))
                || "error".equalsIgnoreCase(status)) {
            String message = responseBody == null ? null : optionalText(responseBody, "message");
            String detail = message == null ? "request rejected" : message;
            if (errorCode != null) {
                detail += " (" + errorCode + ")";
            }
            throw new ZohoSetupException("Zoho Cliq API request failed (HTTP "
                    + response.statusCode() + "): " + detail, errorCode);
        }
        return responseBody;
    }

    private static JsonNode postForm(String endpoint, Map<String, String> parameters)
            throws IOException, InterruptedException, ZohoSetupException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(parameters)))
                .build();
        HttpResponse<String> response =
                HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode body;
        try {
            body = JSON.readTree(response.body());
        } catch (IOException exception) {
            throw new ZohoSetupException("Zoho returned an unreadable OAuth response.", exception);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300
                || body == null || body.has("error")) {
            String description = body == null ? null : optionalText(body, "error_description");
            if (description == null && body != null) {
                description = optionalText(body, "error");
            }
            throw new ZohoSetupException("Zoho OAuth request failed (HTTP "
                    + response.statusCode() + ")"
                    + (description == null ? "." : ": " + description));
        }
        return body;
    }

    private static String form(Map<String, String> parameters) {
        List<String> values = new ArrayList<>();
        parameters.forEach((key, value) -> values.add(encode(key) + "=" + encode(value)));
        return String.join("&", values);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String requiredText(JsonNode node, String field) throws ZohoSetupException {
        String value = optionalText(node, field);
        if (value == null) {
            throw new ZohoSetupException("Zoho OAuth response did not include " + field + ".");
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private static long expiresInSeconds(JsonNode response) throws ZohoSetupException {
        long expiresIn = response.path("expires_in_sec").asLong(0);
        if (expiresIn <= 0) {
            expiresIn = response.path("expires_in").asLong(0);
        }
        if (expiresIn <= 0) {
            throw new ZohoSetupException("Zoho returned an invalid access-token lifetime.");
        }
        return expiresIn;
    }

    private static List<String> parseScopes(String value) {
        return java.util.Arrays.stream(value.split("[,\\s]+"))
                .map(String::trim)
                .filter(scope -> !scope.isEmpty())
                .distinct()
                .toList();
    }

    public static final class ZohoSetupException extends Exception {
        private static final long serialVersionUID = 1L;
        private final String errorCode;

        public ZohoSetupException(String message) {
            this(message, (String) null);
        }

        public ZohoSetupException(String message, String errorCode) {
            super(message);
            this.errorCode = errorCode;
        }

        public ZohoSetupException(String message, Throwable cause) {
            super(message, cause);
            this.errorCode = null;
        }

        public String errorCode() {
            return errorCode;
        }
    }
}
