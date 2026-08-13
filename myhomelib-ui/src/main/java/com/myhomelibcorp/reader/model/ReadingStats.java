package com.myhomelibcorp.reader.model;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class ReadingStats {
    String bookId;
    String bookTitle;
    int totalPages;
    int currentPage;
    int totalParagraphs;
    int currentParagraph;
    double percent;
    int minutesRead;
    LocalDateTime lastRead;
    int sessionsCount;

    public String getFormattedPercent() {
        return String.format("%.1f%%", percent);
    }

    public String getFormattedTime() {
        if (minutesRead < 60) {
            return minutesRead + " хв";
        }
        int hours = minutesRead / 60;
        int mins = minutesRead % 60;
        return hours + " год " + mins + " хв";
    }
}