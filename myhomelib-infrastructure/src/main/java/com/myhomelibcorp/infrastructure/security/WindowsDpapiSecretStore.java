package com.myhomelibcorp.infrastructure.security;

import com.myhomelibcorp.shared.security.SecretStore;
import com.myhomelibcorp.shared.security.SecretStoreException;
import com.sun.jna.platform.win32.Crypt32Util;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Optional;

/** Windows CurrentUser DPAPI store. The file beside config contains only a DPAPI-protected blob. */
final class WindowsDpapiSecretStore implements SecretStore {
    private static final String FILE_NAME = "credential-key.dpapi";
    private final Path blobFile;

    WindowsDpapiSecretStore(Path configDir) {
        this.blobFile = configDir.resolve(FILE_NAME);
    }

    @Override
    public Optional<String> read(String key) {
        if (!Files.isRegularFile(blobFile)) return Optional.empty();
        try {
            byte[] protectedBytes = Base64.getDecoder().decode(Files.readString(blobFile, StandardCharsets.US_ASCII).trim());
            byte[] plain = Crypt32Util.cryptUnprotectData(protectedBytes);
            return Optional.of(new String(plain, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new SecretStoreException("Windows DPAPI could not decrypt the credential master key", e);
        }
    }

    @Override
    public void write(String key, String secret) {
        try {
            Files.createDirectories(blobFile.getParent());
            byte[] protectedBytes = Crypt32Util.cryptProtectData(secret.getBytes(StandardCharsets.UTF_8));
            String encoded = Base64.getEncoder().encodeToString(protectedBytes) + System.lineSeparator();
            Path temp = Files.createTempFile(blobFile.getParent(), ".credential-dpapi-", ".tmp");
            try {
                Files.writeString(temp, encoded, StandardCharsets.US_ASCII,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                try {
                    Files.move(temp, blobFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temp, blobFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (Exception e) {
            throw new SecretStoreException("Windows DPAPI could not protect the credential master key", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(blobFile);
        } catch (Exception e) {
            throw new SecretStoreException("Windows DPAPI secret blob could not be removed", e);
        }
    }

    @Override
    public String backendId() {
        return "windows-dpapi-current-user";
    }
}
