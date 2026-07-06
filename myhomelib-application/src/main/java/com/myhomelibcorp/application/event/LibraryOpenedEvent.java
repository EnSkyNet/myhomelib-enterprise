package com.myhomelibcorp.application.event;

import java.nio.file.Path;
import java.time.Instant;

public record LibraryOpenedEvent(
        Path libraryPath,
        Instant timestamp
) {
    public LibraryOpenedEvent(Path libraryPath) {
        this(libraryPath, Instant.now());
    }
}