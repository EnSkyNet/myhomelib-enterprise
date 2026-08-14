package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import com.myhomelibcorp.reader.model.ReaderReadingStats;
import com.myhomelibcorp.reader.session.ReaderSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReaderStatsService {

    private final ReadingProgressRepository progressRepository;

    private final ConcurrentMap<String, ReaderReadingStats> statsCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, LocalDateTime> sessionStartTimes = new ConcurrentHashMap<>();

    public void startReadingSession(ReaderSession session) {
        if (session == null || session.getBookId() == null) {
            return;
        }

        String bookId = session.getBookId();
        sessionStartTimes.put(bookId, LocalDateTime.now());

        ReaderReadingStats stats = statsCache.get(bookId);
        if (stats == null) {
            stats = ReaderReadingStats.builder()
                    .bookId(bookId)
                    .bookTitle(session.getBook().getTitle())
                    .firstReadAt(LocalDateTime.now())
                    .lastReadAt(LocalDateTime.now())
                    .readingSessions(0)
                    .startPercent(0)
                    .endPercent(0)
                    .currentPercent(0)
                    .build();
            statsCache.put(bookId, stats);
        }

        log.debug("Started reading session for book: {}", session.getBook().getTitle());
    }

    public void endReadingSession(ReaderSession session) {
        if (session == null || session.getBookId() == null) {
            return;
        }

        String bookId = session.getBookId();
        LocalDateTime startTime = sessionStartTimes.remove(bookId);

        if (startTime == null) {
            return;
        }

        ReaderReadingStats stats = statsCache.get(bookId);
        if (stats == null) {
            return;
        }

        long sessionDuration = ChronoUnit.SECONDS.between(startTime, LocalDateTime.now());
        long totalSeconds = stats.getTotalReadingSeconds() + sessionDuration;
        int sessions = stats.getReadingSessions() + 1;

        int currentPercent = (int) session.getProgressPercent();

        ReaderReadingStats updatedStats = ReaderReadingStats.builder()
                .bookId(bookId)
                .bookTitle(stats.getBookTitle())
                .firstReadAt(stats.getFirstReadAt())
                .lastReadAt(LocalDateTime.now())
                .totalReadingSeconds(totalSeconds)
                .readingSessions(sessions)
                .startPercent(stats.getStartPercent())
                .endPercent(currentPercent)
                .currentPercent(currentPercent)
                .completedAt(currentPercent >= 100 ? LocalDateTime.now() : null)
                .build();

        statsCache.put(bookId, updatedStats);
        log.debug("Ended reading session for book: {} ({} sec)",
                session.getBook().getTitle(), sessionDuration);
    }

    public ReaderReadingStats getStats(String bookId) {
        if (bookId == null) {
            return null;
        }
        return statsCache.get(bookId);
    }

    public void updateProgress(ReaderSession session) {
        if (session == null || session.getBookId() == null) {
            return;
        }

        String bookId = session.getBookId();
        ReaderReadingStats stats = statsCache.get(bookId);
        if (stats == null) {
            return;
        }

        int currentPercent = (int) session.getProgressPercent();
        ReaderReadingStats updatedStats = ReaderReadingStats.builder()
                .bookId(bookId)
                .bookTitle(stats.getBookTitle())
                .firstReadAt(stats.getFirstReadAt())
                .lastReadAt(LocalDateTime.now())
                .totalReadingSeconds(stats.getTotalReadingSeconds())
                .readingSessions(stats.getReadingSessions())
                .startPercent(stats.getStartPercent())
                .endPercent(currentPercent > stats.getEndPercent() ? currentPercent : stats.getEndPercent())
                .currentPercent(currentPercent)
                .completedAt(currentPercent >= 100 ? LocalDateTime.now() : null)
                .build();

        statsCache.put(bookId, updatedStats);
    }

    public void clearCache() {
        statsCache.clear();
        sessionStartTimes.clear();
        log.info("Reader stats cache cleared");
    }
}