package org.example.auth;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

public final class TwoFactorService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String KEY_ENV = "TOTP_ENCRYPTION_KEY";
    private static final int IV_LENGTH = 12;
    private static final int CODE_STEP_SECONDS = 30;
    private static final int LOGIN_TTL_SECONDS = 5 * 60;
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private TwoFactorService() {
    }

    public static boolean isEnabled(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM user_two_factor WHERE user_id = ? AND secret_ciphertext IS NOT NULL")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    public static String beginEnrollment(Connection connection, long userId)
            throws SQLException, GeneralSecurityException {
        String secret = newSecret();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO user_two_factor
                    (user_id, pending_secret_ciphertext, pending_secret_expires_at)
                VALUES (?, ?, CURRENT_TIMESTAMP + INTERVAL '10 minutes')
                ON CONFLICT (user_id) DO UPDATE
                SET pending_secret_ciphertext = EXCLUDED.pending_secret_ciphertext,
                    pending_secret_expires_at = CURRENT_TIMESTAMP + INTERVAL '10 minutes',
                    updated_at = CURRENT_TIMESTAMP
                """)) {
            statement.setLong(1, userId);
            statement.setString(2, encrypt(secret));
            statement.executeUpdate();
        }
        return secret;
    }

    public static List<String> confirmEnrollment(
            Connection connection, long userId, String code)
            throws SQLException, GeneralSecurityException {
        String ciphertext;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pending_secret_ciphertext, pending_secret_expires_at "
                        + "FROM user_two_factor WHERE user_id = ? FOR UPDATE")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getString(1) == null
                        || result.getTimestamp(2) == null
                        || result.getTimestamp(2).toInstant().isBefore(Instant.now())) {
                    return null;
                }
                ciphertext = result.getString(1);
            }
        }

        String secret = decrypt(ciphertext);
        long counter = matchingCounter(secret, code);
        if (counter < 0) {
            return null;
        }
        List<String> recoveryCodes = createRecoveryCodes(connection, userId);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE user_two_factor
                SET secret_ciphertext = pending_secret_ciphertext,
                    pending_secret_ciphertext = NULL,
                    pending_secret_expires_at = NULL,
                    last_totp_counter = ?,
                    failed_login_attempts = 0,
                    login_locked_until = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE user_id = ?
                """)) {
            statement.setLong(1, counter);
            statement.setLong(2, userId);
            statement.executeUpdate();
        }
        return recoveryCodes;
    }

    public static UUID createLoginChallenge(Connection connection, long userId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT login_locked_until FROM user_two_factor
                WHERE user_id = ? AND secret_ciphertext IS NOT NULL
                """)) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                Timestamp lockedUntil = result.getTimestamp(1);
                if (lockedUntil != null && lockedUntil.toInstant().isAfter(Instant.now())) {
                    return null;
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM two_factor_login_challenges WHERE user_id = ?")) {
            statement.setLong(1, userId);
            statement.executeUpdate();
        }
        UUID id = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO two_factor_login_challenges (challenge_id, user_id, expires_at)
                VALUES (?, ?, ?)
                """)) {
            statement.setObject(1, id);
            statement.setLong(2, userId);
            statement.setTimestamp(3, Timestamp.from(
                    Instant.now().plusSeconds(LOGIN_TTL_SECONDS)));
            statement.executeUpdate();
        }
        return id;
    }

    public static int loginTtlSeconds() {
        return LOGIN_TTL_SECONDS;
    }

    public static String otpAuthUri(String secret, String email) {
        return "otpauth://totp/" + urlEncode("MultiParkingSystem:" + email)
                + "?secret=" + secret
                + "&issuer=" + urlEncode("MultiParkingSystem")
                + "&algorithm=SHA1&digits=6&period=30";
    }

    public static boolean verifyLoginCode(Connection connection, UUID challengeId, String code)
            throws SQLException, GeneralSecurityException {
        connection.setAutoCommit(false);
        try {
            long userId;
            int attempts;
            Timestamp expiresAt;
            Timestamp consumedAt;
            Timestamp lockedUntil;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT c.user_id, c.attempts, c.expires_at, c.consumed_at,
                           f.login_locked_until
                    FROM two_factor_login_challenges c
                    JOIN user_two_factor f ON f.user_id = c.user_id
                    WHERE c.challenge_id = ? FOR UPDATE OF c, f
                    """)) {
                statement.setObject(1, challengeId);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        connection.rollback();
                        return false;
                    }
                    userId = result.getLong("user_id");
                    attempts = result.getInt("attempts");
                    expiresAt = result.getTimestamp("expires_at");
                    consumedAt = result.getTimestamp("consumed_at");
                    lockedUntil = result.getTimestamp("login_locked_until");
                }
            }

            if (consumedAt != null || expiresAt.toInstant().isBefore(Instant.now())
                    || attempts >= MAX_LOGIN_ATTEMPTS
                    || (lockedUntil != null && lockedUntil.toInstant().isAfter(Instant.now()))) {
                connection.rollback();
                return false;
            }

            boolean accepted = consumeFactor(connection, userId, code);
            if (accepted) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE user_two_factor
                        SET failed_login_attempts = 0, login_locked_until = NULL
                        WHERE user_id = ?
                        """)) {
                    statement.setLong(1, userId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE two_factor_login_challenges SET consumed_at = CURRENT_TIMESTAMP WHERE challenge_id = ?")) {
                    statement.setObject(1, challengeId);
                    statement.executeUpdate();
                }
                connection.commit();
                return true;
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE two_factor_login_challenges
                    SET attempts = attempts + 1,
                        consumed_at = CASE WHEN attempts + 1 >= ? THEN CURRENT_TIMESTAMP ELSE consumed_at END
                    WHERE challenge_id = ?
                    """)) {
                statement.setInt(1, MAX_LOGIN_ATTEMPTS);
                statement.setObject(2, challengeId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE user_two_factor
                    SET failed_login_attempts = failed_login_attempts + 1,
                        login_locked_until = CASE
                            WHEN failed_login_attempts + 1 >= 10
                            THEN CURRENT_TIMESTAMP + INTERVAL '15 minutes'
                            ELSE login_locked_until
                        END
                    WHERE user_id = ?
                    """)) {
                statement.setLong(1, userId);
                statement.executeUpdate();
            }
            connection.commit();
            return false;
        } catch (SQLException | GeneralSecurityException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public static boolean consumeFactor(Connection connection, long userId, String code)
            throws SQLException, GeneralSecurityException {
        if (code == null || code.length() > 32) {
            return false;
        }

        String secretCiphertext;
        long lastCounter;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT secret_ciphertext, last_totp_counter
                FROM user_two_factor WHERE user_id = ? FOR UPDATE
                """)) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || (secretCiphertext = result.getString(1)) == null) {
                    return false;
                }
                lastCounter = result.getLong(2);
            }
        }

        String codeHash = hashRecoveryCode(code);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE user_two_factor_recovery_codes
                SET used_at = CURRENT_TIMESTAMP
                WHERE user_id = ? AND code_hash = ? AND used_at IS NULL
                """)) {
            statement.setLong(1, userId);
            statement.setString(2, codeHash);
            if (statement.executeUpdate() == 1) {
                return true;
            }
        }

        if (!code.matches("\\d{6}")) {
            return false;
        }
        long counter = matchingCounter(decrypt(secretCiphertext), code);
        if (counter > lastCounter) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE user_two_factor SET last_totp_counter = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE user_id = ?
                    """)) {
                statement.setLong(1, counter);
                statement.setLong(2, userId);
                statement.executeUpdate();
            }
            return true;
        }
        return false;
    }

    public static List<String> replaceRecoveryCodes(Connection connection, long userId)
            throws SQLException, GeneralSecurityException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM user_two_factor_recovery_codes WHERE user_id = ?")) {
            statement.setLong(1, userId);
            statement.executeUpdate();
        }
        return createRecoveryCodes(connection, userId);
    }

    public static void disable(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM user_two_factor WHERE user_id = ?")) {
            statement.setLong(1, userId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM user_two_factor_recovery_codes WHERE user_id = ?")) {
            statement.setLong(1, userId);
            statement.executeUpdate();
        }
    }

    private static List<String> createRecoveryCodes(Connection connection, long userId)
            throws SQLException, GeneralSecurityException {
        List<String> codes = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO user_two_factor_recovery_codes (user_id, code_hash) VALUES (?, ?)
                """)) {
            for (int i = 0; i < 10; i++) {
                String code = newRecoveryCode();
                codes.add(code);
                statement.setLong(1, userId);
                statement.setString(2, hashRecoveryCode(code));
                statement.addBatch();
            }
            statement.executeBatch();
        }
        return codes;
    }

    private static String newRecoveryCode() {
        StringBuilder value = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            value.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length())));
        }
        return value.substring(0, 6) + "-" + value.substring(6);
    }

    private static String hashRecoveryCode(String code) throws GeneralSecurityException {
        String normalized = code.replace("-", "").replace(" ", "")
                .toUpperCase(java.util.Locale.ROOT);
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(normalized.getBytes(StandardCharsets.UTF_8));
        StringBuilder encoded = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            encoded.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        }
        return encoded.toString();
    }

    private static String newSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return base32Encode(bytes);
    }

    private static long matchingCounter(String secret, String code)
            throws GeneralSecurityException {
        if (code == null || !code.matches("\\d{6}")) {
            return -1;
        }
        long current = Instant.now().getEpochSecond() / CODE_STEP_SECONDS;
        for (long counter = current - 1; counter <= current + 1; counter++) {
            if (MessageDigest.isEqual(
                    hotp(secret, counter).getBytes(StandardCharsets.US_ASCII),
                    code.getBytes(StandardCharsets.US_ASCII))) {
                return counter;
            }
        }
        return -1;
    }

    private static String hotp(String secret, long counter) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(base32Decode(secret), "HmacSHA1"));
        byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        return String.format(java.util.Locale.ROOT, "%06d", binary % 1_000_000);
    }

    private static String encrypt(String plaintext) throws GeneralSecurityException {
        byte[] iv = new byte[IV_LENGTH];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] packed = ByteBuffer.allocate(iv.length + encrypted.length)
                .put(iv).put(encrypted).array();
        return Base64.getEncoder().encodeToString(packed);
    }

    private static String decrypt(String ciphertext) throws GeneralSecurityException {
        byte[] packed = Base64.getDecoder().decode(ciphertext);
        if (packed.length <= IV_LENGTH) {
            throw new GeneralSecurityException("Encrypted TOTP secret is malformed.");
        }
        byte[] iv = java.util.Arrays.copyOfRange(packed, 0, IV_LENGTH);
        byte[] encrypted = java.util.Arrays.copyOfRange(packed, IV_LENGTH, packed.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    private static SecretKeySpec encryptionKey() throws GeneralSecurityException {
        String configured = System.getenv(KEY_ENV);
        if (configured == null || configured.isBlank()) {
            throw new GeneralSecurityException(
                    KEY_ENV + " must be configured before using authenticator enrollment.");
        }
        try {
            byte[] key = Base64.getDecoder().decode(configured);
            if (key.length != 32) {
                throw new GeneralSecurityException(KEY_ENV + " must be a Base64-encoded 32-byte key.");
            }
            return new SecretKeySpec(key, "AES");
        } catch (IllegalArgumentException exception) {
            throw new GeneralSecurityException(KEY_ENV + " must be valid Base64.", exception);
        }
    }

    private static String base32Encode(byte[] input) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder output = new StringBuilder((input.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte value : input) {
            buffer = (buffer << 8) | (value & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                output.append(alphabet.charAt((buffer >> (bitsLeft - 5)) & 0x1f));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            output.append(alphabet.charAt((buffer << (5 - bitsLeft)) & 0x1f));
        }
        return output.toString();
    }

    private static byte[] base32Decode(String input) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        ByteBuffer output = ByteBuffer.allocate(input.length() * 5 / 8);
        int buffer = 0;
        int bitsLeft = 0;
        for (char character : input.toCharArray()) {
            int value = alphabet.indexOf(character);
            if (value < 0) {
                throw new IllegalArgumentException("Invalid Base32 secret.");
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                output.put((byte) (buffer >> (bitsLeft - 8)));
                bitsLeft -= 8;
            }
        }
        return output.array();
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
