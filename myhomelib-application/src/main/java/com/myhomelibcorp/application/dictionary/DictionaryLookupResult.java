package com.myhomelibcorp.application.dictionary;

import com.myhomelibcorp.application.textprovider.TextProviderIssue;

import java.util.List;

public record DictionaryLookupResult(
        List<DictionaryEntry> entries,
        TextProviderIssue issue,
        boolean cancelled
) {
    public DictionaryLookupResult {
        entries = List.copyOf(entries == null ? List.of() : entries);
        if (cancelled) entries = List.of();
    }

    public static DictionaryLookupResult success(List<DictionaryEntry> entries) {
        return new DictionaryLookupResult(entries, null, false);
    }

    public static DictionaryLookupResult cancelled(TextProviderIssue issue) {
        return new DictionaryLookupResult(List.of(), issue, true);
    }

    public static DictionaryLookupResult failed(TextProviderIssue issue) {
        return new DictionaryLookupResult(List.of(), issue, false);
    }
}
