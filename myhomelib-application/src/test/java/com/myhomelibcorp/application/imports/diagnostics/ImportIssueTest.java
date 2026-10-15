package com.myhomelibcorp.application.imports.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImportIssueTest {
    @Test
    void boundsTextAndContextFromUntrustedDiagnosticSources() {
        Map<String, String> context = new LinkedHashMap<>();
        context.put("control", "line one\nline two\u0000");
        for (int i = 0; i < 25; i++) context.put("key-" + i, "value-" + i);

        ImportIssue issue = new ImportIssue(ImportSeverity.ERROR,
                "s".repeat(100), "c".repeat(150), "r".repeat(200), "m".repeat(1000),
                false, context);

        assertThat(issue.stage()).hasSize(64);
        assertThat(issue.code()).hasSize(96);
        assertThat(issue.sourceRecord()).hasSize(160);
        assertThat(issue.message()).hasSize(512);
        assertThat(issue.context()).hasSize(16);
        assertThat(issue.context()).containsEntry("control", "line one line two");
        assertThat(issue.context().values()).allSatisfy(value -> assertThat(value).doesNotContain("\n", "\u0000"));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> issue.context().put("x", "y"));
    }

    @Test
    void normalizesNullFieldsAndDefaultsSeverity() {
        ImportIssue issue = new ImportIssue(null, null, null, null, null, false, null);

        assertThat(issue.severity()).isEqualTo(ImportSeverity.WARNING);
        assertThat(issue.stage()).isEqualTo("import");
        assertThat(issue.code()).isEqualTo("UNKNOWN");
        assertThat(issue.sourceRecord()).isEmpty();
        assertThat(issue.message()).isEmpty();
        assertThat(issue.context()).isEmpty();
    }
}
