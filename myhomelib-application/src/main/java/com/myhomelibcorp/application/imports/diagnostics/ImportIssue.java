package com.myhomelibcorp.application.imports.diagnostics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Structured diagnostic with bounded text and context suitable for logs and UI summaries. */
public record ImportIssue(
        ImportSeverity severity,
        String stage,
        String code,
        String sourceRecord,
        String message,
        boolean retryable,
        Map<String, String> context
) {
    private static final int MAX_STAGE_LENGTH = 64;
    private static final int MAX_CODE_LENGTH = 96;
    private static final int MAX_SOURCE_RECORD_LENGTH = 160;
    private static final int MAX_MESSAGE_LENGTH = 512;
    private static final int MAX_CONTEXT_ENTRIES = 16;
    private static final int MAX_CONTEXT_KEY_LENGTH = 64;
    private static final int MAX_CONTEXT_VALUE_LENGTH = 256;

    public ImportIssue {
        severity = severity == null ? ImportSeverity.WARNING : severity;
        stage = bounded(stage, MAX_STAGE_LENGTH, "import");
        code = bounded(code, MAX_CODE_LENGTH, "UNKNOWN");
        sourceRecord = bounded(sourceRecord, MAX_SOURCE_RECORD_LENGTH, "");
        message = bounded(message, MAX_MESSAGE_LENGTH, "");
        context = boundedContext(context);
    }

    private static Map<String, String> boundedContext(Map<String, String> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, String> bounded = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (bounded.size() >= MAX_CONTEXT_ENTRIES) break;
            String key = bounded(entry.getKey(), MAX_CONTEXT_KEY_LENGTH, "");
            if (key.isBlank()) continue;
            bounded.putIfAbsent(key, bounded(entry.getValue(), MAX_CONTEXT_VALUE_LENGTH, ""));
        }
        return Collections.unmodifiableMap(bounded);
    }

    private static String bounded(String value, int maxLength, String fallback) {
        if (value == null) return fallback;
        String normalized = value.replaceAll("[\\p{Cc}]", " ").trim();
        if (normalized.length() <= maxLength) return normalized;
        if (maxLength <= 1) return normalized.substring(0, maxLength);
        return normalized.substring(0, maxLength - 1) + "…";
    }
}
