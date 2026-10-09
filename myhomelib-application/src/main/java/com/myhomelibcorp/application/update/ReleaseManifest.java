package com.myhomelibcorp.application.update;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public record ReleaseManifest(
        String productId, String version, UpdateChannel channel, String platform, String arch,
        URI artifactUri, long artifactLength, String sha256, String signature, String keyId,
        String minimumUpdaterVersion, String releaseNotes) {
    public ReleaseManifest {
        productId = required(productId, "productId");
        version = required(version, "version");
        channel = Objects.requireNonNull(channel, "channel");
        platform = required(platform, "platform");
        arch = required(arch, "arch");
        artifactUri = Objects.requireNonNull(artifactUri, "artifactUri");
        if (artifactLength <= 0 || artifactLength > 2_147_483_648L) throw new IllegalArgumentException("artifactLength out of bounds");
        sha256 = required(sha256, "sha256").toLowerCase(java.util.Locale.ROOT);
        if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid SHA-256");
        signature = required(signature, "signature");
        keyId = required(keyId, "keyId");
        minimumUpdaterVersion = required(minimumUpdaterVersion, "minimumUpdaterVersion");
        releaseNotes = releaseNotes == null ? "" : releaseNotes;
    }
    public byte[] canonicalSignedPayload() {
        String value = String.join("\n", productId, version, channel.name(), platform, arch,
                artifactUri.toASCIIString(), Long.toString(artifactLength), sha256, keyId, minimumUpdaterVersion);
        return value.getBytes(StandardCharsets.UTF_8);
    }
    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
