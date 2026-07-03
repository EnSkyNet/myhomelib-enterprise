package com.myhomelibcorp.application.event;

import com.myhomelibcorp.application.imports.statistics.ImportResult;

import java.nio.file.Path;

public record ImportSummary(
        Path source,
        long imported,
        long skipped,
        long duplicates,
        long errors,
        long durationMs
) {
    public static ImportSummary from(Path source, ImportResult result) {
        return new ImportSummary(
                source,
                result.imported(),      // <-- змінено
                result.skipped(),       // <-- змінено
                result.duplicates(),    // <-- змінено
                result.errors(),        // <-- змінено
                result.durationMs()     // <-- змінено
        );
    }

    public String getFormattedMessage() {
        return String.format(
                "Імпорт завершено: +%d, пропущено: %d, дублікатів: %d, помилок: %d, час: %d мс",
                imported, skipped, duplicates, errors, durationMs
        );
    }
}