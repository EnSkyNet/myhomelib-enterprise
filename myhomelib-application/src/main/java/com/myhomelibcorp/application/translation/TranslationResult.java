package com.myhomelibcorp.application.translation;

/** Normalized translation result safe for reader/UI consumption. */
public record TranslationResult(
        String providerId,
        String providerName,
        String sourceLanguage,
        String targetLanguage,
        String translatedText
) {
    public TranslationResult {
        providerId = required(providerId, "providerId");
        providerName = required(providerName, "providerName");
        sourceLanguage = clean(sourceLanguage);
        targetLanguage = required(targetLanguage, "targetLanguage");
        translatedText = required(translatedText, "translatedText");
    }

    private static String required(String value, String field) {
        String normalized = clean(value);
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
