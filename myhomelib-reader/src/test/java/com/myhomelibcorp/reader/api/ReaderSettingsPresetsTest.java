package com.myhomelibcorp.reader.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderSettingsPresetsTest {
    @Test
    void builtInsProvideUsableNavigationAndStatusDefaults() {
        assertThat(ReaderSettingsPresets.builtIns()).extracting(ReaderSettingsPreset::id)
                .containsExactly("default", "day", "comfortable", "compact", "night");
        assertThat(ReaderSettingsPresets.builtIns()).allSatisfy(p -> {
            assertThat(p.settings().fontSize()).isBetween(10.0, 52.0);
            assertThat(p.settings().tapLeftAction()).isNotBlank();
            assertThat(p.settings().tapRightAction()).isNotBlank();
        });
    }
}
