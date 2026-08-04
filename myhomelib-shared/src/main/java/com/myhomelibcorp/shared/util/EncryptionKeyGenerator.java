package com.myhomelibcorp.shared.util;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Утиліта для генерації ключа шифрування.
 * Використовується тільки один раз при налаштуванні нового середовища.
 */
@Slf4j
public final class EncryptionKeyGenerator {

    private static final String ALGORITHM = "AES";
    private static final int KEY_SIZE = 256;

    private EncryptionKeyGenerator() {
        // Utility class
    }

    public static void main(String[] args) {
        String key = generateKey();
        log.info("\n\n=== ENCRYPTION KEY ===\n");
        log.info("Set this as environment variable:");
        log.info("export MYHOMELIB_ENCRYPTION_KEY={}", key);
        log.info("\nOr as system property:");
        log.info("-Dmyhomelib.encryption.key={}", key);
        log.info("\n=======================\n");
    }

    public static String generateKey() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance(ALGORITHM);
            keyGen.init(KEY_SIZE, new SecureRandom());
            SecretKey key = keyGen.generateKey();
            return Base64.getEncoder().encodeToString(key.getEncoded());
        } catch (Exception e) {
            log.error("Failed to generate encryption key", e);
            throw new RuntimeException("Failed to generate encryption key", e);
        }
    }
}