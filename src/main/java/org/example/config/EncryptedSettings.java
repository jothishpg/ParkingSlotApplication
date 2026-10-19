package org.example.config;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Base64;

public final class EncryptedSettings {
    private static final String ENCRYPTION_KEY_ENV = "GLOBAL_ENCRYPTION_KEY";
    private static final int IV_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private EncryptedSettings() {
    }

    public static String get(String keyName) {
        if (keyName == null || keyName.isBlank()) {
            throw new IllegalArgumentException("keyName must not be blank.");
        }

        String sql = "SELECT key_value FROM app_settings WHERE key_name = ?";
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, keyName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? decrypt(result.getString("key_value")) : null;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Unable to read encrypted setting '" + keyName + "' from the database.",
                    exception);
        }
    }

    public static String require(String keyName) {
        String value = get(keyName);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Required encrypted setting is missing: " + keyName);
        }
        return value;
    }

    public static String encrypt(String value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null.");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] packed = Arrays.copyOf(iv, iv.length + ciphertext.length);
            System.arraycopy(ciphertext, 0, packed, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt setting value.", exception);
        }
    }

    public static String decrypt(String encryptedValue) {
        if (encryptedValue == null) {
            throw new IllegalArgumentException("encryptedValue must not be null.");
        }
        try {
            byte[] packed = Base64.getDecoder().decode(encryptedValue);
            if (packed.length <= IV_BYTES) {
                throw new GeneralSecurityException("Encrypted value is malformed.");
            }
            byte[] iv = Arrays.copyOfRange(packed, 0, IV_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(packed, IV_BYTES, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Unable to decrypt setting value. Verify GLOBAL_ENCRYPTION_KEY and the stored value.",
                    exception);
        }
    }

    private static SecretKeySpec encryptionKey() {
        String encoded = System.getenv(ENCRYPTION_KEY_ENV);
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException(ENCRYPTION_KEY_ENV
                    + " must contain a Base64-encoded 32-byte key.");
        }
        try {
            byte[] key = Base64.getDecoder().decode(encoded.trim());
            if (key.length != 32) {
                throw new IllegalArgumentException("Key must be 32 bytes.");
            }
            return new SecretKeySpec(key, "AES");
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(ENCRYPTION_KEY_ENV
                    + " must contain a Base64-encoded 32-byte key.", exception);
        }
    }
}
