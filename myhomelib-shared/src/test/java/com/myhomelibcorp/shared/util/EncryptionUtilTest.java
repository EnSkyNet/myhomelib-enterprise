package com.myhomelibcorp.shared.util;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptionUtilTest {
    private static final byte[] KEY = new byte[32];

    @BeforeAll
    static void configureStableKey() {
        System.setProperty("myhomelib.encryption.key", Base64.getEncoder().encodeToString(KEY));
    }

    @Test
    void newCiphertextUsesExplicitEnvelopeAndRoundTrips() {
        String encrypted = EncryptionUtil.encrypt("catalog-secret");

        assertThat(encrypted).startsWith("mhlenc:v1:");
        assertThat(EncryptionUtil.isEncrypted(encrypted)).isTrue();
        assertThat(EncryptionUtil.isCurrentEnvelope(encrypted)).isTrue();
        assertThat(EncryptionUtil.decrypt(encrypted)).isEqualTo("catalog-secret");
        assertThat(EncryptionUtil.encrypt(encrypted)).isEqualTo(encrypted);
    }

    @Test
    void craftedBase64PlaintextIsNotClassifiedAsCiphertext() {
        byte[] fake = new byte[40];
        fake[0] = 1; // matches the old heuristic but has no valid GCM tag
        String crafted = Base64.getEncoder().encodeToString(fake);

        assertThat(EncryptionUtil.isEncrypted(crafted)).isFalse();
        assertThat(EncryptionUtil.decrypt(crafted)).isEqualTo(crafted);
    }

    @Test
    void legacyCiphertextRemainsReadableAndMigratesWhenSaved() throws Exception {
        String legacy = legacyCiphertext("legacy-password");

        assertThat(legacy).doesNotStartWith("mhlenc:");
        assertThat(EncryptionUtil.isEncrypted(legacy)).isTrue();
        assertThat(EncryptionUtil.isCurrentEnvelope(legacy)).isFalse();
        assertThat(EncryptionUtil.decrypt(legacy)).isEqualTo("legacy-password");

        String migrated = EncryptionUtil.encrypt(legacy);
        assertThat(migrated).startsWith("mhlenc:v1:").isNotEqualTo(legacy);
        assertThat(EncryptionUtil.decrypt(migrated)).isEqualTo("legacy-password");
    }

    @Test
    void tamperedEnvelopeFailsClosed() {
        String encrypted = EncryptionUtil.encrypt("do-not-corrupt");
        byte[] bytes = Base64.getDecoder().decode(encrypted.substring("mhlenc:v1:".length()));
        bytes[bytes.length - 1] ^= 0x01;
        String tampered = "mhlenc:v1:" + Base64.getEncoder().encodeToString(bytes);

        assertThat(EncryptionUtil.isEncrypted(tampered)).isTrue();
        assertThatThrownBy(() -> EncryptionUtil.decrypt(tampered))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("tampered");
    }

    private static String legacyCiphertext(String plaintext) throws Exception {
        byte[] nonce = new byte[12];
        for (int i = 0; i < nonce.length; i++) nonce[i] = (byte) (i + 1);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        ByteBuffer buffer = ByteBuffer.allocate(1 + nonce.length + ciphertext.length);
        buffer.put((byte) 1).put(nonce).put(ciphertext);
        return Base64.getEncoder().encodeToString(buffer.array());
    }
}
