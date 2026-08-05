package com.myhomelibcorp.domain.model.sync;

import lombok.Builder;
import lombok.Value;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Value
@Builder
public class SyncResult {
    @Builder.Default
    int added = 0;

    @Builder.Default
    int updated = 0;

    @Builder.Default
    int deleted = 0;

    @Builder.Default
    int skipped = 0;

    @Builder.Default
    int errors = 0;

    @Builder.Default
    List<String> errorMessages = new ArrayList<>();

    @Builder.Default
    LocalDateTime startTime = LocalDateTime.now();

    @Builder.Default
    LocalDateTime endTime = LocalDateTime.now();

    public Duration getDuration() {
        return Duration.between(startTime, endTime);
    }

    public int getTotalProcessed() {
        return added + updated + deleted + skipped;
    }

    public String getSummary() {
        return String.format(
                "Додано: %d, Оновлено: %d, Видалено: %d, Пропущено: %d, Помилок: %d, Час: %s",
                added, updated, deleted, skipped, errors, formatDuration(getDuration())
        );
    }

    private String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        if (seconds < 60) return seconds + "с";
        if (seconds < 3600) return (seconds / 60) + "хв " + (seconds % 60) + "с";
        return (seconds / 3600) + "год " + ((seconds % 3600) / 60) + "хв";
    }
}