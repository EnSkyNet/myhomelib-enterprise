package com.myhomelibcorp.application.imports.statistics;

public record ImportResult(
        long imported,
        long skipped,
        long duplicates,
        long errors,
        long durationMs
) {
    public static ImportResult fromStatistics(ImportStatistics stats) {
        return new ImportResult(
                stats.getImported().get(),
                stats.getSkipped().get(),
                stats.getDuplicates().get(),
                stats.getErrors().get(),
                stats.getDurationMs()
        );
    }
}