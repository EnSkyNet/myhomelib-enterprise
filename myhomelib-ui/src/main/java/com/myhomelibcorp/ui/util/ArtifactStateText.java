package com.myhomelibcorp.ui.util;

import java.util.Locale;

/** Stable user-facing source labels for stored artifact-state codes. */
public final class ArtifactStateText {
    private ArtifactStateText() {}

    public static String sourceLabel(String state, boolean local) {
        String normalized = state == null ? "" : state.trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) normalized = local ? "AVAILABLE" : "REMOTE_ONLY";
        return switch (normalized) {
            case "AVAILABLE" -> "Доступний";
            case "REMOTE_ONLY" -> "Лише віддалено";
            case "MISSING" -> "Файл відсутній";
            case "CORRUPT" -> "Пошкоджений";
            case "UNAVAILABLE" -> "Недоступний";
            default -> "Невідомий стан";
        };
    }
}
