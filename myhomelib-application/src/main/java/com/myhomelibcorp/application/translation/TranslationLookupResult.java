package com.myhomelibcorp.application.translation;

import com.myhomelibcorp.application.textprovider.TextProviderIssue;

public record TranslationLookupResult(
        TranslationResult translation,
        TextProviderIssue issue,
        boolean cancelled
) {
    public TranslationLookupResult {
        if (cancelled) translation = null;
    }

    public static TranslationLookupResult success(TranslationResult translation) {
        return new TranslationLookupResult(translation, null, false);
    }

    public static TranslationLookupResult cancelled(TextProviderIssue issue) {
        return new TranslationLookupResult(null, issue, true);
    }

    public static TranslationLookupResult failed(TextProviderIssue issue) {
        return new TranslationLookupResult(null, issue, false);
    }
}
