package com.myhomelibcorp.application.service;

import com.myhomelibcorp.application.dto.ReadingSessionRecord;
import com.myhomelibcorp.application.dto.ReadingStatisticsDto;
import com.myhomelibcorp.application.port.out.statistics.ReadingStatisticsPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/** Tracks bounded in-process Reader sessions and persists completed sessions atomically. */
@Service
@RequiredArgsConstructor
public class ReadingSessionService {

    private final ReadingStatisticsPort statisticsPort;
    private final ConcurrentMap<String, ActiveSession> activeSessions = new ConcurrentHashMap<>();

    public void start(String bookId, int startPercent) {
        if (bookId == null || bookId.isBlank()) return;
        // A duplicate UI callback must not reset the session timer and silently lose elapsed time.
        activeSessions.putIfAbsent(bookId, new ActiveSession(
                System.nanoTime(), LocalDateTime.now(), clampPercent(startPercent)));
    }

    public void finish(String bookId, int currentPercent) {
        if (bookId == null || bookId.isBlank()) return;
        ActiveSession session = activeSessions.remove(bookId);
        if (session == null) return;

        LocalDateTime endedAt = LocalDateTime.now();
        long durationSeconds = Math.max(0L,
                TimeUnit.NANOSECONDS.toSeconds(Math.max(0L, System.nanoTime() - session.startedNanos())));
        statisticsPort.recordSession(new ReadingSessionRecord(
                bookId,
                session.startedAt(),
                endedAt,
                durationSeconds,
                session.startPercent(),
                clampPercent(currentPercent)));
    }

    public Optional<ReadingStatisticsDto> find(String bookId) {
        if (bookId == null || bookId.isBlank()) return Optional.empty();
        return statisticsPort.findByBookId(bookId);
    }

    public void discard(String bookId) {
        if (bookId != null) activeSessions.remove(bookId);
    }

    int activeSessionCount() {
        return activeSessions.size();
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private record ActiveSession(long startedNanos, LocalDateTime startedAt, int startPercent) { }
}
