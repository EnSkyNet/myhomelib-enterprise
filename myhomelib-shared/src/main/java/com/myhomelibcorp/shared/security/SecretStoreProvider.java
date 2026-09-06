package com.myhomelibcorp.shared.security;

import java.util.Optional;

/** ServiceLoader SPI for platform secret stores. */
public interface SecretStoreProvider {
    int priority();
    Optional<SecretStore> open(SecretStoreContext context);
}
