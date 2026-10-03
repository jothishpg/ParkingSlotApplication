package org.example.auth;

import org.mindrot.jbcrypt.BCrypt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Single place for everything password-hash related.
 *
 * Passwords are HASHED (one-way), never encrypted. A bcrypt hash looks like:
 *
 *   $2a$12$N9qo8uLOickgx2ZMRZoMye IjZAgcfl7p92ldGxad68LJZdL17lhWy
 *   \__/\/ \____________________/ \_____________________________/
 *    |  |          salt (22)                 hash (31)
 *    |  cost (work factor, 2^12 rounds)
 *    algorithm version
 *
 * The random salt and the cost are stored inside the hash string itself, so
 * the database needs only one column (60 characters).
 */
public final class PasswordHasher {

    /** Work factor. Each +1 doubles the time. Tune so a hash takes ~250-500 ms. */
    private static final int COST = 12;

    /** bcrypt only uses the first 72 bytes of a password. */
    public static final int MAX_PASSWORD_BYTES = 72;

    // A valid hash of a throwaway password. Checking against it when a login
    // email doesn't exist makes "unknown email" take as long as "wrong
    // password", so response time can't be used to discover which emails exist.
    private static final String DUMMY_HASH =
            BCrypt.hashpw("timing-equalizer-not-a-real-password", BCrypt.gensalt(COST));

    private PasswordHasher() {
    }

    /** Hashes a plaintext password with a fresh random salt. */
    public static String hash(String plain) {
        return BCrypt.hashpw(plain, BCrypt.gensalt(COST));
    }

    /**
     * True if the stored value LOOKS like a bcrypt hash ($2a$/$2b$/$2x$/$2y$
     * + 2-digit cost + 53 chars). Used to tell hashed rows from legacy
     * plaintext rows, and to make the migration safe to re-run.
     */
    public static boolean isBcryptFormat(String stored) {
        return stored != null
                && stored.length() == 60
                && stored.matches("^\\$2[abxy]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");
    }

    /**
     * Verifies a plaintext password against a stored bcrypt hash. Returns
     * false (never throws) for malformed or unsupported hashes. Note:
     * jBCrypt 0.4 can verify $2a$ hashes only, which is exactly what
     * hash() produces.
     */
    public static boolean matches(String plain, String stored) {
        if (plain == null || !isBcryptFormat(stored)) {
            return false;
        }
        try {
            return BCrypt.checkpw(plain, stored);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** True if the hash was made with a lower cost than the current COST. */
    public static boolean needsRehash(String stored) {
        if (!isBcryptFormat(stored)) {
            return true;
        }
        int storedCost = Integer.parseInt(stored.substring(4, 6));
        return storedCost < COST;
    }

    /** Spends the same time as a real check; call when the user doesn't exist. */
    public static void burnTime(String plain) {
        try {
            BCrypt.checkpw(plain == null ? "" : plain, DUMMY_HASH);
        } catch (IllegalArgumentException ignored) {
            // nothing to do; the goal is only to consume comparable time
        }
    }

    /** bcrypt silently ignores bytes past 72, so reject longer passwords up front. */
    public static boolean withinBcryptLimit(String plain) {
        return plain != null
                && plain.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;
    }

    /**
     * Constant-time equality for the temporary legacy-plaintext login path,
     * so that path doesn't leak how many leading characters matched.
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}