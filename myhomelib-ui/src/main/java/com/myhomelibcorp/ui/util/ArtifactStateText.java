package com.myhomelibcorp.ui.util;


import com.myhomelibcorp.ui.service.UiText;
import java.util.Locale;

/** Stable user-facing source labels for stored artifact-state codes. */
public final class ArtifactStateText {
    private ArtifactStateText() {}

    public static String sourceLabel(String state, boolean local) {
        String normalized = state == null ? "" : state.trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) normalized = local ? "AVAILABLE" : "REMOTE_ONLY";
        return switch (normalized) {
            case "AVAILABLE" -> UiText.text("ui.java.artifact_state_text.990a30de36");
            case "REMOTE_ONLY" -> UiText.text("ui.java.artifact_state_text.71775e5309");
            case "MISSING" -> UiText.text("ui.java.artifact_state_text.4822718c49");
            case "CORRUPT" -> UiText.text("ui.java.artifact_state_text.8d34371fca");
            case "UNAVAILABLE" -> UiText.text("ui.java.artifact_state_text.8f51f97aa8");
            default -> UiText.text("ui.java.artifact_state_text.f7daf442b7");
        };
    }
}
