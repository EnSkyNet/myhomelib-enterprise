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
    /** SQLite generation observed by the caller: null = legacy unguarded writer,
     * -1 = row absent with no deletion record, <=-3 = observed delete generation,
     * >=0 = compare-and-swap update. Not exported to backups. */
    private Long revision;
    private String anchorId;
    /** Durable text resume anchor. Null means this medium has not been recorded yet. */
    private String textAnchorId;
    /** Durable audio resume anchor. Kept independently of the text position. */
    private String audioAnchorId;
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

    /** Human/device identifier of the last writer; persisted for cross-device Continue Reading UX. */
    @Builder.Default
    private String lastDevice = "";
}