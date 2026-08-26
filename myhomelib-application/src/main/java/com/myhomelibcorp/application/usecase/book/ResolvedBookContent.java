package com.myhomelibcorp.application.usecase.book;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** One resolved physical document; closes/removes only temporary archive materialization. */
public final class ResolvedBookContent implements AutoCloseable {
    private final Path path;
    private final boolean temporary;

    public ResolvedBookContent(Path path, boolean temporary) {
        this.path = path;
        this.temporary = temporary;
    }

    public Path path() { return path; }
    public boolean temporary() { return temporary; }

    @Override
    public void close() {
        if (temporary && path != null) {
            try { Files.deleteIfExists(path); } catch (IOException ignored) { }
        }
    }
}
