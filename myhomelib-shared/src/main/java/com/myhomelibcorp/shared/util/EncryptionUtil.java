package com.myhomelibcorp.shared.util;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/** AES-256-GCM credential encryption with fail-closed key management. */
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
    private static final String LOCAL_KEY_FILE = "credential-key.aes256";

    private static final SecretKey secretKey = initializeKey();

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
        try {
            Path dir = AppPaths.configDir();
            Files.createDirectories(dir);
            Path keyFile = dir.resolve(LOCAL_KEY_FILE);
            if (Files.isRegularFile(keyFile)) {
                return decodeKey(Files.readString(keyFile, StandardCharsets.US_ASCII).trim(), keyFile.toString());
            }

            String generated = generateKey();
            Path temp = Files.createTempFile(dir, ".credential-key-", ".tmp");
            try {
                Files.writeString(temp, generated + System.lineSeparator(), StandardCharsets.US_ASCII,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                restrictPermissions(temp);
                try {
                    Files.move(temp, keyFile, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.FileAlreadyExistsException race) {
                    // Another process won initialization; use its stable key.
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    if (!Files.exists(keyFile)) Files.move(temp, keyFile);
                }
                if (Files.exists(temp)) Files.deleteIfExists(temp);
                restrictPermissions(keyFile);
                String persisted = Files.readString(keyFile, StandardCharsets.US_ASCII).trim();
                log.info("Local credential encryption key initialized at {}", keyFile);
                return decodeKey(persisted, keyFile.toString());
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Credential encryption key is unavailable; refusing plaintext credential storage", e);
        }
    }

    private static void restrictPermissions(Path path) {
        try {
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            }
        } catch (Exception e) {
            log.warn("Could not restrict credential-key permissions on {}: {}", path, e.getMessage());
        }
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

    private static String generateKey() throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance(ALGORITHM);
        keyGen.init(KEY_SIZE, new SecureRandom());
        return Base64.getEncoder().encodeToString(keyGen.generateKey().getEncoded());
    }


    public static String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) return plainText;
        // Idempotence is intentional for repository save paths.
        if (isEncrypted(plainText)) return plainText;
        try {
            byte[] nonce = new byte[GCM_NONCE_LENGTH];
            new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, nonce));
            byte[] ciphertext = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(1 + nonce.length + ciphertext.length);
            buffer.put(CURRENT_VERSION).put(nonce).put(ciphertext);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new SecurityException("Encryption failed", e);
        }
    }

    /**
     * Decrypts AES-GCM ciphertext. Legacy plaintext is returned unchanged so existing installations
     * remain readable; repositories migrate it to ciphertext on read/save.
     */
    public static String decrypt(String encryptedText) {
        if (encryptedText == null || encryptedText.isEmpty()) return encryptedText;
        if (!isEncrypted(encryptedText)) return encryptedText;
        try {
            byte[] combined = Base64.getDecoder().decode(encryptedText);
            byte[] nonce = new byte[GCM_NONCE_LENGTH];
            System.arraycopy(combined, 1, nonce, 0, GCM_NONCE_LENGTH);
            byte[] ciphertext = new byte[combined.length - 1 - GCM_NONCE_LENGTH];
            System.arraycopy(combined, 1 + GCM_NONCE_LENGTH, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new SecurityException("Decryption failed", e);
        }
    }

    public static boolean isEncrypted(String text) {
        if (text == null || text.isEmpty()) return false;
        try {
            byte[] data = Base64.getDecoder().decode(text);
            return data.length >= 1 + GCM_NONCE_LENGTH + 16 && data[0] == CURRENT_VERSION;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

}
