package org.example.auth;

import org.mindrot.jbcrypt.BCrypt;

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

    private static final int COST = 12;
    private static final String DUMMY_HASH =
            BCrypt.hashpw("timing-equalizer-not-a-real-password", BCrypt.gensalt(COST));

    private PasswordHasher() {
    }

    public static String hash(String plain) {
        return BCrypt.hashpw(plain, BCrypt.gensalt(COST));
    }

    public static boolean isBcryptFormat(String stored) {
        return stored != null
                && stored.length() == 60
                && stored.matches("^\\$2[abxy]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");
    }

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

    public static void burnTime(String plain) {
        try {
            BCrypt.checkpw(plain == null ? "" : plain, DUMMY_HASH);
        } catch (IllegalArgumentException ignored) {
        }
    }
}