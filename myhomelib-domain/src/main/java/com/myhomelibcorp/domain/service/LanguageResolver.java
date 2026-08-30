package com.myhomelibcorp.domain.service;

import com.myhomelibcorp.domain.model.valueobject.LanguageCode;

import java.util.Locale;
import java.util.Map;

/**
 * Normalizes language metadata from catalog sources without inventing a language.
 * Unknown, blank or malformed values resolve to the BCP-47 undetermined code {@code und}.
 */
public final class LanguageResolver {
    private static final LanguageCode UNDETERMINED = LanguageCode.of("und");

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("ua", "uk"),
            Map.entry("ukr", "uk"),
            Map.entry("ukrainian", "uk"),
            Map.entry("українська", "uk"),
            Map.entry("украинский", "uk"),
            Map.entry("rus", "ru"),
            Map.entry("russian", "ru"),
            Map.entry("русский", "ru"),
            Map.entry("рус", "ru"),
            Map.entry("eng", "en"),
            Map.entry("english", "en"),
            Map.entry("английский", "en"),
            Map.entry("deu", "de"),
            Map.entry("ger", "de"),
            Map.entry("german", "de"),
            Map.entry("fra", "fr"),
            Map.entry("fre", "fr"),
            Map.entry("french", "fr"),
            Map.entry("spa", "es"),
            Map.entry("spanish", "es"),
            Map.entry("ita", "it"),
            Map.entry("italian", "it"),
            Map.entry("pol", "pl"),
            Map.entry("polish", "pl"),
            Map.entry("bel", "be"),
            Map.entry("belarusian", "be")
    );

    private LanguageResolver() {
    }

    public static LanguageCode resolve(String raw) {
        if (raw == null || raw.isBlank()) return UNDETERMINED;

        String value = raw.trim().replace('_', '-');
        String alias = ALIASES.get(value.toLowerCase(Locale.ROOT));
        if (alias != null) value = alias;

        // A few sources emit a language followed by encoding/locale punctuation.
        int comma = value.indexOf(',');
        if (comma > 0) value = value.substring(0, comma).trim();
        int semicolon = value.indexOf(';');
        if (semicolon > 0) value = value.substring(0, semicolon).trim();

        try {
            return LanguageCode.of(value);
        } catch (IllegalArgumentException ex) {
            return UNDETERMINED;
        }
    }

    public static String resolveValue(String raw) {
        return resolve(raw).value();
    }
}
