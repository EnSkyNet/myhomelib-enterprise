package com.myhomelibcorp.shared.util;

import com.myhomelibcorp.shared.security.CredentialMasterKeyManager;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/** AES-256-GCM credential encryption with fail-closed key management. */
public final class EncryptionUtil {
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_NONCE_LENGTH = 12;
    private static final byte CURRENT_VERSION = 1;
    private static final String ENVELOPE_PREFIX = "mhlenc:v1:";
    private static final String MASTER_KEY_ENV = "MYHOMELIB_ENCRYPTION_KEY";
    private static final String MASTER_KEY_PROPERTY = "myhomelib.encryption.key";

    private static final SecretKey secretKey = initializeKey();
    private static final SecureRandom RANDOM = new SecureRandom();

    private EncryptionUtil() { }

    private static SecretKey initializeKey() {
        String configured = System.getenv(MASTER_KEY_ENV);
        if (configured == null || configured.isBlank()) configured = System.getProperty(MASTER_KEY_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return decodeKey(configured, "configured encryption key");
        }
        return loadOrCreateLocalKey();
    }

    private static SecretKey loadOrCreateLocalKey() {
        String encoded = CredentialMasterKeyManager.loadOrCreateDefault(AppPaths.configDir(), AppPaths.portableMode());
        return decodeKey(encoded, "credential master key");
    }

    private static SecretKey decodeKey(String base64, String source) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(base64);
            if (keyBytes.length != 32) throw new IllegalArgumentException("expected 32 bytes, got " + keyBytes.length);
            return new SecretKeySpec(keyBytes, ALGORITHM);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Invalid " + source + ": AES-256 key must be Base64-encoded 32 bytes", e);
        }
    }

    /**
     * Encrypts plaintext into an explicit, versioned envelope. Existing v1 envelopes are left
     * unchanged. Authenticated legacy ciphertext (the pre-envelope Base64 format) is decrypted and
     * immediately re-encrypted into the current envelope so normal save paths migrate it safely.
     */
    public static String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) return plainText;
        if (isCurrentEnvelope(plainText)) return plainText;

        String value = tryDecryptLegacy(plainText).orElse(plainText);
        try {
            byte[] nonce = new byte[GCM_NONCE_LENGTH];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, nonce));
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(1 + nonce.length + ciphertext.length);
            buffer.put(CURRENT_VERSION).put(nonce).put(ciphertext);
            return ENVELOPE_PREFIX + Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new SecurityException("Encryption failed", e);
        }
    }

    /**
     * Decrypts the current explicit envelope. Legacy authenticated ciphertext remains readable and
     * ordinary legacy plaintext is returned unchanged. A malformed/tampered current envelope fails
     * closed instead of being mistaken for plaintext.
     */
    public static String decrypt(String encryptedText) {
        if (encryptedText == null || encryptedText.isEmpty()) return encryptedText;
        if (isCurrentEnvelope(encryptedText)) {
            return decryptCurrentEnvelope(encryptedText);
        }
        return tryDecryptLegacy(encryptedText).orElse(encryptedText);
    }

    /**
     * Returns true only for an explicit current envelope or legacy ciphertext that authenticates
     * successfully with this installation's key. Merely looking like Base64 is no longer enough.
     */
    public static boolean isEncrypted(String text) {
        if (text == null || text.isEmpty()) return false;
        if (isCurrentEnvelope(text)) return true;
        return tryDecryptLegacy(text).isPresent();
    }

    /** Returns true only for the current explicit envelope marker; no decryption is attempted. */
    public static boolean isCurrentEnvelope(String value) {
        return value != null && value.startsWith(ENVELOPE_PREFIX);
    }

    private static String decryptCurrentEnvelope(String value) {
        String payload = value.substring(ENVELOPE_PREFIX.length());
        final byte[] combined;
        try {
            combined = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            throw new SecurityException("Encrypted value has an invalid v1 envelope", e);
        }
        if (!isStructurallyValidCiphertext(combined)) {
            throw new SecurityException("Encrypted value has an invalid v1 payload");
        }
        try {
            return decryptCombined(combined);
        } catch (Exception e) {
            throw new SecurityException("Decryption failed; encrypted value may be corrupted or tampered", e);
        }
    }

    private static Optional<String> tryDecryptLegacy(String value) {
        final byte[] combined;
        try {
            combined = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException notBase64) {
            return Optional.empty();
        }
        if (!isStructurallyValidCiphertext(combined)) return Optional.empty();
        try {
            return Optional.of(decryptCombined(combined));
        } catch (AEADBadTagException notOurCiphertext) {
            return Optional.empty();
        } catch (Exception invalidLegacyCiphertext) {
            return Optional.empty();
        }
    }

    private static boolean isStructurallyValidCiphertext(byte[] combined) {
        return combined.length >= 1 + GCM_NONCE_LENGTH + 16 && combined[0] == CURRENT_VERSION;
    }

    private static String decryptCombined(byte[] combined) throws Exception {
        byte[] nonce = new byte[GCM_NONCE_LENGTH];
        System.arraycopy(combined, 1, nonce, 0, GCM_NONCE_LENGTH);
        byte[] ciphertext = new byte[combined.length - 1 - GCM_NONCE_LENGTH];
        System.arraycopy(combined, 1 + GCM_NONCE_LENGTH, ciphertext, 0, ciphertext.length);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, nonce));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }
}
