package com.myhomelibcorp.application.recommendation;

import java.util.List;

public record SimilarBookResult(String bookId, String title, double score, List<BookSimilarityEvidence> reasons) {
    public SimilarBookResult { reasons = reasons == null ? List.of() : List.copyOf(reasons); }
}
