package org.example.notification;

import org.example.config.EncryptedSettings;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public final class MetaTokenCrypto {
    private static final String KEY_SETTING = "META_TOKEN_ENCRYPTION_KEY";
    private static final int IV_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private MetaTokenCrypto() {
    }

    public static String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] result = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(result);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt Meta credentials.", exception);
        }
    }

    public static String decrypt(String ciphertext) {
        try {
            byte[] packed = Base64.getUrlDecoder().decode(ciphertext);
            if (packed.length <= IV_BYTES) {
                throw new GeneralSecurityException("Encrypted value is malformed.");
            }
            byte[] iv = Arrays.copyOfRange(packed, 0, IV_BYTES);
            byte[] encrypted = Arrays.copyOfRange(packed, IV_BYTES, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Unable to decrypt Meta credentials. Verify the META_TOKEN_ENCRYPTION_KEY setting is unchanged.",
                    exception);
        }
    }

    public static void validateKey() {
        key();
    }

    private static SecretKeySpec key() {
        String encoded = EncryptedSettings.require(KEY_SETTING);
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException(KEY_SETTING
                    + " must contain a Base64-encoded 32-byte key.");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded.trim());
            if (decoded.length != 32) {
                throw new IllegalArgumentException("Key must be 32 bytes.");
            }
            return new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(KEY_SETTING
                    + " must contain a Base64-encoded 32-byte key.", exception);
        }
    }
}
