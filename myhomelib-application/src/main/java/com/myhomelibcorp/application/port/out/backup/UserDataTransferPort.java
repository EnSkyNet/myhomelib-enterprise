package com.myhomelibcorp.application.port.out.backup;

import com.myhomelibcorp.application.imports.statistics.ImportChangeSet;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Portable, schema-versioned user-data export/restore boundary.
 * Implementations resolve book-scoped data by stable LibID first and may use
 * the internal book id only as a same-catalogue compatibility fallback.
 */
public interface UserDataTransferPort {
    String FILE_NAME = "user-data.json";
    int CURRENT_SCHEMA_VERSION = 2;

    ExportResult exportTo(Path targetFile) throws IOException;

    ImportResult restoreFrom(Path sourceFile) throws IOException;

    default boolean isPortableBackup(Path backupDir) {
        return backupDir != null && java.nio.file.Files.isRegularFile(backupDir.resolve(FILE_NAME));
    }

    record ExportResult(int schemaVersion, long bookRecords, long groupMemberships,
                        long bookmarks, long historyEntries, long savedSearches,
                        long readerOverrides) { }

    record ImportResult(int sourceSchemaVersion, int effectiveSchemaVersion,
                        long matchedBooks, long unmatchedBooks, long groups,
                        long groupMemberships, long bookmarks, long historyEntries,
                        long savedSearches, long readerOverrides,
                        ImportChangeSet searchChanges) {
        public ImportResult {
            searchChanges = searchChanges == null ? ImportChangeSet.empty(true) : searchChanges;
        }
    }
}
