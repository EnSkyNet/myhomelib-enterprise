package com.myhomelibcorp.infrastructure.sync;

/** Accumulates per-file sync outcomes without I/O or orchestration. */
final class FolderSyncCounters {
    int added;
    int updated;
    int deleted;
    int skipped;
    int errors;

    void add(FileResult result) {
        added += result.added();
        updated += result.updated();
        deleted += result.deleted();
        errors += result.errors();
        if (result.added() == 0 && result.updated() == 0 && result.deleted() == 0 && result.errors() == 0) skipped++;
    }

    record FileResult(int added, int updated, int deleted, int errors) {
        static FileResult skipped() { return new FileResult(0, 0, 0, 0); }
    }

}
