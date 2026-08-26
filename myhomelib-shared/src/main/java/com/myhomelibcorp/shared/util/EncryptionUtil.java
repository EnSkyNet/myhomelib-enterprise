package com.myhomelibcorp.shared.util;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Безпечна утиліта для шифрування та дешифрування даних.
 * Використовує AES-GCM з випадковим nonce та versioned ciphertext.
 *
 * Якщо ключ не налаштовано, працює в режимі fallback (без шифрування)
 * з попередженням у логах.
 */
@Slf4j
public final class EncryptionUtil {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_SIZE = 256;
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_NONCE_LENGTH = 12;
    private static final byte CURRENT_VERSION = 1;

    private static final String MASTER_KEY_ENV = "MYHOMELIB_ENCRYPTION_KEY";
    private static final String MASTER_KEY_PROPERTY = "myhomelib.encryption.key";

    private static SecretKey secretKey;
    private static boolean initialized = false;
    private static boolean fallbackMode = false;

    static {
        initializeKey();
    }

    private EncryptionUtil() {
        // Utility class
    }

    private static void initializeKey() {
        // 1. Спроба отримати ключ зі змінної середовища
        String base64Key = System.getenv(MASTER_KEY_ENV);
        if (base64Key == null || base64Key.isEmpty()) {
            // 2. Спроба отримати ключ з System property
            base64Key = System.getProperty(MASTER_KEY_PROPERTY);
        }

        if (base64Key != null && !base64Key.isEmpty()) {
            try {
                byte[] keyBytes = Base64.getDecoder().decode(base64Key);
                if (keyBytes.length == 32) { // 256 bits = 32 bytes
                    secretKey = new SecretKeySpec(keyBytes, ALGORITHM);
                    initialized = true;
                    log.info("Encryption key loaded successfully");
                    return;
                } else {
                    log.error("Invalid encryption key length: expected 32 bytes, got {}", keyBytes.length);
                }
            } catch (IllegalArgumentException e) {
                log.error("Invalid Base64 encryption key", e);
            }
        }

        // Якщо ключ не знайдено - переходимо в fallback режим
        log.warn("No encryption key found! Running in fallback mode (no encryption).");
        log.warn("To enable encryption, set environment variable {} or system property {}",
                MASTER_KEY_ENV, MASTER_KEY_PROPERTY);
        fallbackMode = true;
        initialized = true; // Позначаємо як ініціалізований для роботи в fallback-режимі
    }

    /**
     * Генерує новий ключ і виводить його в консоль (для первинного налаштування).
     * Використовувати тільки один раз при створенні нового середовища.
     */
    public static String generateAndPrintKey() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance(ALGORITHM);
            keyGen.init(KEY_SIZE, new SecureRandom());
            SecretKey key = keyGen.generateKey();
            String base64Key = Base64.getEncoder().encodeToString(key.getEncoded());
            log.info("GENERATED ENCRYPTION KEY (save this value and set as environment variable):");
            log.info("export {}={}", MASTER_KEY_ENV, base64Key);
            return base64Key;
        } catch (Exception e) {
            log.error("Failed to generate encryption key", e);
            return null;
        }
    }

    /**
     * Шифрує текст з використанням AES-GCM.
     * Формат вихідних даних: [version(1 byte)][nonce(12 bytes)][ciphertext]
     * Кодується в Base64.
     */
    public static String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return plainText;
        }

        if (!initialized) {
            log.error("Encryption not initialized");
            throw new IllegalStateException("Encryption not initialized");
        }

        // В fallback-режимі повертаємо текст без змін
        if (fallbackMode) {
            log.debug("Encryption in fallback mode: returning plain text");
            return plainText;
        }

        try {
            // Генеруємо випадковий nonce
            byte[] nonce = new byte[GCM_NONCE_LENGTH];
            SecureRandom secureRandom = new SecureRandom();
            secureRandom.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, nonce);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);

            byte[] plainBytes = plainText.getBytes(StandardCharsets.UTF_8);
            byte[] ciphertext = cipher.doFinal(plainBytes);

            // Формуємо структуру: [version][nonce][ciphertext]
            ByteBuffer buffer = ByteBuffer.allocate(1 + nonce.length + ciphertext.length);
            buffer.put(CURRENT_VERSION);
            buffer.put(nonce);
            buffer.put(ciphertext);
            byte[] combined = buffer.array();

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("Failed to encrypt text", e);
            throw new SecurityException("Encryption failed", e);
        }
    }

    /**
     * Дешифрує текст з використанням AES-GCM.
     * Очікує формат: [version(1 byte)][nonce(12 bytes)][ciphertext] в Base64.
     */
    public static String decrypt(String encryptedText) {
        if (encryptedText == null || encryptedText.isEmpty()) {
            return encryptedText;
        }

        if (!initialized) {
            log.error("Encryption not initialized");
            throw new IllegalStateException("Encryption not initialized");
        }

        // В fallback-режимі повертаємо текст без змін
        if (fallbackMode) {
            log.debug("Decryption in fallback mode: returning plain text");
            return encryptedText;
        }

        try {
            byte[] combined = Base64.getDecoder().decode(encryptedText);

            // Перевіряємо мінімальну довжину
            if (combined.length < 1 + GCM_NONCE_LENGTH) {
                log.error("Encrypted data too short");
                throw new SecurityException("Invalid encrypted data format");
            }

            // Читаємо версію
            byte version = combined[0];
            if (version != CURRENT_VERSION) {
                log.error("Unsupported encryption version: {}", version);
                throw new SecurityException("Unsupported encryption version: " + version);
            }

            // Читаємо nonce та ciphertext
            byte[] nonce = new byte[GCM_NONCE_LENGTH];
            System.arraycopy(combined, 1, nonce, 0, GCM_NONCE_LENGTH);

            byte[] ciphertext = new byte[combined.length - 1 - GCM_NONCE_LENGTH];
            System.arraycopy(combined, 1 + GCM_NONCE_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, nonce);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

            byte[] plainBytes = cipher.doFinal(ciphertext);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to decrypt text", e);
            throw new SecurityException("Decryption failed", e);
        }
    }

    /**
     * Перевіряє, чи текст є зашифрованим (формат versioned ciphertext в Base64).
     */
    public static boolean isEncrypted(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        // В fallback-режимі нічого не зашифровано
        if (fallbackMode) {
            return false;
        }

        try {
            byte[] data = Base64.getDecoder().decode(text);
            // Перевіряємо: довжина >= 1 + nonce + мінімальна довжина ciphertext
            if (data.length < 1 + GCM_NONCE_LENGTH + 16) {
                return false;
            }
            byte version = data[0];
            return version == CURRENT_VERSION;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static boolean isFallbackMode() {
        return fallbackMode;
    }
}