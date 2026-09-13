package com.myhomelibcorp.application.bulkedit;

import java.util.List;

/** Only fields the local bulk editor is allowed to mutate. User state/files/artifacts are intentionally absent. */
public record BatchMetadataEditableSnapshot(
        String title,
        String series,
        Integer sequenceNumber,
        String language,
        Integer year,
        String publisher,
        String tags,
        String annotation,
        List<BatchMetadataGenreSnapshot> genres) {
    public BatchMetadataEditableSnapshot {
        genres = genres == null ? List.of() : List.copyOf(genres);
    }
}
