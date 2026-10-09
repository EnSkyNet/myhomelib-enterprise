package com.myhomelibcorp.organizer.detect;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class DetectedBookFormatLocaleTest {
    @Test
    void technicalFormatNormalizationIsIndependentOfDefaultLocale() {
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            DetectedBookFormat detected = new DetectedBookFormat(
                    "ZIP", "application/zip", DetectionConfidence.HIGH, "ZIP", true, "test");
            assertThat(detected.format()).isEqualTo("zip");
            assertThat(detected.extension()).isEqualTo("zip");
        } finally {
            Locale.setDefault(previous);
        }
    }
}
