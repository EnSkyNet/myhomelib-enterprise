package com.myhomelibcorp.application.translation;

import java.util.Locale;

/** Explicit translation request. Construction alone never invokes a provider. */
public record TranslationQuery(String text, String sourceLanguage, String targetLanguage) {
    public static final int MAX_TEXT_LENGTH = 12_000;

    public TranslationQuery {
        text = normalizeText(text);
        sourceLanguage = normalizeLanguage(sourceLanguage);
        targetLanguage = normalizeLanguage(targetLanguage);
        if (text.isBlank()) throw new IllegalArgumentException("Translation text is required");
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Translation text exceeds " + MAX_TEXT_LENGTH + " characters");
        }
        if (targetLanguage.isBlank()) throw new IllegalArgumentException("Target language is required");
        if (!sourceLanguage.isBlank() && sourceLanguage.equals(targetLanguage)) {
            throw new IllegalArgumentException("Source and target languages must differ");
        }
    }

    public static TranslationQuery autoDetect(String text, String targetLanguage) {
        return new TranslationQuery(text, "", targetLanguage);
    }

    private static String normalizeText(String value) {
        if (value == null) return "";
        return value.strip().replaceAll("[\\t\\x0B\\f\\r ]+", " ");
    }

    private static String normalizeLanguage(String value) {
        if (value == null || value.isBlank()) return "";
        return value.trim().replace('_', '-').toLowerCase(Locale.ROOT);
    }
}
