package com.myhomelibcorp.shared.security;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.KeyGenerator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Supplier;

/** Resolves the installation AES master key through a platform SecretStore with a controlled file fallback. */
@Slf4j
public final class CredentialMasterKeyManager {
    public static final String SECRET_NAME = "credential-master-key-v1";
    public static final String LOCAL_KEY_FILE = "credential-key.aes256";
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
        if (Files.isRegularFile(localKey)) {
            String legacy = readAndValidate(localKey);
            store.write(SECRET_NAME, legacy);
            String roundTrip = store.read(SECRET_NAME)
                    .orElseThrow(() -> new SecretStoreException("Native store did not return the migrated master key"));
            if (!legacy.equals(roundTrip)) {
                throw new SecretStoreException("Native store verification failed after master-key migration");
            }
            deleteLegacyKey(localKey);
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
            Files.createDirectories(keyFile.getParent());
            if (Files.isRegularFile(keyFile)) return readAndValidate(keyFile);

            String generated = validateKey(keyGenerator.get(), "generated master key");
            Path temp = Files.createTempFile(keyFile.getParent(), ".credential-key-", ".tmp");
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
            return validateKey(Files.readString(path, StandardCharsets.US_ASCII).trim(), path.toString());
        } catch (Exception e) {
            if (e instanceof SecretStoreException sse) throw sse;
            throw new SecretStoreException("Cannot read credential master key from " + path, e);
        }
    }

    private static void deleteLegacyKey(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception e) {
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
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            }
        } catch (Exception e) {
            log.warn("Could not restrict credential-key permissions on {}: {}", path, e.getMessage());
        }
    }
}
