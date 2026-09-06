package com.myhomelibcorp.infrastructure.security;

import com.myhomelibcorp.shared.security.SecretStoreContext;
import com.myhomelibcorp.shared.security.SecretStoreProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformSecretStoreProviderTest {
    @TempDir Path tempDir;

    @Test
    void serviceLoaderDiscoversInfrastructureProvider() {
        assertThat(ServiceLoader.load(SecretStoreProvider.class).stream()
                .map(ServiceLoader.Provider::type)
                .toList())
                .contains(PlatformSecretStoreProvider.class);
    }

    @Test
    void windowsInstalledContextSelectsDpapiAndPortableContextDisablesNativeStore() {
        PlatformSecretStoreProvider provider = new PlatformSecretStoreProvider();

        var installed = provider.open(new SecretStoreContext(tempDir, false, "Windows 11", "tester"));
        var portable = provider.open(new SecretStoreContext(tempDir, true, "Windows 11", "tester"));

        assertThat(installed).isPresent();
        assertThat(installed.orElseThrow().backendId()).isEqualTo("windows-dpapi-current-user");
        assertThat(portable).isEmpty();
    }
}
