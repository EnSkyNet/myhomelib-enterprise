package com.myhomelibcorp.application.dto;

import java.time.LocalDateTime;

/** One completed Reader session to be merged atomically into aggregate statistics. */
public record ReadingSessionRecord(
        String bookId,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        long durationSeconds,
        int startPercent,
        int currentPercent
) { }
