package com.myhomelibcorp.ui.duplicate;

import java.util.List;

public record DuplicateReviewPresentation(
        String sourceTitle,
        String sourceAuthors,
        String sourceArtifacts,
        List<DuplicateReviewRow> rows
) {
    public DuplicateReviewPresentation {
        rows = List.copyOf(rows == null ? List.of() : rows);
    }
}
