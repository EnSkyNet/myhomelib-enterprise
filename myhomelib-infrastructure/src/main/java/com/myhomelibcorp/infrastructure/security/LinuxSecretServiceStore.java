package com.myhomelibcorp.infrastructure.security;

import com.myhomelibcorp.shared.security.SecretStore;
import com.myhomelibcorp.shared.security.SecretStoreException;

import java.util.List;
import java.util.Optional;

/** freedesktop Secret Service adapter via the standard secret-tool CLI. */
final class LinuxSecretServiceStore implements SecretStore {
    private static final List<String> ATTRS = List.of("application", "myhomelib", "key", "credential-master-key-v1");

    @Override
    public Optional<String> read(String key) {
        var command = new java.util.ArrayList<String>();
        command.add("secret-tool");
        command.add("lookup");
        command.addAll(ATTRS);
        var result = CommandSecretStoreSupport.run(command, null);
        if (result.exitCode() == 0) return result.stdout().isBlank() ? Optional.empty() : Optional.of(result.stdout());
        if (CommandSecretStoreSupport.looksUnavailable(result)) {
            throw new SecretStoreException("Linux Secret Service is unavailable: " + result.stderr());
        }
        return Optional.empty();
    }

    @Override
    public void write(String key, String secret) {
        var command = new java.util.ArrayList<String>();
        command.add("secret-tool");
        command.add("store");
        command.add("--label=MyHomeLib credential master key");
        command.addAll(ATTRS);
        var result = CommandSecretStoreSupport.run(command, secret + System.lineSeparator());
        if (result.exitCode() != 0) {
            throw new SecretStoreException("Linux Secret Service could not store the credential master key: " + result.stderr());
        }
    }

    @Override
    public void delete(String key) {
        var command = new java.util.ArrayList<String>();
        command.add("secret-tool");
        command.add("clear");
        command.addAll(ATTRS);
        var result = CommandSecretStoreSupport.run(command, null);
        if (result.exitCode() != 0 && CommandSecretStoreSupport.looksUnavailable(result)) {
            throw new SecretStoreException("Linux Secret Service is unavailable: " + result.stderr());
        }
    }

    @Override
    public String backendId() {
        return "linux-secret-service";
    }
}
