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
    private long localBooksCount;
    private long remoteBooksCount;
    private long readBooksCount;
    private long unreadBooksCount;
    private long favoritesCount;
    private long deletedBooksCount;
    private long sourcesCount;
    /** True when cached aggregates no longer describe the current catalog state. */
    private boolean stale;
}