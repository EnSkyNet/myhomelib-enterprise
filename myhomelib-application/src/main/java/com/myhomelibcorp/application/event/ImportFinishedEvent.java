package com.myhomelibcorp.application.event;

import com.myhomelibcorp.application.imports.statistics.ImportResult;

import java.nio.file.Path;
import java.time.Instant;

public record ImportFinishedEvent(
        Path source,
        ImportResult result,
        Instant timestamp
) {
    public ImportFinishedEvent(Path source, ImportResult result) {
        this(source, result, Instant.now());
    }

    public boolean isSuccess() {
        return result != null && result.errors() == 0;  // <-- змінено
    }

    public long getImported() {
        return result != null ? result.imported() : 0;  // <-- змінено
    }

    public long getErrors() {
        return result != null ? result.errors() : 0;    // <-- змінено
    }
}