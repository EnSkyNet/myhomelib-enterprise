package com.myhomelibcorp.application.imports.statistics;

import com.myhomelibcorp.application.imports.diagnostics.ImportIssue;

import java.util.List;

public record ImportResult(
        long imported,
        long skipped,
        long duplicates,
        long errors,
        long durationMs,
        ImportStatus status,
        ImportChangeSet changes,
        List<ImportIssue> issues
) {
    /** Compatibility constructor for existing importers/tests. */
    public ImportResult(long imported, long skipped, long duplicates, long errors, long durationMs) {
        this(imported, skipped, duplicates, errors, durationMs,
                errors > 0 ? ImportStatus.SUCCESS_WITH_WARNINGS : ImportStatus.SUCCESS,
                ImportChangeSet.empty(false), List.of());
    }

    public ImportResult {
        status = status == null ? ImportStatus.SUCCESS : status;
        changes = changes == null ? ImportChangeSet.empty(false) : changes;
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public static ImportResult fromStatistics(ImportStatistics stats) {
        return new ImportResult(
                stats.getImported().get(),
                stats.getSkipped().get(),
                stats.getDuplicates().get(),
                stats.getErrors().get(),
                stats.getDurationMs()
        );
    }

    public static ImportResult cancelled(long durationMs) {
        return new ImportResult(0, 0, 0, 0, durationMs,
                ImportStatus.CANCELLED, ImportChangeSet.empty(false), List.of());
    }
}
