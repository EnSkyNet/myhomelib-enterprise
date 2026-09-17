package com.myhomelibcorp.ui.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactStateTextTest {
    @Test
    void mapsStoredCodesToUkrainianSourceLabelsWithoutLeakingEnums() {
        assertThat(ArtifactStateText.sourceLabel("AVAILABLE", true)).isEqualTo("Доступний");
        assertThat(ArtifactStateText.sourceLabel("REMOTE_ONLY", false)).isEqualTo("Лише віддалено");
        assertThat(ArtifactStateText.sourceLabel("MISSING", true)).isEqualTo("Файл відсутній");
        assertThat(ArtifactStateText.sourceLabel("CORRUPT", true)).isEqualTo("Пошкоджений");
        assertThat(ArtifactStateText.sourceLabel("UNAVAILABLE", false)).isEqualTo("Недоступний");
        assertThat(ArtifactStateText.sourceLabel(null, true)).isEqualTo("Доступний");
        assertThat(ArtifactStateText.sourceLabel(null, false)).isEqualTo("Лише віддалено");
        assertThat(ArtifactStateText.sourceLabel("future_state", false)).isEqualTo("Невідомий стан");
    }
}
