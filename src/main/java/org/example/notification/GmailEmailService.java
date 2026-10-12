package org.example.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.example.config.DatabaseConnection;
import org.example.config.SecretEncryption;

import java.io.ByteArrayOutputStream;
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
import java.util.Base64;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class GmailEmailService {
    public static final String SEND_SCOPE = "https://www.googleapis.com/auth/gmail.send";
    public static final String METADATA_SCOPE = "https://www.googleapis.com/auth/gmail.metadata";

    private static final Logger LOGGER = Logger.getLogger(GmailEmailService.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10)).build();
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String USERINFO_ENDPOINT =
            "https://openidconnect.googleapis.com/v1/userinfo";
    private static final String SEND_ENDPOINT =
            "https://gmail.googleapis.com/gmail/v1/users/me/messages/send";
    private static final int ACCESS_TOKEN_REFRESH_SKEW_SECONDS = 60;

    private GmailEmailService() {
    }

    public record Authorization(String senderEmail, String refreshToken,
                                String accessToken, Instant accessTokenExpiresAt,
                                String scopes) { }

    public static String authorizationUrl(String redirectUri, String state)
            throws EmailSetupException, SQLException {
        GoogleOAuthConfig.Client client = requireGoogleClient();
        return "https://accounts.google.com/o/oauth2/v2/auth"
                + "?client_id=" + encode(client.clientId())
                + "&redirect_uri=" + encode(redirectUri)
                + "&response_type=code"
                + "&scope=" + encode("openid email " + SEND_SCOPE + " " + METADATA_SCOPE)
                + "&access_type=offline"
                + "&prompt=" + encode("consent select_account")
                + "&include_granted_scopes=true"
                + "&state=" + encode(state);
    }

    public static void validateSetupRequirements() throws EmailSetupException, SQLException {
        requireGoogleClient();
    }

    public static Authorization exchangeAndVerify(String code, String redirectUri)
            throws IOException, InterruptedException, EmailSetupException, SQLException {
        GoogleOAuthConfig.Client client = requireGoogleClient();
        JsonNode tokenResponse = postForm(TOKEN_ENDPOINT, form(
                "code", code,
                "client_id", client.clientId(),
                "client_secret", client.clientSecret(),
                "redirect_uri", redirectUri,
                "grant_type", "authorization_code"));
        String accessToken = requiredText(tokenResponse, "access_token");
        String refreshToken = requiredText(tokenResponse, "refresh_token");
        String scopes = tokenResponse.path("scope").asText("");
        if (!hasSendScope(scopes)) {
            throw new EmailSetupException(
                    "Google did not grant the Gmail send permission. Check the OAuth consent-screen scopes and authorize again.");
        }
        if (!java.util.Arrays.asList(scopes.split("\\s+")).contains(METADATA_SCOPE)) {
            throw new EmailSetupException("Grant Gmail metadata permission to enable automatic no-reply responses, then reconnect.");
        }

        long expiresIn = tokenResponse.path("expires_in").asLong(0);
        if (expiresIn <= 0) {
            throw new EmailSetupException("Google returned an invalid access-token lifetime.");
        }

        HttpRequest userInfoRequest = HttpRequest.newBuilder()
                .uri(URI.create(USERINFO_ENDPOINT))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        HttpResponse<String> userInfoResponse =
                HTTP.send(userInfoRequest, HttpResponse.BodyHandlers.ofString());
        if (userInfoResponse.statusCode() < 200 || userInfoResponse.statusCode() >= 300) {
            throw new EmailSetupException(
                    "Google authorization succeeded, but the selected sender account could not be verified (HTTP "
                            + userInfoResponse.statusCode() + ").");
        }

        JsonNode userInfo = JSON.readTree(userInfoResponse.body());
        String senderEmail = userInfo.path("email").asText("");
        if (senderEmail.isBlank() || !userInfo.path("email_verified").asBoolean(false)) {
            throw new EmailSetupException("Google did not return a verified sender email address.");
        }
        sendGmailMessage(accessToken, senderEmail, senderEmail,
                "Gmail sender setup test",
                "This test confirms that the parking system can send forgot-password emails from this account.",
                null);

        return new Authorization(senderEmail, refreshToken, accessToken,
                Instant.now().plusSeconds(expiresIn), scopes);
    }

    public static void saveAuthorization(Authorization authorization, long adminUserId)
            throws SQLException {
        String sql = """
            INSERT INTO email_sender_config
                (config_id, sender_email, encrypted_refresh_token, encrypted_access_token,
                 access_token_expires_at, granted_scopes, configured_by_user_id, created_at, updated_at)
            VALUES (1, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (config_id) DO UPDATE SET
                auto_reply_started_at = CASE WHEN email_sender_config.sender_email = EXCLUDED.sender_email
                    THEN email_sender_config.auto_reply_started_at ELSE CURRENT_TIMESTAMP END,
                auto_reply_page_token = NULL,
                sender_email = EXCLUDED.sender_email,
                encrypted_refresh_token = EXCLUDED.encrypted_refresh_token,
                encrypted_access_token = EXCLUDED.encrypted_access_token,
                access_token_expires_at = EXCLUDED.access_token_expires_at,
                granted_scopes = EXCLUDED.granted_scopes,
                configured_by_user_id = EXCLUDED.configured_by_user_id,
                updated_at = CURRENT_TIMESTAMP
            """;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, authorization.senderEmail());
            statement.setString(2, SecretEncryption.encrypt(authorization.refreshToken()));
            statement.setString(3, SecretEncryption.encrypt(authorization.accessToken()));
            statement.setTimestamp(4, Timestamp.from(authorization.accessTokenExpiresAt()));
            statement.setString(5, authorization.scopes());
            statement.setLong(6, adminUserId);
            statement.executeUpdate();
        }
    }

    public static String validateStoredAuthorization()
            throws SQLException, IOException, InterruptedException, EmailSetupException {
        return currentAccessToken().senderEmail();
    }

    public static String getReplyToEmail() throws SQLException {
        String sql = "SELECT reply_to_email FROM email_sender_config WHERE config_id = 1";
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getString("reply_to_email") : null;
        }
    }

    public static void saveReplyToEmail(String replyToEmail) throws SQLException, EmailSetupException {
        String sql = """
            UPDATE email_sender_config
            SET reply_to_email = ?, updated_at = CURRENT_TIMESTAMP
            WHERE config_id = 1
            """;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, replyToEmail == null || replyToEmail.isBlank()
                    ? null : replyToEmail.trim());
            if (statement.executeUpdate() == 0) {
                throw new EmailSetupException(
                        "Connect a Gmail sender before configuring the Reply-To address.");
            }
        }
    }

    public static void disconnect() throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM email_sender_config WHERE config_id = 1")) {
            statement.executeUpdate();
        }
    }

    public static void sendEmail(String toEmail, String subject, String body)
            throws EmailSetupException {
        try {
            AccessCredentials credentials = currentAccessToken();
            sendGmailMessage(credentials.accessToken(), credentials.senderEmail(),
                    toEmail, subject, body, credentials.replyToEmail());
        } catch (EmailSetupException exception) {
            throw exception;
        } catch (Exception exception) {
            LOGGER.log(Level.WARNING, "Unable to send password-reset email via Gmail.", exception);
            throw new EmailSetupException("Unable to send the password-reset email.", exception);
        }
    }

    private static AccessCredentials currentAccessToken()
            throws SQLException, IOException, InterruptedException, EmailSetupException {
        GoogleOAuthConfig.Client client = requireGoogleClient();
        String sql = """
            SELECT sender_email, reply_to_email, encrypted_refresh_token,
                   encrypted_access_token, access_token_expires_at
            FROM email_sender_config
            WHERE config_id = 1
            FOR UPDATE
            """;
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new EmailSetupException(
                            "Email password reset is not available because an administrator has not connected a Gmail account.");
                }
                String senderEmail = result.getString("sender_email");
                String replyToEmail = result.getString("reply_to_email");
                String refreshToken = SecretEncryption.decrypt(result.getString("encrypted_refresh_token"));
                String encryptedAccessToken = result.getString("encrypted_access_token");
                Timestamp expiresAt = result.getTimestamp("access_token_expires_at");
                if (encryptedAccessToken != null && expiresAt != null
                        && expiresAt.toInstant().isAfter(
                        Instant.now().plusSeconds(ACCESS_TOKEN_REFRESH_SKEW_SECONDS))) {
                    String token = SecretEncryption.decrypt(encryptedAccessToken);
                    connection.commit();
                    return new AccessCredentials(senderEmail, token, replyToEmail);
                }

                JsonNode tokenResponse = postForm(TOKEN_ENDPOINT, form(
                        "client_id", client.clientId(),
                        "client_secret", client.clientSecret(),
                        "refresh_token", refreshToken,
                        "grant_type", "refresh_token"));
                String accessToken = requiredText(tokenResponse, "access_token");
                long expiresIn = tokenResponse.path("expires_in").asLong(0);
                if (expiresIn <= 0) {
                    throw new EmailSetupException("Google returned an invalid refreshed-token lifetime.");
                }
                Instant expiry = Instant.now().plusSeconds(expiresIn);
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE email_sender_config SET encrypted_access_token = ?, "
                                + "access_token_expires_at = ?, updated_at = CURRENT_TIMESTAMP "
                                + "WHERE config_id = 1")) {
                    update.setString(1, SecretEncryption.encrypt(accessToken));
                    update.setTimestamp(2, Timestamp.from(expiry));
                    update.executeUpdate();
                }
                connection.commit();
                return new AccessCredentials(senderEmail, accessToken, replyToEmail);
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof EmailSetupException setupException) {
                    throw setupException;
                }
                if (exception instanceof SQLException sqlException) {
                    throw sqlException;
                }
                if (exception instanceof IOException ioException) {
                    throw ioException;
                }
                if (exception instanceof InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw interruptedException;
                }
                throw new EmailSetupException("Unable to refresh the Gmail authorization.", exception);
            }
        }
    }

    private record AccessCredentials(String senderEmail, String accessToken, String replyToEmail) { }

    static String connectedSender() throws Exception { return currentAccessToken().senderEmail(); }


    static JsonNode inboxMetadata(String expectedSender, String path) throws Exception {
        AccessCredentials credentials = currentAccessToken();
        if (!credentials.senderEmail().equalsIgnoreCase(expectedSender))
            throw new EmailSetupException("Gmail sender changed while reading inbox metadata.");
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://gmail.googleapis.com/gmail/v1/users/me/" + path))
                .timeout(java.time.Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + credentials.accessToken()).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new EmailSetupException("Gmail inbox request failed (HTTP " + response.statusCode() + "). Reconnect with metadata permission if access is denied.");
        return JSON.readTree(response.body());
    }

    static void sendNoReplyNotice(String expectedSender, String recipient, String subject, String messageId) throws Exception {
        AccessCredentials credentials = currentAccessToken();
        if (!credentials.senderEmail().equalsIgnoreCase(expectedSender))
            throw new EmailSetupException("Gmail sender changed while processing a reply.");
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress(credentials.senderEmail(), true));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(recipient, true));
        message.setSubject(subject, StandardCharsets.UTF_8.name());
        message.setText(GmailAutoReplyService.NOTICE, StandardCharsets.UTF_8.name());
        message.setHeader("Auto-Submitted", "auto-replied");
        message.setHeader("X-Auto-Response-Suppress", "All");
        if (messageId != null) {
            message.setHeader("In-Reply-To", messageId);
            message.setHeader("References", messageId);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        message.writeTo(bytes);
        HttpRequest request = HttpRequest.newBuilder(URI.create(SEND_ENDPOINT))
                .timeout(java.time.Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + credentials.accessToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(java.util.Map.of(
                        "raw", Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray()))))).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new EmailSetupException("Gmail automatic response failed (HTTP " + response.statusCode() + ").");
    }

    private static void sendGmailMessage(
            String accessToken, String senderEmail, String toEmail, String subject, String body,
            String replyToEmail)
            throws IOException, EmailSetupException {
        try {
            MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
            message.setFrom(new InternetAddress(senderEmail, true));
            if (replyToEmail != null && !replyToEmail.isBlank()) {
                message.setReplyTo(new Address[] { new InternetAddress(replyToEmail, true) });
            }
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(toEmail, true));
            message.setSubject(subject, StandardCharsets.UTF_8.name());
            message.setText(body, StandardCharsets.UTF_8.name());

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            message.writeTo(bytes);
            String raw = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(bytes.toByteArray());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SEND_ENDPOINT))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            JSON.writeValueAsString(java.util.Map.of("raw", raw))))
                    .build();
            HttpResponse<String> response =
                    HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOGGER.warning("Gmail message send failed with HTTP status "
                        + response.statusCode() + ".");
                throw new EmailSetupException(
                        "Gmail could not send the verification email (HTTP " + response.statusCode()
                                + "). Check that the Gmail API is enabled and the gmail.send permission was granted.");
            }
        } catch (EmailSetupException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Gmail message request was interrupted.", exception);
        } catch (jakarta.mail.MessagingException exception) {
            throw new EmailSetupException("Unable to build the Gmail message.", exception);
        }
    }

    private static JsonNode postForm(String url, String formBody)
            throws IOException, InterruptedException, EmailSetupException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = JSON.readTree(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String error = json.path("error").asText("");
            if ("invalid_grant".equals(error)) {
                throw new EmailSetupException(
                        "Google authorization expired or was revoked. Ask an administrator to reconnect the Gmail account.");
            }
            throw new EmailSetupException(
                    "Google OAuth request failed (HTTP " + response.statusCode()
                            + "). Check the Gmail API, consent-screen scope, and OAuth client configuration.");
        }
        return json;
    }

    private static String form(String... keyValues) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (!body.isEmpty()) {
                body.append('&');
            }
            body.append(encode(keyValues[i])).append('=').append(encode(keyValues[i + 1]));
        }
        return body.toString();
    }

    private static String requiredText(JsonNode node, String field) throws EmailSetupException {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new EmailSetupException("Google did not return the required OAuth credential.");
        }
        return value;
    }

    private static boolean hasSendScope(String scopes) {
        for (String scope : scopes.split("\\s+")) {
            if (SEND_SCOPE.equals(scope)) {
                return true;
            }
        }
        return false;
    }

    private static GoogleOAuthConfig.Client requireGoogleClient()
            throws EmailSetupException, SQLException {
        final GoogleOAuthConfig.Client client;
        try {
            client = GoogleOAuthConfig.load();
        } catch (IllegalStateException exception) {
            throw new EmailSetupException(exception.getMessage(), exception);
        }
        if (client == null) {
            throw new EmailSetupException(
                    "Google OAuth is not configured. An administrator must save the OAuth web client in Email Setup.");
        }
        return client;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public static final class EmailSetupException extends Exception {
        public EmailSetupException(String message) {
            super(message);
        }

        public EmailSetupException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
