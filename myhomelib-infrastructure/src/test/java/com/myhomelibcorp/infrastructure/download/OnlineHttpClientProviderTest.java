package com.myhomelibcorp.infrastructure.download;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.junit.jupiter.api.Test;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OnlineHttpClientProviderTest {

    @Test
    void reusesOneStatelessClientButKeepsCookieSessionsIsolated() {
        OnlineHttpClientProvider provider = new OnlineHttpClientProvider(defaults());

        HttpClient first = provider.shared();
        HttpClient second = provider.shared();
        HttpClient cookieClient = provider.isolated(new CookieManager(null, CookiePolicy.ACCEPT_ALL));

        assertThat(second).isSameAs(first);
        assertThat(cookieClient).isNotSameAs(first);
        assertThat(first.cookieHandler()).isEmpty();
        assertThat(cookieClient.cookieHandler()).isPresent();
    }

    private static ApplicationSettingsPort defaults() {
        ApplicationSettingsPort settings = mock(ApplicationSettingsPort.class);
        when(settings.getInt(anyString(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(settings.get(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        when(settings.get("online.proxy.mode", "SYSTEM")).thenReturn("NONE");
        return settings;
    }
}
