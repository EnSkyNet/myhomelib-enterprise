package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.progress.OperationStage;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationStageTextTest {
    @Test
    void everyOperationStageHasCanonicalLocalizationKey() {
        assertThat(Arrays.stream(OperationStage.values()).map(OperationStageText::key))
                .doesNotContainNull()
                .allMatch(key -> key.startsWith("ui.import.stage."));
    }

    @Test
    void nullStageIsRejectedInsteadOfSilentlyChoosingContextSpecificText() {
        assertThatThrownBy(() -> OperationStageText.key(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stage");
    }
}
