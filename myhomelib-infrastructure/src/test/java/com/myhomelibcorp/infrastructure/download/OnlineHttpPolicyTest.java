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
