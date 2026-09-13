package com.myhomelibcorp.application.duplicate;

import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;

/** One artifact participating in a byte-for-byte duplicate group. */
public record ExactDuplicateArtifact(
        BookId bookId,
        String artifactId,
        String title,
        String format,
        BookFile file,
        long sizeBytes
) {
    public ExactDuplicateArtifact {
        if (bookId == null) throw new IllegalArgumentException("bookId is required");
        if (artifactId == null || artifactId.isBlank()) throw new IllegalArgumentException("artifactId is required");
        title = title == null ? "" : title;
        format = format == null ? "" : format;
        if (file == null) file = BookFile.empty();
        sizeBytes = Math.max(0, sizeBytes);
    }
}
