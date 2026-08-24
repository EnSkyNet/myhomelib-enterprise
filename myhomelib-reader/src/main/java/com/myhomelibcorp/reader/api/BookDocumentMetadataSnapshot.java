package com.myhomelibcorp.reader.api;

import java.util.List;
import java.util.Optional;

/** Незмінний snapshot метаданих, зручний для lightweight scan. */
public final class BookDocumentMetadataSnapshot implements BookDocumentMetadata {

    private final BookMetadata metadata;
    private final long estimatedCharacterCount;
    private final boolean hasImages;
    private final int chapterCount;

    public BookDocumentMetadataSnapshot(
            BookMetadata metadata,
            long estimatedCharacterCount,
            boolean hasImages,
            int chapterCount
    ) {
        this.metadata = metadata != null ? metadata : BookMetadata.empty();
        this.estimatedCharacterCount = Math.max(0, estimatedCharacterCount);
        this.hasImages = hasImages;
        this.chapterCount = Math.max(0, chapterCount);
    }

    @Override public String id() { return metadata.id(); }
    @Override public String title() { return metadata.title(); }
    @Override public List<String> authors() { return metadata.authors(); }
    @Override public String language() { return metadata.language(); }
    @Override public Optional<String> series() { return Optional.ofNullable(metadata.series()); }
    @Override public Optional<Integer> sequenceNumber() { return Optional.ofNullable(metadata.sequenceNumber()); }
    @Override public List<String> genres() { return metadata.genres(); }
    @Override public String annotation() { return metadata.annotation(); }
    @Override public String publisher() { return metadata.publisher(); }
    @Override public String year() { return metadata.year(); }
    @Override public String isbn() { return metadata.isbn(); }
    @Override public long fileSize() { return metadata.fileSize(); }
    @Override public long estimatedCharacterCount() { return estimatedCharacterCount; }
    @Override public boolean hasImages() { return hasImages; }
    @Override public int chapterCount() { return chapterCount; }
}
