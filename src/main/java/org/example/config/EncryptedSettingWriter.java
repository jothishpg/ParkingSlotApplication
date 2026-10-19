package org.example.config;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Base64;

public final class EncryptedSettingWriter {
    private static final String ENCRYPTION_KEY_ENV = "GLOBAL_ENCRYPTION_KEY";
    private static final int IV_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private EncryptedSettingWriter() {
    }

    public static void main(String[] args) throws IOException, SQLException, GeneralSecurityException {
        Console console = System.console();
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

        System.out.print("key_name: ");
        String keyName = input.readLine();
        if (keyName == null || keyName.isBlank()) {
            throw new IllegalArgumentException("key_name must not be blank.");
        }

        String keyValue;
        if (console != null) {
            char[] entered = console.readPassword("key_value: ");
            if (entered == null) {
                throw new IllegalStateException("No key_value was entered.");
            }
            keyValue = new String(entered);
            Arrays.fill(entered, '\0');
        } else {
            System.out.println("No terminal console is available; key_value input will be visible.");
            System.out.print("key_value: ");
            keyValue = input.readLine();
            if (keyValue == null) {
                throw new IllegalStateException("No key_value was entered.");
            }
        }

        String encryptedValue = encrypt(keyValue, encryptionKey());
        try (Connection connection = DatabaseConnection.getConnection()) {
            createTableIfMissing(connection);
            upsert(connection, keyName.trim(), encryptedValue);
        }

        System.out.println("Setting saved.");
    }

    private static void createTableIfMissing(Connection connection) throws SQLException {
        String sql = """
                CREATE TABLE IF NOT EXISTS app_settings (
                    key_name VARCHAR(255) PRIMARY KEY,
                    key_value TEXT NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """;
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void upsert(Connection connection, String keyName, String encryptedValue)
            throws SQLException {
        String sql = """
                INSERT INTO app_settings (key_name, key_value, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (key_name) DO UPDATE
                SET key_value = EXCLUDED.key_value,
                    updated_at = CURRENT_TIMESTAMP
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, keyName);
            statement.setString(2, encryptedValue);
            statement.executeUpdate();
        }
    }

    private static String encrypt(String value, SecretKeySpec key) throws GeneralSecurityException {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] packed = Arrays.copyOf(iv, iv.length + ciphertext.length);
        System.arraycopy(ciphertext, 0, packed, iv.length, ciphertext.length);
        return Base64.getEncoder().encodeToString(packed);
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
