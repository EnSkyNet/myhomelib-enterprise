package com.myhomelibcorp.ui.annotation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotationCsvSafetyTest {
    @Test
    void neutralizesSpreadsheetFormulaMarkersWithoutChangingOrdinaryText() {
        assertThat(AnnotationManagerWorkspaceController.csv("=HYPERLINK(\"https://example.test\")"))
                .isEqualTo("\"'=HYPERLINK(\"\"https://example.test\"\")\"");
        assertThat(AnnotationManagerWorkspaceController.csv("  +SUM(1,2)"))
                .isEqualTo("\"  '+SUM(1,2)\"");
        assertThat(AnnotationManagerWorkspaceController.csv("ordinary quote"))
                .isEqualTo("\"ordinary quote\"");
    }
}
