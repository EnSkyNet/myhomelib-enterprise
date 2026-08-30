package com.myhomelibcorp.application.imports.diagnostics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Structured, bounded diagnostic emitted by download/validation/import stages. */
public record ImportIssue(
        ImportSeverity severity,
        String stage,
        String code,
        String sourceRecord,
        String message,
        boolean retryable,
        Map<String, String> context
) {
    public ImportIssue {
        severity = severity == null ? ImportSeverity.WARNING : severity;
        stage = stage == null ? "import" : stage;
        code = code == null ? "UNKNOWN" : code;
        sourceRecord = sourceRecord == null ? "" : sourceRecord;
        message = message == null ? "" : message;
        context = context == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(context));
    }
}
