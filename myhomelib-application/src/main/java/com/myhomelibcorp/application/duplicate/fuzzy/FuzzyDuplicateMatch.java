package com.myhomelibcorp.application.duplicate.fuzzy;

import com.myhomelibcorp.domain.model.valueobject.BookId;

import java.util.List;

/** A non-destructive duplicate suggestion. No merge action is implied by this value. */
public record FuzzyDuplicateMatch(
        BookId leftBookId,
        BookId rightBookId,
        double score,
        List<FuzzyDuplicateReason> reasons
) {
    public FuzzyDuplicateMatch {
        if (leftBookId == null || rightBookId == null) throw new IllegalArgumentException("Both book ids are required");
        score = Math.max(0.0, Math.min(1.0, score));
        reasons = List.copyOf(reasons == null ? List.of() : reasons);
        if (reasons.isEmpty()) throw new IllegalArgumentException("At least one duplicate reason is required");
    }
}
