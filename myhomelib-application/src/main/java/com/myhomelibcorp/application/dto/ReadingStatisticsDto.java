package com.myhomelibcorp.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReadingStatisticsDto {
    private String bookId;
    private String bookTitle;
    private LocalDateTime firstReadAt;
    private LocalDateTime lastReadAt;
    private long totalReadingSeconds;
    private int readingSessions;
    private int startPercent;
    private int endPercent;
    private int currentPercent;
    private LocalDateTime completedAt;

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
            return "Сьогодні " + lastReadAt.format(DateTimeFormatter.ofPattern("HH:mm"));
        }
        if (lastReadAt.toLocalDate().equals(now.minusDays(1).toLocalDate())) {
            return "Вчора " + lastReadAt.format(DateTimeFormatter.ofPattern("HH:mm"));
        }
        return lastReadAt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
    }

    public static ReadingStatisticsDto forBook(String bookId, String bookTitle) {
        return ReadingStatisticsDto.builder()
                .bookId(bookId)
                .bookTitle(bookTitle)
                .firstReadAt(LocalDateTime.now())
                .lastReadAt(LocalDateTime.now())
                .totalReadingSeconds(0)
                .readingSessions(0)
                .startPercent(0)
                .endPercent(0)
                .currentPercent(0)
                .build();
    }

    public ReadingStatisticsDto withSession(long durationSeconds, int currentPercent) {
        long newTotal = this.totalReadingSeconds + durationSeconds;
        int newSessions = this.readingSessions + 1;

        return ReadingStatisticsDto.builder()
                .bookId(this.bookId)
                .bookTitle(this.bookTitle)
                .firstReadAt(this.firstReadAt)
                .lastReadAt(LocalDateTime.now())
                .totalReadingSeconds(newTotal)
                .readingSessions(newSessions)
                .startPercent(this.startPercent)
                .endPercent(Math.max(this.endPercent, currentPercent))
                .currentPercent(currentPercent)
                .completedAt(currentPercent >= 100 ? LocalDateTime.now() : null)
                .build();
    }
}