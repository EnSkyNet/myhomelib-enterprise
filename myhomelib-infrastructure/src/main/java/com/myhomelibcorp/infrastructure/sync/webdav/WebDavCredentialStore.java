package com.myhomelibcorp.infrastructure.sync.webdav;

import com.myhomelibcorp.shared.security.SecretStore;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
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
        secretStore.write(prefix + "username", secrets.username());
        secretStore.write(prefix + "password", secrets.password());
        secretStore.write(prefix + "sync-key", secrets.syncKeyBase64());
        if (secrets.rotationInProgress()) {
            secretStore.write(prefix + "sync-key-previous", secrets.previousSyncKeyBase64());
        } else {
            secretStore.delete(prefix + "sync-key-previous");
        }
    }

    public Optional<WebDavSyncSecrets> load() {
        Optional<String> username = secretStore.read(prefix + "username");
        Optional<String> password = secretStore.read(prefix + "password");
        Optional<String> syncKey = secretStore.read(prefix + "sync-key");
        Optional<String> previous = secretStore.read(prefix + "sync-key-previous");
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

    public void delete() {
        secretStore.delete(prefix + "username");
        secretStore.delete(prefix + "password");
        secretStore.delete(prefix + "sync-key");
        secretStore.delete(prefix + "sync-key-previous");
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
