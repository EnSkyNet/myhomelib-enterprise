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
public class ReadingProgressDto {
    private String bookId;
    private String anchorId;
    private int paragraphIndex;

    @Builder.Default
    private String paragraphId = "";  // <-- DEFAULT ЗНАЧЕННЯ

    private int charOffset;
    private double percent;

    @Builder.Default
    private String chapterTitle = "";  // <-- DEFAULT ЗНАЧЕННЯ

    @Builder.Default
    private String chapterId = "";     // <-- DEFAULT ЗНАЧЕННЯ

    private LocalDateTime updatedAt;
    private long readingTimeSeconds;
}