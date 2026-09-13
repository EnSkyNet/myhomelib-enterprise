package com.myhomelibcorp.ui.duplicate;

/** Immutable row rendered in the duplicate-review table. Candidate id is retained for explicit merge review. */
public record DuplicateReviewRow(
        String candidateId,
        String candidateTitle,
        String candidateAuthors,
        String candidateYear,
        String candidateIsbn,
        String score,
        String reasons,
        String artifacts
) {}
