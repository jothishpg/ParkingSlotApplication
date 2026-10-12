package org.example.payment;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

public final class PaymentCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();
    private PaymentCrypto() {}
    public static String random() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static boolean verify(String secret, byte[] body, String signature) {
        if (signature == null || !signature.matches("[0-9a-fA-F]{64}")) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return MessageDigest.isEqual(mac.doFinal(body), HexFormat.of().parseHex(signature));
        } catch (Exception e) { throw new IllegalStateException("Cannot verify payment signature", e); }
    }
    public static String encrypt(String value) {
        try {
            byte[] iv = new byte[12]; RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] data = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] packed = Arrays.copyOf(iv, iv.length + data.length);
            System.arraycopy(data, 0, packed, iv.length, data.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (Exception e) { throw new IllegalStateException("Cannot encrypt payment credentials", e); }
    }
    public static String decrypt(String value) {
        try {
            byte[] packed = Base64.getDecoder().decode(value);
            if (packed.length < 29) throw new IllegalArgumentException("Invalid ciphertext");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOf(packed, 12)));
            return new String(cipher.doFinal(Arrays.copyOfRange(packed, 12, packed.length)), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("Cannot decrypt payment credentials", e); }
    }
    private static SecretKeySpec key() {
        String encoded = System.getenv("RAZORPAY_TOKEN_ENCRYPTION_KEY");
        if (encoded == null) throw new IllegalStateException("Set RAZORPAY_TOKEN_ENCRYPTION_KEY");
        byte[] key = Base64.getDecoder().decode(encoded);
        if (key.length != 32) throw new IllegalStateException("Payment encryption key must be 32 bytes");
        return new SecretKeySpec(key, "AES");
    }
}
