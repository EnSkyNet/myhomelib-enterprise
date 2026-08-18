package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.application.dto.ReadingStatisticsDto;
import com.myhomelibcorp.application.port.out.statistics.ReadingStatisticsPort;
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

    private final ReadingStatisticsPort statisticsPort;

    private final ConcurrentMap<String, ReadingStatisticsDto> statsCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, LocalDateTime> sessionStartTimes = new ConcurrentHashMap<>();

    public ReadingStatisticsDto loadOrCreateStats(String bookId, String bookTitle) {
        if (bookId == null) {
            return null;
        }

        return statisticsPort.findByBookId(bookId)
                .orElseGet(() -> {
                    ReadingStatisticsDto newStats = ReadingStatisticsDto.forBook(bookId, bookTitle);
                    statisticsPort.save(newStats);
                    return newStats;
                });
    }

    public void startReadingSession(ReaderSession session) {
        if (session == null || session.getBookId() == null) {
            return;
        }

        String bookId = session.getBookId();
        String bookTitle = session.getBook() != null ? session.getBook().getTitle() : "Без назви";

        ReadingStatisticsDto stats = loadOrCreateStats(bookId, bookTitle);
        statsCache.put(bookId, stats);
        sessionStartTimes.put(bookId, LocalDateTime.now());

        log.debug("Started reading session for book: {}", bookTitle);
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

        ReadingStatisticsDto stats = statsCache.get(bookId);
        if (stats == null) {
            return;
        }

        long sessionDuration = ChronoUnit.SECONDS.between(startTime, LocalDateTime.now());
        int currentPercent = (int) session.getProgressPercent();

        ReadingStatisticsDto updatedStats = stats.withSession(sessionDuration, currentPercent);

        statisticsPort.save(updatedStats);
        statsCache.put(bookId, updatedStats);

        log.debug("Ended reading session for book: {} ({} sec, {}%)",
                session.getBook().getTitle(), sessionDuration, currentPercent);
    }

    public ReaderReadingStats getStats(String bookId) {
        if (bookId == null) {
            return null;
        }

        ReadingStatisticsDto cached = statsCache.get(bookId);
        if (cached == null) {
            cached = statisticsPort.findByBookId(bookId).orElse(null);
            if (cached != null) {
                statsCache.put(bookId, cached);
            }
        }

        if (cached == null) {
            return null;
        }

        return ReaderReadingStats.builder()
                .bookId(cached.getBookId())
                .bookTitle(cached.getBookTitle())
                .firstReadAt(cached.getFirstReadAt())
                .lastReadAt(cached.getLastReadAt())
                .totalReadingSeconds(cached.getTotalReadingSeconds())
                .readingSessions(cached.getReadingSessions())
                .startPercent(cached.getStartPercent())
                .endPercent(cached.getEndPercent())
                .currentPercent(cached.getCurrentPercent())
                .completedAt(cached.getCompletedAt())
                .build();
    }

    public void updateProgress(ReaderSession session) {
        if (session == null || session.getBookId() == null) {
            return;
        }

        String bookId = session.getBookId();
        int currentPercent = (int) session.getProgressPercent();

        statisticsPort.updateProgress(bookId, currentPercent);

        ReadingStatisticsDto stats = statsCache.get(bookId);
        if (stats != null) {
            ReadingStatisticsDto updatedStats = ReadingStatisticsDto.builder()
                    .bookId(stats.getBookId())
                    .bookTitle(stats.getBookTitle())
                    .firstReadAt(stats.getFirstReadAt())
                    .lastReadAt(LocalDateTime.now())
                    .totalReadingSeconds(stats.getTotalReadingSeconds())
                    .readingSessions(stats.getReadingSessions())
                    .startPercent(stats.getStartPercent())
                    .endPercent(Math.max(stats.getEndPercent(), currentPercent))
                    .currentPercent(currentPercent)
                    .completedAt(currentPercent >= 100 ? LocalDateTime.now() : null)
                    .build();
            statsCache.put(bookId, updatedStats);
        }
    }

    public void clearCache() {
        statsCache.clear();
        sessionStartTimes.clear();
        log.info("Reader stats cache cleared");
    }
}