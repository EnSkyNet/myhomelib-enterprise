package com.myhomelibcorp.infrastructure.sync.webdav;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/** Versioned authenticated envelope for sync bundles with key-rotation support. */
public final class AesGcmSyncPayloadEnvelope {
    private static final byte[] MAGIC = new byte[]{'M','H','L','S','Y','N','C'};
    private static final byte LEGACY_VERSION = 1;
    private static final byte VERSION = 2;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_PLAINTEXT_BYTES = 16 * 1024 * 1024;
    private static final byte[] LEGACY_AAD = "MyHomeLib-Sync-Payload-v1".getBytes(StandardCharsets.US_ASCII);
    private static final String AAD_PREFIX = "MyHomeLib-Sync-Payload-v2:";

    private final SyncKeyRing keyRing;
    private final SecureRandom random;

    public AesGcmSyncPayloadEnvelope(byte[] keyBytes) {
        this(new SyncKeyRing(keyBytes), new SecureRandom());
    }

    public AesGcmSyncPayloadEnvelope(SyncKeyRing keyRing) {
        this(keyRing, new SecureRandom());
    }

    AesGcmSyncPayloadEnvelope(SyncKeyRing keyRing, SecureRandom random) {
        this.keyRing = java.util.Objects.requireNonNull(keyRing, "keyRing");
        this.random = java.util.Objects.requireNonNull(random, "random");
    }

    public byte[] protect(byte[] plaintext) {
        validatePlaintext(plaintext);
        SyncKeyRing.Key active = keyRing.active();
        byte[] keyId = active.id().getBytes(StandardCharsets.US_ASCII);
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, active.bytes(), nonce, aad(active.id()), plaintext,
                "Could not encrypt sync payload");
        return ByteBuffer.allocate(MAGIC.length + 1 + 1 + keyId.length + NONCE_BYTES + ciphertext.length)
                .put(MAGIC).put(VERSION).put((byte) keyId.length).put(keyId).put(nonce).put(ciphertext).array();
    }

    public byte[] unprotect(byte[] envelope) {
        Parsed parsed = parse(envelope);
        if (parsed.version == LEGACY_VERSION) {
            SecurityException last = null;
            for (SyncKeyRing.Key key : keyRing.candidates()) {
                try {
                    byte[] plain = crypt(Cipher.DECRYPT_MODE, key.bytes(), parsed.nonce, LEGACY_AAD,
                            parsed.ciphertext, "Sync payload authentication failed");
                    validateDecrypted(plain);
                    return plain;
                } catch (SecurityException e) {
                    last = e;
                }
            }
            throw new SecurityException("Sync payload authentication failed", last);
        }
        SyncKeyRing.Key key = keyRing.require(parsed.keyId);
        byte[] plain = crypt(Cipher.DECRYPT_MODE, key.bytes(), parsed.nonce, aad(parsed.keyId), parsed.ciphertext,
                "Sync payload authentication failed");
        validateDecrypted(plain);
        return plain;
    }

    /** True when a payload is legacy or encrypted with a non-active key. */
    public boolean needsRotation(byte[] envelope) {
        Parsed parsed = parse(envelope);
        return parsed.version != VERSION || !keyRing.isActive(parsed.keyId);
    }

    /** Authenticates/decrypts with an allowed old key and emits a fresh v2 envelope with the active key. */
    public byte[] rewrap(byte[] envelope) {
        return protect(unprotect(envelope));
    }

    public String activeKeyId() { return keyRing.active().id(); }

    private static Parsed parse(byte[] envelope) {
        if (envelope == null || envelope.length < MAGIC.length + 1 + NONCE_BYTES + 16) {
            throw new SecurityException("Sync payload envelope is truncated");
        }
        ByteBuffer buffer = ByteBuffer.wrap(envelope);
        byte[] magic = new byte[MAGIC.length];
        buffer.get(magic);
        if (!Arrays.equals(MAGIC, magic)) throw new SecurityException("Unknown sync payload envelope");
        byte version = buffer.get();
        if (version == LEGACY_VERSION) {
            if (buffer.remaining() < NONCE_BYTES + 16) throw new SecurityException("Sync payload envelope is truncated");
            byte[] nonce = new byte[NONCE_BYTES];
            buffer.get(nonce);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            return new Parsed(version, "", nonce, ciphertext);
        }
        if (version != VERSION) throw new SecurityException("Unsupported sync payload version: " + version);
        if (!buffer.hasRemaining()) throw new SecurityException("Sync payload envelope is truncated");
        int keyIdLength = Byte.toUnsignedInt(buffer.get());
        if (keyIdLength != 16 || buffer.remaining() < keyIdLength + NONCE_BYTES + 16) {
            throw new SecurityException("Invalid sync payload key id");
        }
        byte[] keyIdBytes = new byte[keyIdLength];
        buffer.get(keyIdBytes);
        String keyId = new String(keyIdBytes, StandardCharsets.US_ASCII);
        if (!keyId.matches("[a-f0-9]{16}")) throw new SecurityException("Invalid sync payload key id");
        byte[] nonce = new byte[NONCE_BYTES];
        buffer.get(nonce);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);
        return new Parsed(version, keyId, nonce, ciphertext);
    }

    private static byte[] crypt(int mode, byte[] keyBytes, byte[] nonce, byte[] aad, byte[] input, String message) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new SecurityException(message, e);
        }
    }

    private static byte[] aad(String keyId) {
        return (AAD_PREFIX + keyId).getBytes(StandardCharsets.US_ASCII);
    }

    private static void validatePlaintext(byte[] plaintext) {
        if (plaintext == null || plaintext.length == 0 || plaintext.length > MAX_PLAINTEXT_BYTES) {
            throw new IllegalArgumentException("Sync plaintext size is outside allowed range");
        }
    }

    private static void validateDecrypted(byte[] plaintext) {
        if (plaintext.length == 0 || plaintext.length > MAX_PLAINTEXT_BYTES) {
            throw new SecurityException("Decrypted sync payload size is outside allowed range");
        }
    }

    private record Parsed(byte version, String keyId, byte[] nonce, byte[] ciphertext) {}
}
