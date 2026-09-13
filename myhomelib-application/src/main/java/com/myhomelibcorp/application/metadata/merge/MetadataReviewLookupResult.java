package com.myhomelibcorp.application.metadata.merge;

import com.myhomelibcorp.application.metadata.MetadataProviderIssue;

import java.util.List;

/** Application result for UI review: already-normalized previews plus fail-soft provider issues. */
public record MetadataReviewLookupResult(
        List<MetadataMergePreview> previews,
        List<MetadataProviderIssue> issues,
        boolean cancelled) {
    public MetadataReviewLookupResult {
        previews = List.copyOf(previews == null ? List.of() : previews);
        issues = List.copyOf(issues == null ? List.of() : issues);
        if (cancelled) previews = List.of();
    }
}
