package com.myhomelibcorp.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LibraryStatistics {
    private long booksCount;
    private long authorsCount;
    private long seriesCount;
    private long genresCount;
    private long languagesCount;
    private long publishersCount;
    private long totalSizeBytes;
    private long duplicatesCount;
    private long missingCoversCount;
}