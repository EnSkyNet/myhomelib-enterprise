package com.myhomelibcorp.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReadingStatisticsDto {
    private String bookId;
    private LocalDateTime firstReadAt;
    private LocalDateTime lastReadAt;
    private long totalReadingSeconds;
    private int readingSessions;
    private int startPercent;
    private int endPercent;
    private int currentPercent;
    private LocalDateTime completedAt;

}
