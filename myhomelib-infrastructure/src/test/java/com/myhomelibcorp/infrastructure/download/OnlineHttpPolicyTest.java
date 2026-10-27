package com.myhomelibcorp.infrastructure.download;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.shared.util.EncryptionUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OnlineHttpPolicyTest {
    @TempDir Path temp;

    @Test
    void scriptedLegacyTransfersPreferHttp11WithoutChangingStatelessHttpClients() {
        OnlineHttpPolicy policy = new OnlineHttpPolicy(defaults());
        java.net.CookieManager cookies = new java.net.CookieManager();
        try (var scripted = policy.createConnectionScript(cookies);
             var shared = policy.create(null)) {
            org.assertj.core.api.Assertions.assertThat(scripted.version())
                    .isEqualTo(java.net.http.HttpClient.Version.HTTP_1_1);
            org.assertj.core.api.Assertions.assertThat(scripted.cookieHandler()).contains(cookies);
            org.assertj.core.api.Assertions.assertThat(shared.version())
                    .isEqualTo(java.net.http.HttpClient.Version.HTTP_2);
        }
    }

    @Test
    void directAndNoneModesExplicitlyBypassDefaultProxySelector() {
        for (String mode : java.util.List.of("DIRECT", "NONE")) {
            ApplicationSettingsPort settings = defaults();
            when(settings.get("online.proxy.mode", "SYSTEM")).thenReturn(mode);
            try (var client = new OnlineHttpPolicy(settings).create(null)) {
                org.assertj.core.api.Assertions.assertThat(client.proxy()).isPresent();
                org.assertj.core.api.Assertions.assertThat(
                        client.proxy().orElseThrow().select(java.net.URI.create("https://books.example/")))
                        .containsExactly(java.net.Proxy.NO_PROXY);
            }
        }
    }

    @Test
    void systemProxyModePreservesJvmDefaultProxySelector() {
        ApplicationSettingsPort settings = defaults();
        when(settings.get("online.proxy.mode", "SYSTEM")).thenReturn("SYSTEM");
        java.net.ProxySelector defaultSelector = java.net.ProxySelector.getDefault();
        try (var client = new OnlineHttpPolicy(settings).create(null)) {
            if (defaultSelector != null) {
                org.assertj.core.api.Assertions.assertThat(client.proxy()).contains(defaultSelector);
            }
        }
    }

    @Test
    void plaintextProxyPasswordIsRejected() {
        ApplicationSettingsPort settings = defaults();
        when(settings.get("online.proxy.mode", "SYSTEM")).thenReturn("HTTP");
        when(settings.get("online.proxy.host", "")).thenReturn("127.0.0.1");
        when(settings.get("online.proxy.user", "")).thenReturn("user");
        when(settings.get("online.proxy.password", "")).thenReturn("plaintext-secret");

        assertThatThrownBy(() -> new OnlineHttpPolicy(settings).create(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("plaintext credentials");
    }

    @Test
    void explicitPkcs12TrustStoreUsesEncryptedPasswordWithoutTrustAll() throws Exception {
        char[] password = "changeit-test".toCharArray();
        Path trustStore = temp.resolve("custom.p12");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, password);
        try (OutputStream out = Files.newOutputStream(trustStore)) { ks.store(out, password); }

        ApplicationSettingsPort settings = defaults();
        when(settings.get("online.tls.trustStore", "")).thenReturn(trustStore.toString());
        when(settings.get("online.tls.trustStoreType", "PKCS12")).thenReturn("PKCS12");
        when(settings.get("online.tls.trustStorePassword", "")).thenReturn(EncryptionUtil.encrypt("changeit-test"));

        assertThatCode(() -> new OnlineHttpPolicy(settings).create(null)).doesNotThrowAnyException();
    }

    @Test
    void plaintextTrustStorePasswordIsRejected() throws Exception {
        Path trustStore = temp.resolve("custom.p12");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, "secret".toCharArray());
        try (OutputStream out = Files.newOutputStream(trustStore)) { ks.store(out, "secret".toCharArray()); }

        ApplicationSettingsPort settings = defaults();
        when(settings.get("online.tls.trustStore", "")).thenReturn(trustStore.toString());
        when(settings.get("online.tls.trustStoreType", "PKCS12")).thenReturn("PKCS12");
        when(settings.get("online.tls.trustStorePassword", "")).thenReturn("secret");

        assertThatThrownBy(() -> new OnlineHttpPolicy(settings).create(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trust store");
    }

    private static ApplicationSettingsPort defaults() {
        ApplicationSettingsPort settings = mock(ApplicationSettingsPort.class);
        when(settings.getInt(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(settings.get(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.get("online.proxy.mode", "SYSTEM")).thenReturn("NONE");
        return settings;
    }
}
