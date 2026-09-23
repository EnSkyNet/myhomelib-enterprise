package com.myhomelibcorp.application.event;

import com.myhomelibcorp.application.imports.statistics.ImportResult;

import java.nio.file.Path;
import java.time.Instant;

public record ImportFinishedEvent(
        Path source,
        ImportResult result,
        Instant timestamp,
        String collectionId
) {
    public ImportFinishedEvent(Path source, ImportResult result) {
        this(source, result, Instant.now(), "");
    }

    public ImportFinishedEvent(Path source, ImportResult result, Instant timestamp) {
        this(source, result, timestamp, "");
    }

    public ImportFinishedEvent(Path source, ImportResult result, String collectionId) {
        this(source, result, Instant.now(), collectionId == null ? "" : collectionId.trim());
    }

    public boolean isSuccess() {
        return result != null && result.errors() == 0;
    }

    public long getImported() {
        return result != null ? result.imported() : 0;
    }

    public long getErrors() {
        return result != null ? result.errors() : 0;
    }
}
