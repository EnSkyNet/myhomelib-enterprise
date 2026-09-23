package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalizationServiceJvmLocaleTest {
    @Test
    void selectedUiLanguageAlsoDrivesJavaFxDefaultLocale() {
        Locale before = Locale.getDefault();
        try {
            ApplicationSettingsPort settings = mock(ApplicationSettingsPort.class);
            LanguageCatalogService catalogs = mock(LanguageCatalogService.class);
            when(catalogs.hasLanguage(anyString())).thenReturn(true);
            when(catalogs.fallbackLanguage()).thenReturn("uk");
            when(catalogs.translations(anyString())).thenReturn(Optional.of(Map.of("common.ok", "Гаразд")));
            when(settings.get("ui.language", "uk")).thenReturn("uk");

            LocalizationService service = new LocalizationService(settings, catalogs);
            // The language.txt file intentionally has precedence over settings in production,
            // so drive this test through the public setter instead of depending on the user's
            // existing config directory.
            service.setLanguage("uk");
            assertThat(service.language()).isEqualTo("uk");
            assertThat(Locale.getDefault().getLanguage()).isEqualTo("uk");

            service.setLanguage("bg");
            assertThat(Locale.getDefault().getLanguage()).isEqualTo("bg");
        } finally {
            Locale.setDefault(before);
        }
    }
}
