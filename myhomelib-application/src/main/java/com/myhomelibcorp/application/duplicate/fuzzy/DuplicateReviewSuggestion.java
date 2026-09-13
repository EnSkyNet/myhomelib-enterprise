package com.myhomelibcorp.application.duplicate.fuzzy;

import com.myhomelibcorp.application.dto.BookDto;

import java.util.List;

/** User-facing, explainable duplicate suggestion. This value never implies an automatic merge. */
public record DuplicateReviewSuggestion(
        BookDto source,
        BookDto candidate,
        double score,
        List<FuzzyDuplicateReason> reasons
) {
    public DuplicateReviewSuggestion {
        if (source == null || candidate == null) throw new IllegalArgumentException("Both books are required");
        score = Math.max(0.0, Math.min(1.0, score));
        reasons = List.copyOf(reasons == null ? List.of() : reasons);
        if (reasons.isEmpty()) throw new IllegalArgumentException("At least one reason is required");
    }
}
