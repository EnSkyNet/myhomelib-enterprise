package com.myhomelibcorp.application.duplicate;

import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;

/** Stable snapshot item used by the exact-duplicate scan queue. */
public record ArtifactScanCandidate(
        BookId bookId,
        String artifactId,
        String format,
        BookFile file
) {
    public ArtifactScanCandidate {
        if (bookId == null) throw new IllegalArgumentException("bookId is required");
        if (artifactId == null || artifactId.isBlank()) throw new IllegalArgumentException("artifactId is required");
        format = format == null ? "" : format;
        if (file == null) file = BookFile.empty();
    }
}
