package com.myhomelibcorp.application.textprovider;

/** Sanitized provider issue safe to show in desktop UI. */
public record TextProviderIssue(
        String providerId,
        String providerName,
        TextProviderErrorKind kind,
        String message
) {
    public TextProviderIssue {
        providerId = clean(providerId);
        providerName = clean(providerName);
        kind = kind == null ? TextProviderErrorKind.FAILED : kind;
        message = clean(message);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
