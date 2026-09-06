package com.myhomelibcorp.infrastructure.security;

import com.myhomelibcorp.shared.security.SecretStore;
import com.myhomelibcorp.shared.security.SecretStoreContext;
import com.myhomelibcorp.shared.security.SecretStoreProvider;

import java.util.Optional;

/** Native credential-store provider used through the shared ServiceLoader SPI. */
public final class PlatformSecretStoreProvider implements SecretStoreProvider {
    @Override
    public int priority() {
        return 100;
    }

    @Override
    public Optional<SecretStore> open(SecretStoreContext context) {
        if (context.portableMode()) return Optional.empty();
        if (context.isWindows()) return Optional.of(new WindowsDpapiSecretStore(context.configDir()));
        if (context.isLinux() && CommandSecretStoreSupport.isExecutableOnPath("secret-tool")) {
            return Optional.of(new LinuxSecretServiceStore());
        }
        if (context.isMac()) return Optional.of(new MacKeychainSecretStore(context.userName()));
        return Optional.empty();
    }
}
