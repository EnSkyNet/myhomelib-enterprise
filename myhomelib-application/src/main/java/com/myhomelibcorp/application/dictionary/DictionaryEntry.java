package com.myhomelibcorp.application.dictionary;

import java.util.List;

/** Normalized definition returned by any dictionary provider. */
public record DictionaryEntry(
        String providerId,
        String providerName,
        String headword,
        String language,
        String partOfSpeech,
        String definition,
        List<String> examples,
        String source
) {
    public DictionaryEntry {
        providerId = required(providerId, "providerId");
        providerName = required(providerName, "providerName");
        headword = required(headword, "headword");
        language = clean(language);
        partOfSpeech = clean(partOfSpeech);
        definition = required(definition, "definition");
        examples = examples == null ? List.of() : examples.stream()
                .map(DictionaryEntry::clean)
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(20)
                .toList();
        source = clean(source);
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
