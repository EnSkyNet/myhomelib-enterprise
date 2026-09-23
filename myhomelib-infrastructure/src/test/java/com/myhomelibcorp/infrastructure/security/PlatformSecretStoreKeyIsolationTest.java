package com.myhomelibcorp.infrastructure.security;

import com.myhomelibcorp.shared.security.CredentialMasterKeyManager;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformSecretStoreKeyIsolationTest {

    @Test
    void windowsDpapiMapsDifferentLogicalKeysToDifferentFilesAndKeepsLegacyMasterSlot() {
        String master = WindowsDpapiSecretStore.blobFileNameFor(CredentialMasterKeyManager.SECRET_NAME);
        String username = WindowsDpapiSecretStore.blobFileNameFor("myhomelib.sync.webdav.endpoint.username");
        String password = WindowsDpapiSecretStore.blobFileNameFor("myhomelib.sync.webdav.endpoint.password");

        assertThat(master).isEqualTo("credential-key.dpapi");
        assertThat(username).isNotEqualTo(password).isNotEqualTo(master);
        assertThat(username).startsWith("credential-secret-v1-").endsWith(".dpapi");
        assertThat(password).startsWith("credential-secret-v1-").endsWith(".dpapi");
    }

    @Test
    void linuxSecretServiceUsesLogicalKeyAsSlotAttribute() {
        assertThat(LinuxSecretServiceStore.attributesFor("alpha"))
                .containsExactly("application", "myhomelib", "key", "alpha");
        assertThat(LinuxSecretServiceStore.attributesFor("beta"))
                .containsExactly("application", "myhomelib", "key", "beta");
        assertThat(LinuxSecretServiceStore.attributesFor("alpha"))
                .isNotEqualTo(LinuxSecretServiceStore.attributesFor("beta"));
        assertThat(LinuxSecretServiceStore.attributesFor(CredentialMasterKeyManager.SECRET_NAME))
                .containsExactly("application", "myhomelib", "key", "credential-master-key-v1");
    }

    @Test
    void macKeychainMapsDifferentLogicalKeysToDifferentServicesAndKeepsLegacyMasterService() {
        String master = new String(MacKeychainSecretStore.serviceFor(CredentialMasterKeyManager.SECRET_NAME),
                StandardCharsets.UTF_8);
        String username = new String(MacKeychainSecretStore.serviceFor("myhomelib.sync.webdav.endpoint.username"),
                StandardCharsets.UTF_8);
        String password = new String(MacKeychainSecretStore.serviceFor("myhomelib.sync.webdav.endpoint.password"),
                StandardCharsets.UTF_8);

        assertThat(master).isEqualTo("com.myhomelibcorp.credential-master-key-v1");
        assertThat(username).startsWith("com.myhomelibcorp.secret.v1.");
        assertThat(password).startsWith("com.myhomelibcorp.secret.v1.");
        assertThat(username).isNotEqualTo(password).isNotEqualTo(master);
    }
}
