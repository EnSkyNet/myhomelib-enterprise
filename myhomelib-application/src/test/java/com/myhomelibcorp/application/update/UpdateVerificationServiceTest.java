package com.myhomelibcorp.application.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpdateVerificationServiceTest {
    @TempDir Path temp;

    @Test
    void acceptsValidManifestAndArtifact() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] bytes = "verified-update".getBytes(StandardCharsets.UTF_8);
        Path artifact = temp.resolve("candidate.bin");
        Files.write(artifact, bytes);
        ReleaseManifest unsigned = manifest(bytes, "", "release-key", "8.1.0", "8.0.0", "linux", "x64");
        ReleaseManifest signed = withSignature(unsigned, pair);
        UpdateVerificationService service = new UpdateVerificationService(id -> "release-key".equals(id)
                ? Optional.of(pair.getPublic()) : Optional.empty());

        service.verifyManifest(signed, "myhomelib", "8.0.0", "8.0.0", "linux", "x64", false);
        service.verifyArtifact(artifact, signed);
        assertThat(UpdateVerificationService.compareVersions("8.1.0", "8.0.9")).isPositive();
    }

    @Test
    void rejectsWrongSignatureUnknownKeyDowngradeAndWrongPlatform() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] bytes = "candidate".getBytes(StandardCharsets.UTF_8);
        ReleaseManifest valid = withSignature(manifest(bytes, "", "release-key", "8.1.0", "8.0.0", "windows", "x64"), pair);
        UpdateVerificationService service = new UpdateVerificationService(id -> Optional.of(pair.getPublic()));

        ReleaseManifest wrongSignature = withSignature(manifest(bytes, "", "release-key", "8.1.0", "8.0.0", "windows", "x64"), other);
        assertThatThrownBy(() -> service.verifyManifest(wrongSignature, "myhomelib", "8.0.0", "8.0.0", "windows", "x64", false))
                .isInstanceOf(SecurityException.class).hasMessageContaining("signature");
        assertThatThrownBy(() -> new UpdateVerificationService(id -> Optional.empty())
                .verifyManifest(valid, "myhomelib", "8.0.0", "8.0.0", "windows", "x64", false))
                .isInstanceOf(SecurityException.class).hasMessageContaining("Unknown");
        ReleaseManifest downgrade = withSignature(manifest(bytes, "", "release-key", "7.9.9", "8.0.0", "windows", "x64"), pair);
        assertThatThrownBy(() -> service.verifyManifest(downgrade, "myhomelib", "8.0.0", "8.0.0", "windows", "x64", false))
                .isInstanceOf(SecurityException.class).hasMessageContaining("Downgrade");
        assertThatThrownBy(() -> service.verifyManifest(valid, "myhomelib", "8.0.0", "8.0.0", "linux", "x64", false))
                .isInstanceOf(SecurityException.class).hasMessageContaining("platform");
    }

    @Test
    void rejectsWrongHashLengthAndUnsafeArtifact() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] expected = "expected".getBytes(StandardCharsets.UTF_8);
        ReleaseManifest manifest = withSignature(manifest(expected, "", "key", "8.1.0", "8.0.0", "linux", "x64"), pair);
        UpdateVerificationService service = new UpdateVerificationService(id -> Optional.of(pair.getPublic()));

        Path wrongLength = temp.resolve("wrong-length.bin");
        Files.writeString(wrongLength, "x");
        assertThatThrownBy(() -> service.verifyArtifact(wrongLength, manifest))
                .isInstanceOf(SecurityException.class).hasMessageContaining("length");

        Path wrongHash = temp.resolve("wrong-hash.bin");
        Files.write(wrongHash, "bad-data".getBytes(StandardCharsets.UTF_8));
        ReleaseManifest sameLength = withSignature(manifest("bad-data".getBytes(StandardCharsets.UTF_8), sha256(expected), "key", "8.1.0", "8.0.0", "linux", "x64"), pair);
        assertThatThrownBy(() -> service.verifyArtifact(wrongHash, sameLength))
                .isInstanceOf(SecurityException.class).hasMessageContaining("SHA-256");
    }

    @Test
    void blocksUpdaterOlderThanMinimumAndNonHttpsArtifact() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] bytes = "candidate".getBytes(StandardCharsets.UTF_8);
        ReleaseManifest manifest = withSignature(manifest(bytes, "", "key", "8.1.0", "8.1.0", "linux", "x64"), pair);
        UpdateVerificationService service = new UpdateVerificationService(id -> Optional.of(pair.getPublic()));
        assertThatThrownBy(() -> service.verifyManifest(manifest, "myhomelib", "8.0.0", "8.0.0", "linux", "x64", false))
                .isInstanceOf(SecurityException.class).hasMessageContaining("too old");

        ReleaseManifest http = new ReleaseManifest("myhomelib", "8.1.0", UpdateChannel.STABLE, "linux", "x64",
                URI.create("http://example.invalid/app.bin"), bytes.length, sha256(bytes), "AA==", "key", "8.0.0", "");
        assertThatThrownBy(() -> service.verifyManifest(http, "myhomelib", "8.0.0", "8.0.0", "linux", "x64", false))
                .isInstanceOf(SecurityException.class).hasMessageContaining("HTTPS");
    }

    private static ReleaseManifest manifest(byte[] bytes, String shaOverride, String keyId, String version,
                                            String minimumUpdater, String platform, String arch) throws Exception {
        return new ReleaseManifest("myhomelib", version, UpdateChannel.STABLE, platform, arch,
                URI.create("https://updates.example.invalid/myhomelib.bin"), bytes.length,
                shaOverride == null || shaOverride.isBlank() ? sha256(bytes) : shaOverride,
                "AA==", keyId, minimumUpdater, "notes");
    }

    private static ReleaseManifest withSignature(ReleaseManifest manifest, KeyPair pair) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(pair.getPrivate());
        signature.update(manifest.canonicalSignedPayload());
        return new ReleaseManifest(manifest.productId(), manifest.version(), manifest.channel(), manifest.platform(), manifest.arch(),
                manifest.artifactUri(), manifest.artifactLength(), manifest.sha256(), Base64.getEncoder().encodeToString(signature.sign()),
                manifest.keyId(), manifest.minimumUpdaterVersion(), manifest.releaseNotes());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
