package com.myhomelibcorp.application.metadata;

import java.util.List;

/** Aggregated, non-destructive metadata lookup result. */
public record MetadataLookupResult(
        List<MetadataCandidate> candidates,
        List<MetadataProviderIssue> issues,
        boolean cancelled
) {
    public MetadataLookupResult {
        candidates = List.copyOf(candidates == null ? List.of() : candidates);
        issues = List.copyOf(issues == null ? List.of() : issues);
        if (cancelled) candidates = List.of();
    }

    public static MetadataLookupResult empty() {
        return new MetadataLookupResult(List.of(), List.of(), false);
    }

    public static MetadataLookupResult cancelled(List<MetadataProviderIssue> issues) {
        return new MetadataLookupResult(List.of(), issues, true);
    }
}
