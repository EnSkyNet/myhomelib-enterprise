package com.myhomelibcorp.shared.security;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.KeyGenerator;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFileAttributeView;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.myhomelibcorp.shared.util.BoundedIoSupport;

/** Resolves the installation AES master key through a platform SecretStore with a controlled file fallback. */
@Slf4j
public final class CredentialMasterKeyManager {
    public static final String SECRET_NAME = "credential-master-key-v1";
    public static final String LOCAL_KEY_FILE = "credential-key.aes256";
    private static final int MAX_LOCAL_KEY_FILE_BYTES = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private CredentialMasterKeyManager() {}

    public static String loadOrCreateDefault(Path configDir, boolean portableMode) {
        SecretStoreContext context = new SecretStoreContext(
                configDir,
                portableMode,
                System.getProperty("os.name", ""),
                System.getProperty("user.name", "unknown"));
        List<SecretStoreProvider> providers = ServiceLoader.load(SecretStoreProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .sorted(Comparator.comparingInt(SecretStoreProvider::priority).reversed())
                .toList();
        boolean requireNative = Boolean.parseBoolean(System.getProperty(
                "myhomelib.secretStore.nativeRequired",
                Boolean.toString(context.isWindows() && !portableMode)));
        return loadOrCreate(context, providers, CredentialMasterKeyManager::generateKey, requireNative);
    }

    static String loadOrCreate(
            SecretStoreContext context,
            List<SecretStoreProvider> providers,
            Supplier<String> keyGenerator,
            boolean requireNative) {
        Path localKey = context.configDir().resolve(LOCAL_KEY_FILE);

        if (context.portableMode()) {
            return loadOrCreateLocal(localKey, keyGenerator);
        }

        Optional<SecretStore> store = openFirst(context, providers);
        if (store.isPresent()) {
            try {
                return loadOrCreateNative(store.get(), localKey, keyGenerator);
            } catch (RuntimeException e) {
                if (requireNative) {
                    throw new SecretStoreException(
                            "Native credential store is unavailable; refusing insecure master-key fallback", e);
                }
                log.warn("Native credential store {} unavailable; using restricted local fallback: {}",
                        store.get().backendId(), e.getMessage());
            }
        } else if (requireNative) {
            throw new SecretStoreException(
                    "No supported native credential store is available; refusing insecure master-key fallback");
        }

        return loadOrCreateLocal(localKey, keyGenerator);
    }

    private static Optional<SecretStore> openFirst(SecretStoreContext context, List<SecretStoreProvider> providers) {
        for (SecretStoreProvider provider : providers) {
            try {
                Optional<SecretStore> store = provider.open(context);
                if (store.isPresent()) return store;
            } catch (RuntimeException e) {
                log.warn("SecretStore provider {} could not initialize: {}",
                        provider.getClass().getSimpleName(), e.getMessage());
            }
        }
        return Optional.empty();
    }

    private static String loadOrCreateNative(SecretStore store, Path localKey, Supplier<String> keyGenerator) {
        // Existing local key always wins during migration because old ciphertext is bound to it.
        if (Files.exists(localKey, LinkOption.NOFOLLOW_LINKS)) {
            String legacyHash = sha256(localKey, "legacy credential master key");
            String legacy = readAndValidate(localKey);
            store.write(SECRET_NAME, legacy);
            String roundTrip = store.read(SECRET_NAME)
                    .orElseThrow(() -> new SecretStoreException("Native store did not return the migrated master key"));
            if (!legacy.equals(roundTrip)) {
                throw new SecretStoreException("Native store verification failed after master-key migration");
            }
            deleteLegacyKey(localKey, legacyHash);
            log.info("Credential master key migrated to native store {}", store.backendId());
            return legacy;
        }

        Optional<String> existing = store.read(SECRET_NAME);
        if (existing.isPresent()) return validateKey(existing.get(), store.backendId());

        String generated = validateKey(keyGenerator.get(), "generated master key");
        store.write(SECRET_NAME, generated);
        String roundTrip = store.read(SECRET_NAME)
                .orElseThrow(() -> new SecretStoreException("Native store did not persist the generated master key"));
        if (!generated.equals(roundTrip)) {
            throw new SecretStoreException("Native store verification failed after master-key creation");
        }
        log.info("Credential master key initialized in native store {}", store.backendId());
        return generated;
    }

    private static String loadOrCreateLocal(Path keyFile, Supplier<String> keyGenerator) {
        try {
            Path parent = createDirectoriesNoLinks(keyFile.getParent(), "credential master-key directory");
            if (Files.exists(keyFile, LinkOption.NOFOLLOW_LINKS)) {
                requireSafeRegularFile(keyFile, "credential master key");
                restrictPermissions(keyFile);
                return readAndValidate(keyFile);
            }

            String generated = validateKey(keyGenerator.get(), "generated master key");
            Path temp = Files.createTempFile(parent, ".credential-key-", ".tmp");
            try {
                Files.writeString(temp, generated + System.lineSeparator(), StandardCharsets.US_ASCII,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                restrictPermissions(temp);
                try {
                    // CREATE/rename without REPLACE semantics. ATOMIC_MOVE is intentionally not used here:
                    // the NIO contract allows implementation-specific replacement when ATOMIC_MOVE is requested.
                    Files.move(temp, keyFile);
                } catch (java.nio.file.FileAlreadyExistsException race) {
                    // Another process won initialization; use its stable key.
                }
                Files.deleteIfExists(temp);
                restrictPermissions(keyFile);
                return readAndValidate(keyFile);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (Exception e) {
            throw new SecretStoreException(
                    "Credential encryption key is unavailable; refusing plaintext credential storage", e);
        }
    }

    private static String readAndValidate(Path path) {
        try {
            Path safe = requireSafeRegularFile(path, "credential master key");
            try (var in = Files.newInputStream(safe, LinkOption.NOFOLLOW_LINKS)) {
                String value = new String(
                        BoundedIoSupport.readFully(in, MAX_LOCAL_KEY_FILE_BYTES),
                        StandardCharsets.US_ASCII).trim();
                return validateKey(value, path.toString());
            }
        } catch (Exception e) {
            if (e instanceof SecretStoreException sse) throw sse;
            throw new SecretStoreException("Cannot read credential master key from " + path, e);
        }
    }

    private static void deleteLegacyKey(Path path, String expectedHash) {
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
            Path safe = requireSafeRegularFile(path, "legacy credential master key");
            String before = sha256(safe, "legacy credential master key");
            if (!expectedHash.equalsIgnoreCase(before)) {
                throw new SecretStoreException(
                        "Native migration succeeded but plaintext legacy key changed before cleanup");
            }

            Path evidence = safe.getParent().resolve(
                    "." + safe.getFileName() + ".mhl-delete-" + UUID.randomUUID() + ".tmp");
            Files.move(safe, evidence);
            String after = sha256(evidence, "detached legacy credential master key");
            if (!expectedHash.equalsIgnoreCase(after)) {
                try {
                    if (!Files.exists(safe, LinkOption.NOFOLLOW_LINKS)) Files.move(evidence, safe);
                } catch (Exception restoreFailure) {
                    SecretStoreException failure = new SecretStoreException(
                            "Native migration succeeded but legacy key changed during cleanup; evidence preserved at "
                                    + evidence);
                    failure.addSuppressed(restoreFailure);
                    throw failure;
                }
                throw new SecretStoreException(
                        "Native migration succeeded but legacy key changed during cleanup");
            }
            Files.delete(evidence);
        } catch (Exception e) {
            if (e instanceof SecretStoreException sse) throw sse;
            throw new SecretStoreException("Native migration succeeded but plaintext legacy key could not be removed", e);
        }
    }

    static String validateKey(String base64, String source) {
        try {
            byte[] decoded = Base64.getDecoder().decode(base64);
            if (decoded.length != 32) throw new IllegalArgumentException("expected 32 bytes, got " + decoded.length);
            return base64;
        } catch (RuntimeException e) {
            throw new SecretStoreException("Invalid " + source + ": AES-256 key must be Base64-encoded 32 bytes", e);
        }
    }

    private static String generateKey() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(256, RANDOM);
            return Base64.getEncoder().encodeToString(keyGen.generateKey().getEncoded());
        } catch (Exception e) {
            throw new SecretStoreException("Cannot generate credential master key", e);
        }
    }

    private static void restrictPermissions(Path path) {
        try {
            Path safe = requireSafeRegularFile(path, "credential master key permission target");
            if (Files.getFileStore(safe).supportsFileAttributeView("posix")) {
                PosixFileAttributeView view = Files.getFileAttributeView(
                        safe, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (view == null) throw new IllegalStateException("POSIX attribute view is unavailable");
                view.setPermissions(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            }
        } catch (Exception e) {
            log.warn("Could not restrict credential-key permissions on {}: {}", path, e.getMessage());
        }
    }

    private static Path createDirectoriesNoLinks(Path directory, String role) throws java.io.IOException {
        if (directory == null) throw new java.io.IOException(role + " has no parent directory");
        Path absolute = directory.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path component : absolute) {
            current = current == null ? component : current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                requireSafeDirectory(current, role);
            } else {
                try {
                    Files.createDirectory(current);
                } catch (FileAlreadyExistsException race) {
                    requireSafeDirectory(current, role);
                }
            }
        }
        return absolute;
    }

    private static Path requireSafeDirectory(Path directory, String role) throws java.io.IOException {
        Path absolute = directory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute)) {
            throw new java.io.IOException(role + " contains a symbolic link: " + absolute);
        }
        BasicFileAttributes attrs = Files.readAttributes(
                absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isDirectory() || attrs.isOther()) {
            throw new java.io.IOException(role + " is not a safe directory: " + absolute);
        }
        return absolute;
    }

    private static Path requireSafeRegularFile(Path file, String role) throws java.io.IOException {
        Path absolute = file.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path component : absolute) {
            current = current == null ? component : current.resolve(component);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new java.io.IOException(role + " does not exist: " + absolute);
            }
            if (Files.isSymbolicLink(current)) {
                throw new java.io.IOException(role + " contains a symbolic link: " + current);
            }
            BasicFileAttributes attrs = Files.readAttributes(
                    current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isOther()) {
                throw new java.io.IOException(role + " contains a special/reparse entry: " + current);
            }
        }
        if (!Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new java.io.IOException(role + " is not a regular file: " + absolute);
        }
        return absolute;
    }

    private static String sha256(Path path, String role) {
        try {
            Path safe = requireSafeRegularFile(path, role);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(safe, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[8192];
                for (int read; (read = in.read(buffer)) >= 0; ) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        } catch (Exception e) {
            if (e instanceof SecretStoreException sse) throw sse;
            throw new SecretStoreException("Cannot hash " + role, e);
        }
    }
}
