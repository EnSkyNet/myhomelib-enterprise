package com.myhomelibcorp.reader.model;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class ReaderReadingStats {
    String bookId;
    String bookTitle;
    LocalDateTime firstReadAt;
    LocalDateTime lastReadAt;
    long totalReadingSeconds;
    int readingSessions;
    int startPercent;
    int endPercent;
    int currentPercent;
    LocalDateTime completedAt;

    public String getFormattedTotalTime() {
        long seconds = totalReadingSeconds;
        if (seconds < 60) return seconds + "с";
        if (seconds < 3600) return (seconds / 60) + "хв " + (seconds % 60) + "с";
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        return hours + "год " + minutes + "хв";
    }

    public String getEstimatedTimeRemaining() {
        if (currentPercent >= 100) return "Завершено";
        if (currentPercent <= 0 || totalReadingSeconds == 0) return "Немає даних";

        double progress = currentPercent / 100.0;
        double avgTimePerPercent = totalReadingSeconds / progress;
        double remainingPercent = (100 - currentPercent) / 100.0;
        long remainingSeconds = (long) (avgTimePerPercent * remainingPercent);

        if (remainingSeconds < 60) return "менше хвилини";
        if (remainingSeconds < 3600) return (remainingSeconds / 60) + "хв";
        long hours = remainingSeconds / 3600;
        long minutes = (remainingSeconds % 3600) / 60;
        return hours + "год " + minutes + "хв";
    }

    public String getLastReadFormatted() {
        if (lastReadAt == null) return "Ніколи";
        LocalDateTime now = LocalDateTime.now();
        if (lastReadAt.toLocalDate().equals(now.toLocalDate())) {
            return "Сьогодні " + lastReadAt.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        }
        if (lastReadAt.toLocalDate().equals(now.minusDays(1).toLocalDate())) {
            return "Вчора " + lastReadAt.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        }
        return lastReadAt.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
    }
}