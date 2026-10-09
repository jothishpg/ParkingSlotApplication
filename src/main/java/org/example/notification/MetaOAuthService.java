package org.example.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.DatabaseConnection;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public final class MetaOAuthService {
    public static final String BUSINESS_MANAGEMENT = "business_management";
    public static final String WHATSAPP_MANAGEMENT = "whatsapp_business_management";
    public static final String WHATSAPP_MESSAGING = "whatsapp_business_messaging";
    public static final String WHATSAPP_MANAGE_EVENTS = "whatsapp_business_manage_events";
    public static final List<String> REQUIRED_OAUTH_SCOPES = List.of(
            BUSINESS_MANAGEMENT,
            WHATSAPP_MANAGEMENT,
            WHATSAPP_MESSAGING,
            WHATSAPP_MANAGE_EVENTS);
    private static final Set<String> ADMIN_SYSTEM_USER_SCOPES =
            Set.of(BUSINESS_MANAGEMENT);
    private static final Set<String> EMPLOYEE_SYSTEM_USER_SCOPES =
            Set.of(WHATSAPP_MANAGEMENT, WHATSAPP_MESSAGING, WHATSAPP_MANAGE_EVENTS);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String API_VERSION = setting("META_WHATSAPP_API_VERSION", "v23.0");
    private static final String GRAPH_BASE = "https://graph.facebook.com/" + API_VERSION;
    private static final String GRAPH_HOST = "graph.facebook.com";
    private static final Logger LOGGER = Logger.getLogger(MetaOAuthService.class.getName());
    ;

    private MetaOAuthService() {
    }

    public record AppConfig(String appId, String encryptedAppSecret, String businessId,
                            String businessName, boolean connected) { }

    public record Business(String id, String name) { }

    public record Waba(String id, String name) { }

    public record PhoneNumber(String id, String displayNumber, String verifiedName) { }

    private record Token(String value, Instant expiresAt, Set<String> scopes) { }

    private record SystemUser(String id, String name, boolean admin) { }

    private record PersistedSystemUser(SystemUser user, Token token,
                                       Map<String, Set<String>> assignments) { }

    public record SystemUserAccess(String id, String name, boolean admin, List<Waba> missingWabas) {
        public boolean done() {
            return missingWabas.isEmpty();
        }
    }

    public record MessageTemplate(String id, String name, String category, String language,
                                  String status, JsonNode components, JsonNode details) { }

    public static AppConfig status() throws SQLException {
        String sql = """
            SELECT app_id, encrypted_app_secret, business_id, business_name
            FROM meta_app_config
            WHERE config_id = 1
            """;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            String businessId = result.getString("business_id");
            return new AppConfig(result.getString("app_id"),
                    result.getString("encrypted_app_secret"), businessId,
                    result.getString("business_name"), businessId != null);
        }
    }

    public static void validateAndSaveAppCredentials(String appId, String appSecret,
                                                     long adminId)
            throws MetaApiException, IOException, InterruptedException, SQLException {
        if (appId == null || !appId.trim().matches("[0-9]{5,32}")) {
            throw new MetaApiException("Enter a valid Meta App ID.");
        }
        if (appSecret == null || appSecret.isBlank() || appSecret.length() > 512) {
            throw new MetaApiException("Enter a valid Meta App Secret.");
        }
        MetaTokenCrypto.validateKey();
        String normalizedAppId = appId.trim();
        String appAccessToken = normalizedAppId + "|" + appSecret.trim();
        JsonNode app = graphGet("/" + encodePath(normalizedAppId),
                Map.of("fields", "id,name"), appAccessToken);
        if (!normalizedAppId.equals(requiredText(app, "id"))) {
            throw new MetaApiException("Meta returned a different App ID. Check the App ID and App Secret.");
        }

        String currentAppId = null;
        String currentEncryptedSecret = null;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT app_id, encrypted_app_secret "
                             + "FROM meta_app_config WHERE config_id = 1");
             ResultSet result = statement.executeQuery()) {
            if (result.next()) {
                currentAppId = result.getString("app_id");
                currentEncryptedSecret = result.getString("encrypted_app_secret");
            }
        }
        boolean appChanged = currentAppId != null
                && (!currentAppId.equals(normalizedAppId)
                || !appSecret.trim().equals(MetaTokenCrypto.decrypt(currentEncryptedSecret)));
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (appChanged) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "DELETE FROM meta_system_user")) {
                        statement.executeUpdate();
                    }
                    try (PreparedStatement statement = connection.prepareStatement(
                            "DELETE FROM meta_waba")) {
                        statement.executeUpdate();
                    }
                }
                String sql = """
                    INSERT INTO meta_app_config
                        (config_id, app_id, encrypted_app_secret, credentials_validated_at,
                         configured_by_user_id, created_at, updated_at)
                    VALUES (1, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    ON CONFLICT (config_id) DO UPDATE SET
                        app_id = EXCLUDED.app_id,
                        encrypted_app_secret = EXCLUDED.encrypted_app_secret,
                        credentials_validated_at = CURRENT_TIMESTAMP,
                        configured_by_user_id = EXCLUDED.configured_by_user_id,
                        updated_at = CURRENT_TIMESTAMP,
                        business_id = CASE WHEN ? THEN NULL
                                           ELSE meta_app_config.business_id END,
                        business_name = CASE WHEN ? THEN NULL
                                             ELSE meta_app_config.business_name END,
                        connected_at = CASE WHEN ? THEN NULL
                                            ELSE meta_app_config.connected_at END
                    """;
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, normalizedAppId);
                    statement.setString(2, MetaTokenCrypto.encrypt(appSecret.trim()));
                    statement.setLong(3, adminId);
                    statement.setBoolean(4, appChanged);
                    statement.setBoolean(5, appChanged);
                    statement.setBoolean(6, appChanged);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public static String authorizationUrl(String redirectUri, String state)
            throws SQLException, MetaApiException {
        AppConfig config = requiredConfig();
        String scopes = String.join(",", REQUIRED_OAUTH_SCOPES);
        return "https://www.facebook.com/" + API_VERSION + "/dialog/oauth"
                + "?client_id=" + encode(config.appId())
                + "&redirect_uri=" + encode(redirectUri)
                + "&response_type=code"
                + "&scope=" + encode(scopes)
                + "&state=" + encode(state);
    }

    public static String exchangeTokenValue(String code, String redirectUri)
            throws SQLException, MetaApiException, IOException, InterruptedException {
        AppConfig config = requiredConfig();
        String appSecret = MetaTokenCrypto.decrypt(config.encryptedAppSecret());
        JsonNode response = graphGet("/oauth/access_token", Map.of(
                "client_id", config.appId(),
                "client_secret", appSecret,
                "redirect_uri", redirectUri,
                "code", code), null);
        return requiredText(response, "access_token");
    }

    public static Set<String> validateUserToken(String userToken)
            throws SQLException, MetaApiException, IOException, InterruptedException {
        AppConfig config = requiredConfig();
        Set<String> scopes = debugToken(userToken, config);
        Set<String> missing = new LinkedHashSet<>(REQUIRED_OAUTH_SCOPES);
        missing.removeAll(scopes);
        if (!missing.isEmpty()) {
            throw new MetaApiException("Meta did not grant all required permissions: "
                    + String.join(", ", missing)
                    + ". Review the app's permission access and reconnect.");
        }
        return scopes;
    }

    public static List<Business> businesses(String userToken)
            throws MetaApiException, IOException, InterruptedException {
        List<Business> businesses = new ArrayList<>();
        for (JsonNode item : graphList("/me/businesses", Map.of("fields", "id,name"),
                userToken)) {
            String id = item.path("id").asText("");
            if (!id.isBlank()) {
                businesses.add(new Business(id, item.path("name").asText(id)));
            }
        }
        return businesses;
    }

    public static List<Waba> whatsappAccounts(String businessId, String userToken)
            throws MetaApiException, IOException, InterruptedException {
        List<Waba> result = new ArrayList<>();
        addWabas(result, "/" + encodePath(businessId) + "/owned_whatsapp_business_accounts",
                userToken);
        addWabas(result, "/" + encodePath(businessId) + "/client_whatsapp_business_accounts",
                userToken);
        Map<String, Waba> unique = new LinkedHashMap<>();
        for (Waba waba : result) {
            unique.putIfAbsent(waba.id(), waba);
        }
        return List.copyOf(unique.values());
    }

    public static List<PhoneNumber> phoneNumbers(String wabaId, String userToken)
            throws MetaApiException, IOException, InterruptedException {
        List<PhoneNumber> result = new ArrayList<>();
        for (JsonNode item : graphList("/" + encodePath(wabaId) + "/phone_numbers",
                Map.of("fields", "id,display_phone_number,verified_name"), userToken)) {
            String id = item.path("id").asText("");
            if (!id.isBlank()) {
                result.add(new PhoneNumber(id, nullableText(item, "display_phone_number"),
                        nullableText(item, "verified_name")));
            }
        }
        return result;
    }

    public static List<SystemUserAccess> persistCompletedSetup(long adminId, String businessId, String businessName,
                                             List<Waba> wabas,
                                             Map<String, List<PhoneNumber>> phonesByWaba,
                                             String userToken)
            throws MetaApiException, IOException, InterruptedException, SQLException {
        AppConfig config = requiredConfig();
        List<SystemUser> users = systemUsers(businessId, userToken);
        SystemUser admin = users.stream().filter(SystemUser::admin).findFirst().orElse(null);
        if (admin == null) {
            admin = createAdminSystemUser(businessId, userToken);
            users.add(admin);
        }

        Map<String, Map<String, Set<String>>> assignmentsByWaba = new LinkedHashMap<>();
        for (Waba waba : wabas) {
            assignmentsByWaba.put(waba.id(), wabaAssignments(waba.id(), businessId, userToken));
        }
        List<SystemUserAccess> access = new ArrayList<>();
        List<PersistedSystemUser> prepared = new ArrayList<>();
        for (SystemUser user : users) {
            boolean isSelectedAdmin = user.id().equals(admin.id());
            SystemUser normalizedUser = new SystemUser(user.id(), user.name(), isSelectedAdmin);
            Set<String> requestedScopes = isSelectedAdmin
                    ? ADMIN_SYSTEM_USER_SCOPES : EMPLOYEE_SYSTEM_USER_SCOPES;
            Map<String, Set<String>> assignments = new LinkedHashMap<>();
            List<Waba> missing = new ArrayList<>();
            for (Waba waba : wabas) {
                Set<String> tasks = assignmentsByWaba.get(waba.id()).get(user.id());
                if (tasks == null || (!tasks.contains("MANAGE") && !tasks.contains("DEVELOP"))) {
                    missing.add(waba);
                } else {
                    assignments.put(waba.id(), tasks);
                }
            }
            access.add(new SystemUserAccess(user.id(), user.name(), user.admin(), List.copyOf(missing)));
            // Keep the existing single-admin token flow; report every discovered user.
            if ((user.admin() && !isSelectedAdmin) || (!user.admin() && assignments.isEmpty())) {
                continue;
            }

            Token generated = generateSystemUserToken(user.id(), config.appId(), userToken,
                    requestedScopes, config);
            prepared.add(new PersistedSystemUser(normalizedUser, generated, assignments));
        }

        persistDatabaseSetup(adminId, businessId, businessName, wabas, phonesByWaba, prepared);
        return List.copyOf(access);
    }

    public static MessagingCredentials messagingCredentials()
            throws SQLException, IOException {
        return credentialsForPermission(WHATSAPP_MESSAGING);
    }

    public static MessagingCredentials credentialsForPermission(String permission)
            throws SQLException, IOException {
        String sql = """
            SELECT t.encrypted_access_token, p.phone_number_id
            FROM meta_system_user su
            JOIN meta_system_user_token t ON t.system_user_id = su.system_user_id
            JOIN meta_system_user_token_permission pm
              ON pm.token_id = t.token_id
             AND pm.permission = ?
            JOIN meta_system_user_waba_assignment a
              ON a.system_user_id = su.system_user_id
            JOIN meta_phone_number p
              ON p.waba_id = a.waba_id AND p.is_active = TRUE
            WHERE su.role = 'EMPLOYEE'
              AND su.is_active = TRUE
              AND t.status = 'ACTIVE'
              AND (t.expires_at IS NULL OR t.expires_at > CURRENT_TIMESTAMP)
              AND a.meta_task IN ('MANAGE', 'DEVELOP')
            ORDER BY random()
            LIMIT 1
            """;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, permission);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("No active employee system-user token is assigned "
                            + permission + " access to an active phone number.");
                }
                return new MessagingCredentials(
                        MetaTokenCrypto.decrypt(result.getString("encrypted_access_token")),
                        result.getString("phone_number_id"));
            }
        }
    }

    public static List<MessageTemplate> messageTemplates()
            throws SQLException, IOException, InterruptedException, MetaApiException {
        TemplateCredentials credentials = templateCredentials();
        return messageTemplates(credentials);
    }

    private static List<MessageTemplate> messageTemplates(TemplateCredentials credentials)
            throws IOException, InterruptedException, MetaApiException {
        List<MessageTemplate> templates = new ArrayList<>();
        for (JsonNode item : graphList("/" + encodePath(credentials.wabaId())
                        + "/message_templates",
                Map.of("fields", "id,name,category,language,status,components,quality_score,"
                        + "rejected_reason,parameter_format,previous_category,correct_category,"
                        + "sub_category,last_updated_time", "limit", "100"),
                credentials.accessToken())) {
            templates.add(messageTemplate(item));
        }
        return List.copyOf(templates);
    }

    public static MessageTemplate messageTemplate(String templateId)
            throws SQLException, IOException, InterruptedException, MetaApiException {
        validateIdentifier(templateId, "template ID");
        TemplateCredentials credentials = templateCredentials();
        return findTemplate(messageTemplates(credentials), templateId);
    }

    public static void createMessageTemplate(String name, String category, String language,
                                             String parameterFormat, String componentsJson)
            throws SQLException, IOException, InterruptedException, MetaApiException {
        validateTemplateName(name);
        validateTemplateCategory(category);
        validateTemplateLanguage(language);
        JsonNode components = validateTemplateComponents(componentsJson);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("category", category);
        payload.put("language", language);
        payload.put("components", components);
        if (parameterFormat != null && !parameterFormat.isBlank()) {
            if (!Set.of("POSITIONAL", "NAMED").contains(parameterFormat)) {
                throw new MetaApiException("Choose a valid template parameter format.");
            }
            payload.put("parameter_format", parameterFormat);
        }
        TemplateCredentials credentials = templateCredentials();
        graphPostJson("/" + encodePath(credentials.wabaId()) + "/message_templates",
                JSON.writeValueAsString(payload), credentials.accessToken());
    }

    public static void updateMessageTemplate(String templateId, String category,
                                             String parameterFormat,
                                             boolean allowCategoryChange, String componentsJson)
            throws SQLException, IOException, InterruptedException, MetaApiException {
        validateIdentifier(templateId, "template ID");
        if (category != null && !category.isBlank()) {
            validateTemplateCategory(category);
        }
        JsonNode components = validateTemplateComponents(componentsJson);
        TemplateCredentials credentials = templateCredentials();
        MessageTemplate current = findTemplate(messageTemplates(credentials), templateId);
        if (!Set.of("APPROVED", "REJECTED").contains(current.status().toUpperCase())) {
            throw new MetaApiException("Only approved or rejected templates can be edited.");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("components", components);
        if (parameterFormat != null && !parameterFormat.isBlank()) {
            if (!Set.of("POSITIONAL", "NAMED").contains(parameterFormat)) {
                throw new MetaApiException("Choose a valid template parameter format.");
            }
            payload.put("parameter_format", parameterFormat);
        }
        if (category != null && !category.isBlank()) {
            payload.put("category", category);
            payload.put("allow_category_change", allowCategoryChange);
        }
        graphPostJson("/" + encodePath(templateId), JSON.writeValueAsString(payload),
                credentials.accessToken());
    }

    public static void deleteMessageTemplate(String templateId, String name)
            throws SQLException, IOException, InterruptedException, MetaApiException {
        validateIdentifier(templateId, "template ID");
        validateTemplateName(name);
        TemplateCredentials credentials = templateCredentials();
        MessageTemplate template = findTemplate(messageTemplates(credentials), templateId);
        if (!template.name().equals(name)) {
            throw new MetaApiException("The selected template no longer matches the current "
                    + "WhatsApp Business Account. Refresh the template list and try again.");
        }
        graphDelete("/" + encodePath(credentials.wabaId()) + "/message_templates",
                Map.of("name", name, "hsm_id", templateId), credentials.accessToken());
    }

    public static String uploadTemplateMedia(InputStream media, long contentLength,
                                             String contentType)
            throws SQLException, IOException, InterruptedException, MetaApiException {
        if (media == null || contentType == null) {
            throw new MetaApiException("Select a media file to upload.");
        }
        if (!Set.of("image/jpeg", "image/png").contains(contentType.toLowerCase())) {
            throw new MetaApiException("Template header image examples support JPEG and PNG only.");
        }
        long maxBytes = 5L * 1024 * 1024;
        if (contentLength < 1 || contentLength > maxBytes) {
            throw new MetaApiException("The selected file is empty or exceeds the "
                    + (maxBytes / (1024 * 1024)) + " MB limit for " + contentType + ".");
        }
        TemplateCredentials credentials = templateCredentials();
        AppConfig config = requiredConfig();
        URI sessionUri = URI.create(GRAPH_BASE + "/" + encodePath(config.appId())
                + "/uploads" + queryString(Map.of("file_length",
                Long.toString(contentLength), "file_type", contentType)));
        HttpRequest sessionRequest = HttpRequest.newBuilder(sessionUri)
                .header("Authorization", "Bearer " + credentials.accessToken())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        JsonNode session = send(sessionRequest);
        String uploadId = requiredText(session, "id");
        URI uploadUri = URI.create(GRAPH_BASE + "/" + encodePath(uploadId));
        HttpRequest.BodyPublisher stream = HttpRequest.BodyPublishers.fromPublisher(
                HttpRequest.BodyPublishers.ofInputStream(() -> media), contentLength);
        HttpRequest uploadRequest = HttpRequest.newBuilder(uploadUri)
                .header("Authorization", "Bearer " + credentials.accessToken())
                .header("file_offset", "0")
                .header("Content-Type", "application/octet-stream")
                .POST(stream)
                .build();
        return requiredText(send(uploadRequest), "h");
    }

    private static MessageTemplate messageTemplate(JsonNode item) throws MetaApiException {
        String id = requiredText(item, "id");
        return new MessageTemplate(id,
                item.path("name").asText(""),
                item.path("category").asText(""),
                item.path("language").asText(""),
                item.path("status").asText(""),
                item.path("components"),
                item.deepCopy());
    }

    private static MessageTemplate findTemplate(List<MessageTemplate> templates, String templateId)
            throws MetaApiException {
        return templates.stream()
                .filter(template -> template.id().equals(templateId))
                .findFirst()
                .orElseThrow(() -> new MetaApiException("The selected template is not available "
                        + "in the connected WhatsApp Business Account."));
    }

    private static TemplateCredentials templateCredentials()
            throws SQLException, IOException {
        String sql = """
            SELECT a.waba_id, t.encrypted_access_token
            FROM meta_system_user su
            JOIN meta_system_user_token t ON t.system_user_id = su.system_user_id
            JOIN meta_system_user_token_permission pm
              ON pm.token_id = t.token_id
             AND pm.permission = ?
            JOIN meta_system_user_waba_assignment a
              ON a.system_user_id = su.system_user_id
             AND a.meta_task IN ('MANAGE', 'DEVELOP')
            JOIN meta_waba w
              ON w.waba_id = a.waba_id AND w.is_active = TRUE
            WHERE su.role = 'EMPLOYEE'
              AND su.is_active = TRUE
              AND t.status = 'ACTIVE'
              AND (t.expires_at IS NULL OR t.expires_at > CURRENT_TIMESTAMP)
            ORDER BY random()
            LIMIT 1
            """;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, WHATSAPP_MANAGEMENT);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("No active employee system-user token has "
                            + "WhatsApp management access to the connected WABA.");
                }
                return new TemplateCredentials(result.getString("waba_id"),
                        MetaTokenCrypto.decrypt(result.getString("encrypted_access_token")));
            }
        }
    }

    private static void validateTemplateName(String name) throws MetaApiException {
        if (name == null || !name.matches("[a-z0-9_]{1,512}")) {
            throw new MetaApiException("Template names must contain only lowercase letters, "
                    + "numbers, and underscores (maximum 512 characters).");
        }
    }

    private static void validateTemplateCategory(String category) throws MetaApiException {
        if (category == null || !Set.of("AUTHENTICATION", "MARKETING", "UTILITY")
                .contains(category)) {
            throw new MetaApiException("Choose Authentication, Marketing, or Utility "
                    + "as the template category.");
        }
    }

    private static void validateTemplateLanguage(String language) throws MetaApiException {
        if (language == null
                || !language.matches("[a-zA-Z]{2,3}(?:_[a-zA-Z0-9]{2,8})?")) {
            throw new MetaApiException("Enter a valid Meta language code, for example en_US.");
        }
    }

    private static JsonNode validateTemplateComponents(String componentsJson)
            throws MetaApiException {
        if (componentsJson == null || componentsJson.length() > 65536) {
            throw new MetaApiException("Template components are required and must be under 64 KB.");
        }
        try {
            JsonNode components = JSON.readTree(componentsJson);
            if (components == null || !components.isArray() || components.isEmpty()) {
                throw new MetaApiException("Template components must be a non-empty JSON array.");
            }
            for (JsonNode component : components) {
                if (!component.isObject() || component.path("type").asText("").isBlank()) {
                    throw new MetaApiException("Each template component must be an object "
                            + "with a type.");
                }
            }
            return components;
        } catch (IOException exception) {
            throw new MetaApiException("Template components must be valid JSON.", exception);
        }
    }

    private static void validateIdentifier(String value, String label) throws MetaApiException {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new MetaApiException("The " + label + " is invalid.");
        }
    }

    private record TemplateCredentials(String wabaId, String accessToken) { }

    public record MessagingCredentials(String accessToken, String phoneNumberId) { }

    private static List<SystemUser> systemUsers(String businessId, String userToken)
            throws MetaApiException, IOException, InterruptedException {
        List<SystemUser> users = new ArrayList<>();
        for (JsonNode item : graphList("/" + encodePath(businessId) + "/system_users",
                Map.of("fields", "id,name,role"), userToken)) {
            String id = item.path("id").asText("");
            if (id.isBlank()) {
                continue;
            }
            String role = item.path("role").asText("");
            if ("ADMIN".equalsIgnoreCase(role) || "EMPLOYEE".equalsIgnoreCase(role)) {
                users.add(new SystemUser(id, item.path("name").asText("WhatsApp System User"),
                        "ADMIN".equalsIgnoreCase(role)));
            }
        }
        return users;
    }

    private static SystemUser createAdminSystemUser(String businessId, String userToken)
            throws MetaApiException, IOException, InterruptedException {
        JsonNode created = graphPost("/" + encodePath(businessId) + "/system_users",
                Map.of("name", "Parking System Admin", "role", "ADMIN"), userToken);
        return new SystemUser(requiredText(created, "id"),
                created.path("name").asText("Parking System Admin"), true);
    }

    private static Map<String, Set<String>> wabaAssignments(String wabaId, String businessId,
                                                           String userToken)
            throws MetaApiException, IOException, InterruptedException {
        Map<String, Set<String>> assignments = new LinkedHashMap<>();
        for (JsonNode item : graphList("/" + encodePath(wabaId) + "/assigned_users",
                Map.of("business", businessId, "fields", "id,tasks"), userToken)) {
            String id = requiredText(item, "id");
            Set<String> tasks = assignments.computeIfAbsent(id, ignored -> new LinkedHashSet<>());
            for (JsonNode task : item.path("tasks")) {
                tasks.add(task.asText());
            }
        }
        return assignments;
    }

    private static Token generateSystemUserToken(String systemUserId, String appId,
                                                 String userToken,
                                                 Set<String> requestedScopes, AppConfig config)
            throws MetaApiException, IOException, InterruptedException {
        String requested = String.join(",", requestedScopes);
        String proof = appSecretProof(userToken,
                MetaTokenCrypto.decrypt(config.encryptedAppSecret()));
        JsonNode response = graphPost("/" + encodePath(systemUserId) + "/access_tokens",
                Map.of("business_app", appId, "scope", requested,
                        "appsecret_proof", proof), userToken);
        String value = requiredText(response, "access_token");
        Set<String> granted = debugToken(value, config);
        Set<String> missing = new LinkedHashSet<>(requestedScopes);
        missing.removeAll(granted);
        if (!missing.isEmpty()) {
            throw new MetaApiException("Meta could not grant "
                    + String.join(", ", missing) + " to system user " + systemUserId
                    + ". Check that the system user and WhatsApp assets have the required access.");
        }
        long expiresIn = response.path("expires_in").asLong(0);
        long expiresAtEpoch = response.path("expires_at").asLong(0);
        Instant expiresAt = expiresAtEpoch > 0
                ? Instant.ofEpochSecond(expiresAtEpoch)
                : expiresIn > 0 ? Instant.now().plusSeconds(expiresIn) : null;
        return new Token(value, expiresAt, granted);
    }

    private static String appSecretProof(String accessToken, String appSecret) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(hmac.doFinal(accessToken.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to calculate Meta app secret proof.", exception);
        }
    }

    private static Set<String> debugToken(String token, AppConfig config)
            throws MetaApiException, IOException, InterruptedException {
        String secret = MetaTokenCrypto.decrypt(config.encryptedAppSecret());
        String appToken = config.appId() + "|" + secret;
        JsonNode response = graphGet("/debug_token",
                Map.of("input_token", token), appToken);
        JsonNode data = response.path("data");
        if (!data.path("is_valid").asBoolean(false)
                || !config.appId().equals(data.path("app_id").asText())) {
            throw new MetaApiException("Meta returned an invalid access token for this app.");
        }
        Set<String> scopes = new LinkedHashSet<>();
        JsonNode scopesNode = data.path("scopes");
        if (scopesNode.isArray()) {
            scopesNode.forEach(scope -> scopes.add(scope.asText()));
        }
        return scopes;
    }

    private static void addWabas(List<Waba> target, String path, String userToken)
            throws MetaApiException, IOException, InterruptedException {
        for (JsonNode item : graphList(path, Map.of("fields", "id,name"), userToken)) {
            String id = item.path("id").asText("");
            if (!id.isBlank()) {
                target.add(new Waba(id, item.path("name").asText(id)));
            }
        }
    }

    private static void persistDatabaseSetup(long adminId, String businessId, String businessName,
                                             List<Waba> wabas,
                                             Map<String, List<PhoneNumber>> phonesByWaba,
                                             List<PersistedSystemUser> users)
            throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM meta_waba WHERE business_id <> ?")) {
                    statement.setString(1, businessId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE meta_app_config
                    SET business_id = ?, business_name = ?, configured_by_user_id = ?,
                        connected_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                    WHERE config_id = 1
                    """)) {
                    statement.setString(1, businessId);
                    statement.setString(2, businessName);
                    statement.setLong(3, adminId);
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("Meta app credentials are not configured.");
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE meta_waba SET is_active = FALSE, updated_at = CURRENT_TIMESTAMP")) {
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE meta_phone_number SET is_active = FALSE, updated_at = CURRENT_TIMESTAMP")) {
                    statement.executeUpdate();
                }
                for (Waba waba : wabas) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO meta_waba
                            (waba_id, business_id, waba_name, is_active, created_at, updated_at)
                        VALUES (?, ?, ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        ON CONFLICT (waba_id) DO UPDATE SET
                            business_id = EXCLUDED.business_id,
                            waba_name = EXCLUDED.waba_name,
                            is_active = TRUE,
                            updated_at = CURRENT_TIMESTAMP
                        """)) {
                        statement.setString(1, waba.id());
                        statement.setString(2, businessId);
                        statement.setString(3, waba.name());
                        statement.executeUpdate();
                    }
                    for (PhoneNumber phone : phonesByWaba.getOrDefault(waba.id(), List.of())) {
                        try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO meta_phone_number
                                (phone_number_id, waba_id, display_phone_number,
                                 verified_name, is_active, created_at, updated_at)
                            VALUES (?, ?, ?, ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                            ON CONFLICT (phone_number_id) DO UPDATE SET
                                waba_id = EXCLUDED.waba_id,
                                display_phone_number = EXCLUDED.display_phone_number,
                                verified_name = EXCLUDED.verified_name,
                                is_active = TRUE,
                                updated_at = CURRENT_TIMESTAMP
                            """)) {
                            statement.setString(1, phone.id());
                            statement.setString(2, waba.id());
                            statement.setString(3, phone.displayNumber());
                            statement.setString(4, phone.verifiedName());
                            statement.executeUpdate();
                        }
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM meta_system_user_waba_assignment")) {
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE meta_system_user SET is_active = FALSE, updated_at = CURRENT_TIMESTAMP")) {
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE meta_system_user_token
                    SET status = 'REVOKED', updated_at = CURRENT_TIMESTAMP
                    WHERE status = 'ACTIVE'
                    """)) {
                    statement.executeUpdate();
                }
                for (PersistedSystemUser item : users) {
                    long systemUserId = upsertSystemUser(connection, item.user());
                    long tokenId = insertToken(connection, systemUserId, item.token());
                    insertPermissions(connection, tokenId, item.token().scopes());
                    if (item.user().admin()) {
                        continue;
                    }
                    for (Map.Entry<String, Set<String>> assignment : item.assignments().entrySet()) {
                        try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO meta_system_user_waba_assignment
                                (system_user_id, waba_id, meta_task, assigned_at)
                            VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                            ON CONFLICT DO NOTHING
                            """)) {
                            for (String task : assignment.getValue()) {
                                if (!"MANAGE".equals(task) && !"DEVELOP".equals(task)) {
                                    continue;
                                }
                                statement.setLong(1, systemUserId);
                                statement.setString(2, assignment.getKey());
                                statement.setString(3, task);
                                statement.addBatch();
                            }
                            statement.executeBatch();
                        }
                    }
                }
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof SQLException sqlException) {
                    throw sqlException;
                }
                throw new SQLException("Unable to save the completed Meta setup.", exception);
            }
        }
    }

    private static long upsertSystemUser(Connection connection, SystemUser user)
            throws SQLException {
        String sql = """
            INSERT INTO meta_system_user
                (meta_system_user_id, system_user_name, role, is_active, created_at, updated_at)
            VALUES (?, ?, ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (meta_system_user_id) DO UPDATE SET
                system_user_name = EXCLUDED.system_user_name,
                role = EXCLUDED.role,
                is_active = TRUE,
                updated_at = CURRENT_TIMESTAMP
            RETURNING system_user_id
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, user.id());
            statement.setString(2, user.name());
            statement.setString(3, user.admin() ? "ADMIN" : "EMPLOYEE");
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Unable to save a Meta system user.");
                }
                return result.getLong(1);
            }
        }
    }

    private static long insertToken(Connection connection, long systemUserId, Token token)
            throws SQLException {
        String sql = """
            INSERT INTO meta_system_user_token
                (system_user_id, encrypted_access_token, expires_at, status,
                 created_at, updated_at)
            VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING token_id
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, systemUserId);
            statement.setString(2, MetaTokenCrypto.encrypt(token.value()));
            if (token.expiresAt() == null) {
                statement.setTimestamp(3, null);
            } else {
                statement.setTimestamp(3, Timestamp.from(token.expiresAt()));
            }
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Unable to save a Meta system-user token.");
                }
                return result.getLong(1);
            }
        }
    }

    private static void insertPermissions(Connection connection, long tokenId,
                                          Set<String> permissions) throws SQLException {
        String sql = """
            INSERT INTO meta_system_user_token_permission (token_id, permission)
            VALUES (?, ?)
            ON CONFLICT DO NOTHING
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (String permission : permissions) {
                statement.setLong(1, tokenId);
                statement.setString(2, permission);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static AppConfig requiredConfig() throws SQLException, MetaApiException {
        AppConfig config = status();
        if (config == null) {
            throw new MetaApiException("Enter and validate the Meta App ID and App Secret first.");
        }
        return config;
    }

    private static JsonNode graphGet(String path, Map<String, String> parameters, String token)
            throws MetaApiException, IOException, InterruptedException {
        Map<String, String> query = new LinkedHashMap<>(parameters);
        String url = GRAPH_BASE + path + queryString(query);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return send(request.build());
    }

    private static JsonNode graphPost(String path, Map<String, String> parameters, String token)
            throws MetaApiException, IOException, InterruptedException {
        Map<String, String> body = new LinkedHashMap<>(parameters);
        String encoded = queryString(body).substring(1);
        HttpRequest request = HttpRequest.newBuilder(URI.create(GRAPH_BASE + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encoded, StandardCharsets.UTF_8))
                .build();
        return send(request);
    }

    private static JsonNode graphPostJson(String path, String json, String token)
            throws MetaApiException, IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(GRAPH_BASE + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return send(request);
    }

    private static JsonNode graphDelete(String path, Map<String, String> parameters, String token)
            throws MetaApiException, IOException, InterruptedException {
        URI uri = URI.create(GRAPH_BASE + path + queryString(parameters));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + token)
                .DELETE()
                .build();
        return send(request);
    }

    private static List<JsonNode> graphList(String path, Map<String, String> parameters,
                                            String token)
            throws MetaApiException, IOException, InterruptedException {
        List<JsonNode> result = new ArrayList<>();
        JsonNode response = graphGet(path, parameters, token);
        while (true) {
            JsonNode data = response.path("data");
            if (!data.isArray()) {
                throw new MetaApiException("Meta returned an unexpected list response.");
            }
            data.forEach(result::add);
            String next = response.path("paging").path("next").asText("");
            if (next.isBlank()) {
                return result;
            }
            URI nextUri = URI.create(next);
            if (!GRAPH_HOST.equalsIgnoreCase(nextUri.getHost())
                    || !"https".equalsIgnoreCase(nextUri.getScheme())) {
                throw new MetaApiException("Meta returned an invalid pagination URL.");
            }
            String query = nextUri.getRawQuery();
            if (query != null) {
                query = java.util.Arrays.stream(query.split("&"))
                        .filter(part -> !part.regionMatches(true, 0, "access_token=", 0, 13))
                        .filter(part -> !part.regionMatches(true, 0, "access_token%3D", 0, 15))
                        .collect(java.util.stream.Collectors.joining("&"));
            }
            URI safeNext = URI.create(nextUri.getScheme() + "://" + nextUri.getRawAuthority()
                    + nextUri.getRawPath() + (query == null || query.isBlank() ? "" : "?" + query));
            response = send(HttpRequest.newBuilder(safeNext)
                    .header("Authorization", "Bearer " + token)
                    .GET().build());
        }
    }

    private static JsonNode send(HttpRequest request)
            throws MetaApiException, IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode body;
        try {
            body = JSON.readTree(response.body());
        } catch (IOException exception) {
            throw new MetaApiException("Meta returned an unreadable response (HTTP "
                    + response.statusCode() + ").", exception);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300 || body.has("error")) {
            JsonNode error = body.path("error");
            StringBuilder detail = new StringBuilder();
            detail.append(error.path("message").asText("Meta API request failed."));
            if (error.has("error_subcode")) {
                detail.append(" | subcode: ").append(error.path("error_subcode").asText());
            }
            if (error.has("error_user_title")) {
                detail.append(" | title: ").append(error.path("error_user_title").asText());
            }
            if (error.has("error_user_msg")) {
                detail.append(" | detail: ").append(error.path("error_user_msg").asText());
            }
            if (error.has("fbtrace_id")) {
                detail.append(" | trace: ").append(error.path("fbtrace_id").asText());
            }
            LOGGER.warning("Meta API request failed with HTTP " + response.statusCode()
                    + " (Meta error " + error.path("code").asText("unknown") + ").");
            throw new MetaApiException(detail.toString(), error.path("code").asText(""));
        }
        return body;
    }

    private static String requiredText(JsonNode node, String field) throws MetaApiException {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new MetaApiException("Meta did not return the required " + field + ".");
        }
        return value;
    }

    private static String nullableText(JsonNode node, String field) {
        String value = node.path(field).asText("");
        return value.isBlank() ? null : value;
    }

    private static String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String queryString(Map<String, String> parameters) {
        StringBuilder query = new StringBuilder("?");
        parameters.forEach((key, value) -> {
            if (query.length() > 1) {
                query.append('&');
            }
            query.append(encode(key)).append('=').append(encode(value));
        });
        return query.toString();
    }

    private static String setting(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public static final class MetaApiException extends Exception {
        public MetaApiException(String message) {
            this(message, "");
        }

        public MetaApiException(String message, Throwable cause) {
            super(message, cause);
        }

        public MetaApiException(String message, String errorCode) {
            super(errorCode == null || errorCode.isBlank()
                    ? message : message + " (Meta error " + errorCode + ").");
        }

    }
}
