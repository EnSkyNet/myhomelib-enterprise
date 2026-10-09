package com.myhomelibcorp.application.util;

import java.io.IOException;
import java.nio.file.Path;

/** Fail-closed cleanup for SQLite WAL/SHM sidecars that belong to an application-managed database. */
public final class SqliteSidecarSupport {
    private SqliteSidecarSupport() { }

    public static void delete(Path database) throws IOException {
        if (database == null) return;
        SafeFilePublication.deleteSafeRegularFileIfExists(
                Path.of(database.toString() + "-wal"), "SQLite WAL sidecar cleanup");
        SafeFilePublication.deleteSafeRegularFileIfExists(
                Path.of(database.toString() + "-shm"), "SQLite SHM sidecar cleanup");
    }
}
