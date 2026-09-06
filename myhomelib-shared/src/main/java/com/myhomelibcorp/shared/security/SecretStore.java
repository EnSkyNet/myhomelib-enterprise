package com.myhomelibcorp.shared.security;

import java.util.Optional;

/**
 * Platform-backed storage for small application secrets.
 * Implementations must never persist the supplied value in plaintext outside the OS secret store.
 */
public interface SecretStore {
    Optional<String> read(String key);
    void write(String key, String secret);
    void delete(String key);
    String backendId();
}
