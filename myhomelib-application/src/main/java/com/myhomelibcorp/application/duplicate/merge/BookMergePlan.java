package com.myhomelibcorp.application.duplicate.merge;

import com.myhomelibcorp.domain.model.valueobject.BookId;

/** Explicit, non-destructive logical merge choice made by the user. */
public record BookMergePlan(
        BookId survivorBookId,
        BookId mergedBookId,
        BookId metadataSourceBookId
) {
    public BookMergePlan {
        if (survivorBookId == null || mergedBookId == null || metadataSourceBookId == null) {
            throw new IllegalArgumentException("All merge book ids are required");
        }
        if (survivorBookId.equals(mergedBookId)) {
            throw new IllegalArgumentException("Survivor and merged book must be different");
        }
        if (!metadataSourceBookId.equals(survivorBookId) && !metadataSourceBookId.equals(mergedBookId)) {
            throw new IllegalArgumentException("Metadata source must be one of the merged books");
        }
    }
}
