package com.myhomelibcorp.application.opds;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Versioned one-way password representation for OPDS Basic Authentication.
 * Uses only JDK primitives so the application layer does not depend on a crypto provider.
 */
public final class OpdsPasswordHash {
    private static final String PREFIX = "pbkdf2-sha256";
    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private OpdsPasswordHash() { }

    public static String hash(String password) {
        if (password == null || password.isEmpty()) return "";
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] derived = derive(password, salt, ITERATIONS, KEY_BITS);
        return PREFIX + "$" + ITERATIONS + "$"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(salt) + "$"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(derived);
    }

    public static boolean isHash(String value) {
        return value != null && value.startsWith(PREFIX + "$");
    }

    /**
     * Verifies either the current versioned hash or a legacy plaintext value.
     * Plaintext support is runtime-only for migration/backward compatibility; persisted settings
     * are migrated by {@link OpdsSettingsService}.
     */
    public static boolean matches(String candidate, String stored) {
        if (candidate == null || stored == null || stored.isEmpty()) return false;
        if (!isHash(stored)) return constantTimeEquals(candidate, stored);
        try {
            String[] parts = stored.split("\\$", -1);
            if (parts.length != 4 || !PREFIX.equals(parts[0])) return false;
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 50_000 || iterations > 2_000_000) return false;
            byte[] salt = Base64.getUrlDecoder().decode(parts[2]);
            byte[] expected = Base64.getUrlDecoder().decode(parts[3]);
            if (salt.length < 12 || expected.length < 16) return false;
            byte[] actual = derive(candidate, salt, iterations, expected.length * 8);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations, int keyBits) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, keyBits);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2-HMAC-SHA256 is not available", e);
        } finally {
            spec.clearPassword();
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
}
