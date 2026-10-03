package org.example.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.example.config.DatabaseConnection;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
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

    private static final Logger LOGGER = Logger.getLogger(GmailEmailService.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CLIENT_ID = setting("GOOGLE_CLIENT_ID");
    private static final String CLIENT_SECRET = setting("GOOGLE_CLIENT_SECRET");
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String USERINFO_ENDPOINT =
            "https://openidconnect.googleapis.com/v1/userinfo";
    private static final String SEND_ENDPOINT =
            "https://gmail.googleapis.com/gmail/v1/users/me/messages/send";
    private static final String TOKEN_KEY_ENV = "GMAIL_TOKEN_ENCRYPTION_KEY";
    private static final int GCM_IV_BYTES = 12;
    private static final int ACCESS_TOKEN_REFRESH_SKEW_SECONDS = 60;

    private GmailEmailService() {
    }

    public record Authorization(String senderEmail, String refreshToken,
                                String accessToken, Instant accessTokenExpiresAt,
                                String scopes) { }

    public static String authorizationUrl(String redirectUri, String state)
            throws EmailSetupException {
        requireGoogleClient();
        return "https://accounts.google.com/o/oauth2/v2/auth"
                + "?client_id=" + encode(CLIENT_ID)
                + "&redirect_uri=" + encode(redirectUri)
                + "&response_type=code"
                + "&scope=" + encode("openid email " + SEND_SCOPE)
                + "&access_type=offline"
                + "&prompt=" + encode("consent select_account")
                + "&include_granted_scopes=true"
                + "&state=" + encode(state);
    }

    public static void validateSetupRequirements() throws EmailSetupException {
        requireGoogleClient();
        try {
            encryptionKey();
        } catch (IllegalStateException exception) {
            throw new EmailSetupException(exception.getMessage(), exception);
        }
    }

    public static Authorization exchangeAndVerify(String code, String redirectUri)
            throws IOException, InterruptedException, EmailSetupException {
        requireGoogleClient();
        JsonNode tokenResponse = postForm(TOKEN_ENDPOINT, form(
                "code", code,
                "client_id", CLIENT_ID,
                "client_secret", CLIENT_SECRET,
                "redirect_uri", redirectUri,
                "grant_type", "authorization_code"));
        String accessToken = requiredText(tokenResponse, "access_token");
        String refreshToken = requiredText(tokenResponse, "refresh_token");
        String scopes = tokenResponse.path("scope").asText("openid email " + SEND_SCOPE);
        if (!hasSendScope(scopes)) {
            throw new EmailSetupException(
                    "Google did not grant the Gmail send permission. Check the OAuth consent-screen scopes and authorize again.");
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
                "This test confirms that the parking system can send forgot-password emails from this account.");

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
            statement.setString(2, encrypt(authorization.refreshToken()));
            statement.setString(3, encrypt(authorization.accessToken()));
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
                    toEmail, subject, body);
        } catch (EmailSetupException exception) {
            throw exception;
        } catch (Exception exception) {
            LOGGER.log(Level.WARNING, "Unable to send password-reset email via Gmail.", exception);
            throw new EmailSetupException("Unable to send the password-reset email.", exception);
        }
    }

    private static AccessCredentials currentAccessToken()
            throws SQLException, IOException, InterruptedException, EmailSetupException {
        requireGoogleClient();
        String sql = """
            SELECT sender_email, encrypted_refresh_token, encrypted_access_token, access_token_expires_at
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
                String refreshToken = decrypt(result.getString("encrypted_refresh_token"));
                String encryptedAccessToken = result.getString("encrypted_access_token");
                Timestamp expiresAt = result.getTimestamp("access_token_expires_at");
                if (encryptedAccessToken != null && expiresAt != null
                        && expiresAt.toInstant().isAfter(
                        Instant.now().plusSeconds(ACCESS_TOKEN_REFRESH_SKEW_SECONDS))) {
                    String token = decrypt(encryptedAccessToken);
                    connection.commit();
                    return new AccessCredentials(senderEmail, token);
                }

                JsonNode tokenResponse = postForm(TOKEN_ENDPOINT, form(
                        "client_id", CLIENT_ID,
                        "client_secret", CLIENT_SECRET,
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
                    update.setString(1, encrypt(accessToken));
                    update.setTimestamp(2, Timestamp.from(expiry));
                    update.executeUpdate();
                }
                connection.commit();
                return new AccessCredentials(senderEmail, accessToken);
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

    private record AccessCredentials(String senderEmail, String accessToken) { }

    private static void sendGmailMessage(
            String accessToken, String senderEmail, String toEmail, String subject, String body)
            throws IOException, EmailSetupException {
        try {
            MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
            message.setFrom(new InternetAddress(senderEmail, true));
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

    private static String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] result = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt Gmail authorization.", exception);
        }
    }

    private static String decrypt(String ciphertext) {
        try {
            byte[] packed = Base64.getDecoder().decode(ciphertext);
            if (packed.length <= GCM_IV_BYTES) {
                throw new GeneralSecurityException("Encrypted token is malformed.");
            }
            byte[] iv = java.util.Arrays.copyOfRange(packed, 0, GCM_IV_BYTES);
            byte[] encrypted = java.util.Arrays.copyOfRange(packed, GCM_IV_BYTES, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Unable to decrypt Gmail authorization. Verify GMAIL_TOKEN_ENCRYPTION_KEY is unchanged.",
                    exception);
        }
    }

    private static SecretKeySpec encryptionKey() {
        String encoded = setting(TOKEN_KEY_ENV);
        if (encoded == null) {
            throw new IllegalStateException(TOKEN_KEY_ENV
                    + " must contain a Base64-encoded 32-byte key.");
        }
        try {
            byte[] key = Base64.getDecoder().decode(encoded);
            if (key.length != 32) {
                throw new IllegalArgumentException("Key must be 32 bytes.");
            }
            return new SecretKeySpec(key, "AES");
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(TOKEN_KEY_ENV
                    + " must contain a Base64-encoded 32-byte key.", exception);
        }
    }

    private static void requireGoogleClient() throws EmailSetupException {
        if (CLIENT_ID == null || CLIENT_ID.isBlank()
                || CLIENT_SECRET == null || CLIENT_SECRET.isBlank()) {
            throw new EmailSetupException(
                    "Google OAuth is not configured. Set GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET.");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String setting(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value.trim();
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
