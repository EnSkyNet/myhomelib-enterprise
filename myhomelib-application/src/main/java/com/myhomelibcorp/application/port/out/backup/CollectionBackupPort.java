package com.myhomelibcorp.application.port.out.backup;

import com.myhomelibcorp.domain.model.collection.Collection;

import java.io.IOException;
import java.nio.file.Path;

/** Infrastructure boundary used by backup/restore orchestration. */
public interface CollectionBackupPort {
    Collection getCurrentCollection();
    String getDatabasePath(Collection collection);
    void closeCurrentCollection();
    void openCollection(Collection collection);
    boolean hasActiveCollection();

    /** Creates a transactionally consistent SQLite database snapshot. */
    void createDatabaseSnapshot(Collection collection, Path targetFile) throws IOException;

    /** Opens the supplied SQLite file independently and requires PRAGMA quick_check = ok. */
    void validateDatabaseFile(Path databaseFile) throws IOException;

    /**
     * Atomically replaces the active collection database with a previously validated snapshot.
     * Implementations must reopen the same collection before returning and leave either the old
     * or restored database usable if the filesystem swap itself fails.
     */
    void restoreDatabaseSnapshot(Collection collection, Path snapshotFile) throws IOException;
}
