package com.myhomelibcorp.converter.toolchain;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ConversionStepLocaleTest {
    @Test
    void targetExtensionNormalizationIsIndependentOfDefaultLocale() {
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            ConversionStep step = new ConversionStep(
                    ConversionEngineId.FBC, "fb2", "epub", "ZIP", 1, false, 1);
            assertThat(step.targetExtension()).isEqualTo(".zip");
        } finally {
            Locale.setDefault(previous);
        }
    }
}
