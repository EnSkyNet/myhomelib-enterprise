package com.myhomelibcorp.infrastructure.sync.webdav;

import com.myhomelibcorp.shared.security.SecretStore;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Stores WebDAV credentials and sync-encryption keys only in a SecretStore. */
public final class WebDavCredentialStore {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretStore secretStore;
    private final String prefix;

    public WebDavCredentialStore(SecretStore secretStore, URI endpoint) {
        this.secretStore = Objects.requireNonNull(secretStore, "secretStore");
        this.prefix = "myhomelib.sync.webdav." + endpointFingerprint(endpoint) + ".";
    }

    public WebDavSyncSecrets createAndSave(String username, String password) {
        WebDavSyncSecrets secrets = new WebDavSyncSecrets(username, password, randomKey(), "");
        save(secrets);
        return secrets;
    }

    /** Saves an imported/shared key so another device can decrypt the same remote bundles. */
    public void save(WebDavSyncSecrets secrets) {
        Objects.requireNonNull(secrets, "secrets");
        Map<String, Optional<String>> before = snapshot();
        try {
            secretStore.write(key("username"), secrets.username());
            secretStore.write(key("password"), secrets.password());
            secretStore.write(key("sync-key"), secrets.syncKeyBase64());
            if (secrets.rotationInProgress()) {
                secretStore.write(key("sync-key-previous"), secrets.previousSyncKeyBase64());
            } else {
                secretStore.delete(key("sync-key-previous"));
            }
        } catch (RuntimeException | Error failure) {
            restore(before, failure);
            throw failure;
        }
    }

    public Optional<WebDavSyncSecrets> load() {
        Optional<String> username = secretStore.read(key("username"));
        Optional<String> password = secretStore.read(key("password"));
        Optional<String> syncKey = secretStore.read(key("sync-key"));
        Optional<String> previous = secretStore.read(key("sync-key-previous"));
        if (username.isEmpty() && password.isEmpty() && syncKey.isEmpty() && previous.isEmpty()) return Optional.empty();
        if (username.isEmpty() || password.isEmpty() || syncKey.isEmpty()) {
            throw new IllegalStateException("Incomplete WebDAV secrets in " + secretStore.backendId());
        }
        return Optional.of(new WebDavSyncSecrets(username.orElseThrow(), password.orElseThrow(),
                syncKey.orElseThrow(), previous.orElse("")));
    }

    /** Starts one-generation key rotation. The previous key remains available until migration completes. */
    public WebDavSyncSecrets rotateSyncKey() {
        WebDavSyncSecrets current = load().orElseThrow(() -> new IllegalStateException("WebDAV secrets are unavailable"));
        if (current.rotationInProgress()) {
            throw new IllegalStateException("Complete the current sync-key migration before rotating again");
        }
        WebDavSyncSecrets rotated = new WebDavSyncSecrets(current.username(), current.password(),
                randomKey(), current.syncKeyBase64());
        save(rotated);
        return rotated;
    }

    /** Drops the previous key only after every remote bundle has been rewrapped with the active key. */
    public WebDavSyncSecrets completeSyncKeyRotation() {
        WebDavSyncSecrets current = load().orElseThrow(() -> new IllegalStateException("WebDAV secrets are unavailable"));
        WebDavSyncSecrets completed = new WebDavSyncSecrets(current.username(), current.password(),
                current.syncKeyBase64(), "");
        save(completed);
        return completed;
    }

    /** Deletes all credentials atomically from the caller's point of view; partial native failures are compensated. */
    public void delete() {
        Map<String, Optional<String>> before = snapshot();
        try {
            for (String key : keys()) secretStore.delete(key);
        } catch (RuntimeException | Error failure) {
            restore(before, failure);
            throw failure;
        }
    }

    private Map<String, Optional<String>> snapshot() {
        LinkedHashMap<String, Optional<String>> values = new LinkedHashMap<>();
        for (String key : keys()) values.put(key, secretStore.read(key));
        return Map.copyOf(values);
    }

    private void restore(Map<String, Optional<String>> before, Throwable originalFailure) {
        for (String key : keys()) {
            try {
                Optional<String> previous = before.getOrDefault(key, Optional.empty());
                if (previous.isPresent()) secretStore.write(key, previous.orElseThrow());
                else secretStore.delete(key);
            } catch (RuntimeException | Error rollbackFailure) {
                originalFailure.addSuppressed(rollbackFailure);
            }
        }
    }

    private List<String> keys() {
        return List.of(key("username"), key("password"), key("sync-key"), key("sync-key-previous"));
    }

    private String key(String suffix) {
        return prefix + suffix;
    }

    private static String randomKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static String endpointFingerprint(URI endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        URI normalized = endpoint.normalize();
        String authority = normalized.getScheme() + "://" + normalized.getAuthority() + normalized.getPath();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(authority.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
