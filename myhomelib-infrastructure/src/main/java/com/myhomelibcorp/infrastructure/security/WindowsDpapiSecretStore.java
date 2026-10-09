package com.myhomelibcorp.infrastructure.security;

import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.shared.security.CredentialMasterKeyManager;
import com.myhomelibcorp.shared.security.SecretStore;
import com.myhomelibcorp.shared.security.SecretStoreException;
import com.myhomelibcorp.shared.util.BoundedIoSupport;
import com.sun.jna.platform.win32.Crypt32Util;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/** Windows CurrentUser DPAPI store. Files beside config contain only DPAPI-protected blobs. */
final class WindowsDpapiSecretStore implements SecretStore {
    /** Keep the historical master-key filename for backward compatibility. */
    private static final String LEGACY_MASTER_KEY_FILE = "credential-key.dpapi";
    private static final String KEYED_FILE_PREFIX = "credential-secret-v1-";
    private static final int MAX_BLOB_FILE_BYTES = 64 * 1024;

    private final Path configDir;

    WindowsDpapiSecretStore(Path configDir) {
        this.configDir = Objects.requireNonNull(configDir, "configDir").toAbsolutePath().normalize();
    }

    @Override
    public Optional<String> read(String key) {
        Path blobFile = blobFile(key);
        if (!Files.exists(blobFile, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try {
            Path safe = SafeFilePublication.requireSafeRegularFile(blobFile, "Windows DPAPI secret blob");
            String encoded;
            try (var in = Files.newInputStream(safe, LinkOption.NOFOLLOW_LINKS)) {
                encoded = new String(BoundedIoSupport.readFully(in, MAX_BLOB_FILE_BYTES), StandardCharsets.US_ASCII).trim();
            }
            byte[] protectedBytes = Base64.getDecoder().decode(encoded);
            byte[] plain = Crypt32Util.cryptUnprotectData(protectedBytes);
            return Optional.of(new String(plain, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new SecretStoreException("Windows DPAPI could not decrypt the requested secret", e);
        }
    }

    @Override
    public void write(String key, String secret) {
        Objects.requireNonNull(secret, "secret");
        Path blobFile = blobFile(key);
        Path workspace = null;
        try {
            Path parent = SafeFilePublication.createDirectoriesNoLinks(
                    blobFile.getParent(), "Windows DPAPI secret directory");
            String expectedTargetHash = SafeFilePublication.snapshotExistingTarget(
                    blobFile, "Windows DPAPI secret blob");
            byte[] protectedBytes = Crypt32Util.cryptProtectData(secret.getBytes(StandardCharsets.UTF_8));
            String encoded = Base64.getEncoder().encodeToString(protectedBytes) + System.lineSeparator();
            workspace = SafeFilePublication.createPrivateWorkspace(
                    parent, ".credential-dpapi-work-", "Windows DPAPI secret workspace");
            Path temp = workspace.resolve(blobFile.getFileName().toString());
            try (var out = SafeFilePublication.openFreshOutputStream(temp, "Windows DPAPI staged secret blob")) {
                out.write(encoded.getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
            SafeFilePublication.publishReplacingIfUnchanged(
                    temp, blobFile, expectedTargetHash, "Windows DPAPI secret publication");
        } catch (Exception e) {
            throw new SecretStoreException("Windows DPAPI could not protect the requested secret", e);
        } finally {
            if (workspace != null) {
                try {
                    SafeFilePublication.cleanupPrivateWorkspace(workspace);
                } catch (Exception cleanup) {
                    // A failed cleanup must never trigger deletion outside the verified private workspace.
                }
            }
        }
    }

    @Override
    public void delete(String key) {
        Path blobFile = blobFile(key);
        try {
            String expectedTargetHash = SafeFilePublication.snapshotExistingTarget(
                    blobFile, "Windows DPAPI secret blob deletion");
            if (expectedTargetHash != null) {
                SafeFilePublication.removeIfOwned(
                        blobFile, expectedTargetHash, "Windows DPAPI secret blob deletion");
            }
        } catch (Exception e) {
            throw new SecretStoreException("Windows DPAPI secret blob could not be removed", e);
        }
    }

    @Override
    public String backendId() {
        return "windows-dpapi-current-user";
    }

    private Path blobFile(String key) {
        return configDir.resolve(blobFileNameFor(key));
    }

    /** Visible to package tests so slot isolation can be verified without invoking Windows DPAPI. */
    static String blobFileNameFor(String key) {
        String validated = requireLogicalKey(key);
        if (CredentialMasterKeyManager.SECRET_NAME.equals(validated)) return LEGACY_MASTER_KEY_FILE;
        return KEYED_FILE_PREFIX + Utf8HashSupport.sha256(validated) + ".dpapi";
    }

    private static String requireLogicalKey(String key) {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) throw new IllegalArgumentException("Secret key must not be blank");
        return key;
    }
}
