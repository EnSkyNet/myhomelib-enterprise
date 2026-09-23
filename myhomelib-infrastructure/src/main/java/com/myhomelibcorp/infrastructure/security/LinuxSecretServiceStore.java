package com.myhomelibcorp.infrastructure.security;

import com.myhomelibcorp.shared.security.SecretStore;
import com.myhomelibcorp.shared.security.SecretStoreException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** freedesktop Secret Service adapter via the standard secret-tool CLI. */
final class LinuxSecretServiceStore implements SecretStore {
    private static final List<String> BASE_ATTRS = List.of("application", "myhomelib", "key");

    @Override
    public Optional<String> read(String key) {
        var command = new ArrayList<String>();
        command.add("secret-tool");
        command.add("lookup");
        command.addAll(attributesFor(key));
        var result = CommandSecretStoreSupport.run(command, null);
        if (result.exitCode() == 0) return result.stdout().isBlank() ? Optional.empty() : Optional.of(result.stdout());
        if (CommandSecretStoreSupport.looksUnavailable(result)) {
            throw new SecretStoreException("Linux Secret Service is unavailable: " + result.stderr());
        }
        return Optional.empty();
    }

    @Override
    public void write(String key, String secret) {
        Objects.requireNonNull(secret, "secret");
        var command = new ArrayList<String>();
        command.add("secret-tool");
        command.add("store");
        command.add("--label=MyHomeLib application secret");
        command.addAll(attributesFor(key));
        var result = CommandSecretStoreSupport.run(command, secret + System.lineSeparator());
        if (result.exitCode() != 0) {
            throw new SecretStoreException("Linux Secret Service could not store the requested secret: " + result.stderr());
        }
    }

    @Override
    public void delete(String key) {
        var command = new ArrayList<String>();
        command.add("secret-tool");
        command.add("clear");
        command.addAll(attributesFor(key));
        var result = CommandSecretStoreSupport.run(command, null);
        if (result.exitCode() != 0 && CommandSecretStoreSupport.looksUnavailable(result)) {
            throw new SecretStoreException("Linux Secret Service is unavailable: " + result.stderr());
        }
    }

    @Override
    public String backendId() {
        return "linux-secret-service";
    }

    /** The historical master-key slot already used the same key attribute, so it stays compatible. */
    static List<String> attributesFor(String key) {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) throw new IllegalArgumentException("Secret key must not be blank");
        ArrayList<String> attrs = new ArrayList<>(BASE_ATTRS);
        attrs.add(key);
        return List.copyOf(attrs);
    }
}
