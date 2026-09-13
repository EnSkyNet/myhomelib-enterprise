package com.myhomelibcorp.application.metadata;

/** UI-safe provider issue. Raw vendor quota/headers/error payloads are intentionally not exposed. */
public record MetadataProviderIssue(
        String providerId,
        String providerName,
        MetadataProviderErrorKind kind,
        String message
) {
    public MetadataProviderIssue {
        providerId = clean(providerId);
        providerName = clean(providerName);
        kind = kind == null ? MetadataProviderErrorKind.FAILED : kind;
        message = message == null || message.isBlank() ? "Metadata provider failed" : message.trim();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
