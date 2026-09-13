package com.myhomelibcorp.application.metadata.merge;

import com.myhomelibcorp.application.metadata.MetadataCandidate;
import com.myhomelibcorp.application.metadata.MetadataSource;
import com.myhomelibcorp.domain.model.valueobject.BookId;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Immutable preview; creating it never changes the catalog. */
public record MetadataMergePreview(
        BookId bookId,
        String bookTitle,
        MetadataCandidate candidate,
        List<MetadataFieldComparison> fields) {
    public MetadataMergePreview {
        if (bookId == null) throw new IllegalArgumentException("bookId is required");
        if (candidate == null) throw new IllegalArgumentException("candidate is required");
        bookTitle = bookTitle == null ? "" : bookTitle.trim();
        fields = List.copyOf(fields == null ? List.of() : fields);
    }

    public double confidence() {
        return candidate.confidence();
    }

    public MetadataSource source() {
        return candidate.source();
    }

    public Set<MetadataMergeField> changedFields() {
        return fields.stream().filter(MetadataFieldComparison::changed)
                .map(MetadataFieldComparison::field)
                .collect(Collectors.toUnmodifiableSet());
    }
}
