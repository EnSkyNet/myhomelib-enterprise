package com.myhomelibcorp.infrastructure.sync.webdav;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmSyncPayloadEnvelopeTest {
    @Test
    void roundTripsV2AndDoesNotExposePlaintext() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        byte[] plain = "private annotation: dragons at page 42".getBytes(StandardCharsets.UTF_8);

        AesGcmSyncPayloadEnvelope envelope = new AesGcmSyncPayloadEnvelope(key);
        byte[] encrypted = envelope.protect(plain);

        assertThat(indexOf(encrypted, plain)).isNegative();
        assertThat(envelope.needsRotation(encrypted)).isFalse();
        assertThat(envelope.unprotect(encrypted)).containsExactly(plain);
    }

    @Test
    void tamperingAndWrongKeyFailClosed() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 3);
        AesGcmSyncPayloadEnvelope envelope = new AesGcmSyncPayloadEnvelope(key);
        byte[] encrypted = envelope.protect("secret".getBytes(StandardCharsets.UTF_8));
        encrypted[encrypted.length - 1] ^= 0x01;

        assertThatThrownBy(() -> envelope.unprotect(encrypted)).isInstanceOf(SecurityException.class);

        byte[] otherKey = new byte[32];
        Arrays.fill(otherKey, (byte) 9);
        byte[] clean = envelope.protect("secret".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> new AesGcmSyncPayloadEnvelope(otherKey).unprotect(clean))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void keyRingReadsPreviousKeyAndRewrapsWithActiveKey() {
        byte[] oldKey = filled((byte) 1);
        byte[] newKey = filled((byte) 2);
        byte[] clear = "rotation payload".getBytes(StandardCharsets.UTF_8);
        byte[] oldEnvelope = new AesGcmSyncPayloadEnvelope(oldKey).protect(clear);

        AesGcmSyncPayloadEnvelope rotating = new AesGcmSyncPayloadEnvelope(new SyncKeyRing(newKey, oldKey));
        assertThat(rotating.needsRotation(oldEnvelope)).isTrue();
        assertThat(rotating.unprotect(oldEnvelope)).containsExactly(clear);

        byte[] migrated = rotating.rewrap(oldEnvelope);
        assertThat(rotating.needsRotation(migrated)).isFalse();
        assertThat(new AesGcmSyncPayloadEnvelope(newKey).unprotect(migrated)).containsExactly(clear);
        assertThatThrownBy(() -> new AesGcmSyncPayloadEnvelope(oldKey).unprotect(migrated))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void readsLegacyV1OnlyWhileMatchingKeyIsInRing() throws Exception {
        byte[] oldKey = filled((byte) 5);
        byte[] newKey = filled((byte) 6);
        byte[] clear = "legacy payload".getBytes(StandardCharsets.UTF_8);
        byte[] legacy = legacyV1(oldKey, clear);

        AesGcmSyncPayloadEnvelope rotating = new AesGcmSyncPayloadEnvelope(new SyncKeyRing(newKey, oldKey));
        assertThat(rotating.needsRotation(legacy)).isTrue();
        assertThat(rotating.unprotect(legacy)).containsExactly(clear);
        assertThatThrownBy(() -> new AesGcmSyncPayloadEnvelope(newKey).unprotect(legacy))
                .isInstanceOf(SecurityException.class);
    }

    private static byte[] legacyV1(byte[] key, byte[] plain) throws Exception {
        byte[] magic = new byte[]{'M','H','L','S','Y','N','C'};
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD("MyHomeLib-Sync-Payload-v1".getBytes(StandardCharsets.US_ASCII));
        byte[] ciphertext = cipher.doFinal(plain);
        return ByteBuffer.allocate(magic.length + 1 + nonce.length + ciphertext.length)
                .put(magic).put((byte) 1).put(nonce).put(ciphertext).array();
    }

    private static byte[] filled(byte value) {
        byte[] key = new byte[32];
        Arrays.fill(key, value);
        return key;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
