package org.example.auth;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class JwtUtil {

    private static final Logger LOGGER = Logger.getLogger(JwtUtil.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private static final String SECRET = requiredSecret();

    private JwtUtil() {
    }

    private static String requiredSecret() {
        String value = System.getenv("SESSION_SECRET");
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "SESSION_SECRET environment variable is not set. "
                            + "Generate one long random value and set it in Catalyst's "
                            + "Environmental Variables before deploying.");
        }
        return value;
    }

    public static String generate(Map<String, Object> claims, long ttlSeconds) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>(claims);
            long now = System.currentTimeMillis() / 1000;
            payload.put("iat", now);
            payload.put("exp", now + ttlSeconds);

            String payloadJson = MAPPER.writeValueAsString(payload);
            String encodedPayload =
                    ENCODER.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
            String signature = sign(encodedPayload);

            return encodedPayload + "." + signature;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to generate token.", exception);
        }
    }

    public static Map<String, Object> verify(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }

        String[] parts = token.split("\\.");
        if (parts.length != 2) {
            return null;
        }

        String encodedPayload = parts[0];
        String signature = parts[1];

        String expectedSignature;
        try {
            expectedSignature = sign(encodedPayload);
        } catch (Exception exception) {
            LOGGER.log(Level.WARNING, "Unable to compute token signature.", exception);
            return null;
        }

        if (!constantTimeEquals(expectedSignature, signature)) {
            return null;
        }

        try {
            String payloadJson =
                    new String(DECODER.decode(encodedPayload), StandardCharsets.UTF_8);

            @SuppressWarnings("unchecked")
            Map<String, Object> claims = MAPPER.readValue(payloadJson, Map.class);

            long expiry = ((Number) claims.get("exp")).longValue();
            long now = System.currentTimeMillis() / 1000;

            if (now > expiry) {
                return null;
            }

            return claims;
        } catch (Exception exception) {
            LOGGER.log(Level.WARNING, "Unable to parse token payload.", exception);
            return null;
        }
    }

    private static String sign(String data) throws Exception {
        Mac mac = Mac.getInstance(ALGORITHM);
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), ALGORITHM));
        byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return ENCODER.encodeToString(raw);
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < x.length; i++) {
            result |= x[i] ^ y[i];
        }
        return result == 0;
    }
}




/**
 * Minimal, dependency-free stateless token implementation (symmetric HMAC-SHA256).
 *
 * Replaces HttpSession for authentication state. HttpSession is stored in one
 * JVM process's memory only; on Catalyst's AppSail runtime, consecutive
 * requests are not guaranteed to land on the same process, so session data
 * created during login can be invisible to the very next request. A signed
 * token carries its own state in the cookie itself, so any process can verify
 * it without needing shared storage.
 *
 * This uses ONE shared secret key (symmetric) because the same application
 * both signs and verifies the token — unlike Google's id_token, where a
 * different, external party (this app) verifies something signed by a
 * different party (Google), which requires asymmetric keys instead.
 */