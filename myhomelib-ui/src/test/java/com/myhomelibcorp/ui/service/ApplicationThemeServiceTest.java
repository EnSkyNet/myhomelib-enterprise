package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.settings.UiPreferenceService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApplicationThemeServiceTest {

    @Test
    void amoledPresetUsesTrueBlackApplicationBackground() {
        UiPreferenceService preferences = mock(UiPreferenceService.class);
        when(preferences.get(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        ApplicationThemeService service = new ApplicationThemeService(preferences);

        ApplicationThemeService.ThemeConfig resolved = service.effective(new ApplicationThemeService.ThemeConfig(
                ApplicationThemeService.ThemeMode.AMOLED,
                "#ffffff", "#ffffff", "#000000", "#0000ff",
                "#ffffff", "#ffffff", "#ffffff", 13.0));

        assertThat(resolved.mode()).isEqualTo(ApplicationThemeService.ThemeMode.AMOLED);
        assertThat(resolved.background()).isEqualTo("#000000");
        assertThat(resolved.panel()).isEqualTo("#050505");
        assertThat(resolved.text()).isEqualTo("#f2f2f2");
        assertThat(resolved.bookRow()).isEqualTo("#000000");
    }
    @Test
    void cyclePresetMovesDarkToAmoledAndPersistsIt() {
        UiPreferenceService preferences = mock(UiPreferenceService.class);
        when(preferences.get(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        ApplicationThemeService service = new ApplicationThemeService(preferences);

        service.apply(new ApplicationThemeService.ThemeConfig(
                ApplicationThemeService.ThemeMode.DARK, "#202124", "#2b2d31", "#e8eaed", "#8ab4f8",
                "#303b4f", "#25272b", "#23442e", 13.0));
        ApplicationThemeService.ThemeConfig cycled = service.cyclePreset();

        assertThat(cycled.mode()).isEqualTo(ApplicationThemeService.ThemeMode.AMOLED);
        assertThat(service.effective(cycled).background()).isEqualTo("#000000");
    }

}
