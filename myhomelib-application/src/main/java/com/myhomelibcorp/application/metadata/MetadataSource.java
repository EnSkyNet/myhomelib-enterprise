package com.myhomelibcorp.application.metadata;

/** Stable, provider-neutral attribution for one remote metadata record. */
public record MetadataSource(
        String providerId,
        String providerName,
        String recordId,
        String recordUrl
) {
    public MetadataSource {
        providerId = required(providerId, "providerId");
        providerName = required(providerName, "providerName");
        recordId = clean(recordId);
        recordUrl = clean(recordUrl);
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
