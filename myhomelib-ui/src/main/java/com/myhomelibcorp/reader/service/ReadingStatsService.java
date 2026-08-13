package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.application.dto.ReadingProgressDto;
import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import com.myhomelibcorp.reader.model.ReadingStats;
import com.myhomelibcorp.reader.session.ReaderSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReadingStatsService {

    private final ReadingProgressRepository repository;
    private final ReaderPositionService positionService;

    private final ConcurrentMap<String, ReadingStats> statsCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, LocalDateTime> sessionStartTimes = new ConcurrentHashMap<>();

    public ReadingStats getStats(ReaderSession session) {
        if (session == null || session.getBook() == null) {
            return null;
        }

        String bookId = session.getBookId();

        // Перевіряємо кеш
        if (statsCache.containsKey(bookId)) {
            return statsCache.get(bookId);
        }

        // Завантажуємо з бази
        Optional<ReadingProgressDto> progressOpt = repository.findByBookId(bookId);
        if (progressOpt.isEmpty()) {
            return createEmptyStats(session);
        }

        ReadingProgressDto progress = progressOpt.get();
        int totalParagraphs = getTotalParagraphs(session);
        int currentParagraph = getCurrentParagraphIndex(progress.getParagraphId());

        ReadingStats stats = ReadingStats.builder()
                .bookId(bookId)
                .bookTitle(session.getBook().getTitle())
                .totalParagraphs(totalParagraphs)
                .currentParagraph(currentParagraph)
                .percent(progress.getPercent())
                .lastRead(progress.getUpdatedAt())
                .minutesRead(0)
                .sessionsCount(0)
                .build();

        statsCache.put(bookId, stats);
        return stats;
    }

    private ReadingStats createEmptyStats(ReaderSession session) {
        return ReadingStats.builder()
                .bookId(session.getBookId())
                .bookTitle(session.getBook().getTitle())
                .totalParagraphs(0)
                .currentParagraph(0)
                .percent(0)
                .lastRead(LocalDateTime.now())
                .minutesRead(0)
                .sessionsCount(0)
                .build();
    }

    private int getTotalParagraphs(ReaderSession session) {
        if (session == null || session.getWebEngine() == null) {
            return 0;
        }
        try {
            // Тимчасово повертаємо 0, поки не реалізовано повноцінно
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private int getCurrentParagraphIndex(String paragraphId) {
        if (paragraphId == null) return 0;
        try {
            if (paragraphId.startsWith("p")) {
                return Integer.parseInt(paragraphId.substring(1));
            }
            return Integer.parseInt(paragraphId);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public void startSession(String bookId) {
        sessionStartTimes.put(bookId, LocalDateTime.now());
        log.debug("Reading session started for book: {}", bookId);
    }

    public void endSession(String bookId) {
        LocalDateTime start = sessionStartTimes.remove(bookId);
        if (start != null && statsCache.containsKey(bookId)) {
            ReadingStats stats = statsCache.get(bookId);
            int minutes = (int) Duration.between(start, LocalDateTime.now()).toMinutes();

            // Безпечне отримання значень (int не може бути null)
            int currentMinutes = stats.getMinutesRead();
            int currentSessions = stats.getSessionsCount();

            // Оновлюємо статистику
            ReadingStats updatedStats = ReadingStats.builder()
                    .bookId(stats.getBookId())
                    .bookTitle(stats.getBookTitle())
                    .totalParagraphs(stats.getTotalParagraphs())
                    .currentParagraph(stats.getCurrentParagraph())
                    .percent(stats.getPercent())
                    .minutesRead(currentMinutes + minutes)
                    .lastRead(LocalDateTime.now())
                    .sessionsCount(currentSessions + 1)
                    .build();

            statsCache.put(bookId, updatedStats);
            log.debug("Reading session ended for book: {}, minutes this session: {}, total minutes: {}",
                    bookId, minutes, currentMinutes + minutes);
        } else if (start != null) {
            log.debug("Reading session ended but no stats in cache for book: {}", bookId);
        }
    }

    public void clearCache() {
        statsCache.clear();
        sessionStartTimes.clear();
        log.info("Reading stats cache cleared");
    }
}