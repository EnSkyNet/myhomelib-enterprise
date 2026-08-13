package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.application.dto.ReadingProgressDto;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import com.myhomelibcorp.reader.model.ReaderPosition;
import com.myhomelibcorp.reader.session.ReaderSession;
import javafx.scene.web.WebEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Відповідає за отримання та збереження позиції читання.
 * Використовує один JS-виклик для отримання повного снапшоту.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReaderPositionService {

    private final ReadingProgressRepository repository;
    private final CollectionLifecyclePort collectionLifecyclePort;
    private final ReaderJsBridge jsBridge;

    private final ConcurrentMap<String, ReaderPosition> lastSavedPositions = new ConcurrentHashMap<>();

    /**
     * Отримує поточну позицію з WebView одним викликом JS.
     */
    public ReaderPosition getCurrentPosition(ReaderSession session) {
        if (session == null || session.getWebEngine() == null || !session.isActive()) {
            return null;
        }

        WebEngine engine = session.getWebEngine();

        if (!jsBridge.isContentLoaded(engine)) {
            return null;
        }

        try {
            String script = """
                (function() {
                    var paragraphs = document.querySelectorAll('p[data-paragraph-id]');
                    if (paragraphs.length === 0) {
                        return JSON.stringify({
                            paragraphId: '',
                            paragraphIndex: -1,
                            charOffset: 0,
                            percent: 0,
                            chapterId: '',
                            chapterTitle: '',
                            totalParagraphs: 0
                        });
                    }

                    var scrollTop = document.documentElement.scrollTop || document.body.scrollTop || 0;
                    var scrollHeight = document.documentElement.scrollHeight - document.documentElement.clientHeight;
                    var percent = scrollHeight > 0 ? scrollTop / scrollHeight : 0;

                    var firstVisible = 0;
                    for (var i = 0; i < paragraphs.length; i++) {
                        var rect = paragraphs[i].getBoundingClientRect();
                        if (rect.bottom > 0 && rect.top < window.innerHeight) {
                            firstVisible = i;
                            break;
                        }
                    }

                    var el = paragraphs[firstVisible];
                    var text = el.innerText || '';
                    var totalHeight = el.getBoundingClientRect().height || 1;
                    var visibleTop = Math.max(el.getBoundingClientRect().top, 0);
                    var visibleBottom = Math.min(el.getBoundingClientRect().bottom, window.innerHeight);
                    var visibleHeight = Math.max(0, visibleBottom - visibleTop);
                    var ratio = Math.min(1, Math.max(0, visibleHeight / totalHeight));
                    var charOffset = Math.floor(ratio * text.length);

                    var chapterTitle = '';
                    var chapterEl = el.closest('.chapter');
                    if (chapterEl) {
                        var titleEl = chapterEl.querySelector('.chapter-title');
                        if (titleEl) {
                            chapterTitle = titleEl.innerText || '';
                        }
                    }

                    return JSON.stringify({
                        paragraphId: el.getAttribute('data-paragraph-id') || '',
                        paragraphIndex: firstVisible,
                        charOffset: charOffset,
                        percent: Math.min(1, Math.max(0, percent)),
                        chapterId: chapterEl ? chapterEl.id || '' : '',
                        chapterTitle: chapterTitle,
                        totalParagraphs: paragraphs.length
                    });
                })();
            """;

            Object result = engine.executeScript(script);
            if (result == null) {
                return null;
            }

            String json = result.toString();
            return parsePosition(json, session.getBookId());

        } catch (Exception e) {
            log.warn("Failed to get current position: {}", e.getMessage());
            return null;
        }
    }

    private ReaderPosition parsePosition(String json, String bookId) {
        try {
            // Простий парсинг без додаткових залежностей
            String paragraphId = extract(json, "paragraphId");
            int paragraphIndex = extractInt(json, "paragraphIndex");
            int charOffset = extractInt(json, "charOffset");
            double percent = extractDouble(json, "percent");
            String chapterId = extract(json, "chapterId");
            String chapterTitle = extract(json, "chapterTitle");

            return ReaderPosition.builder()
                    .bookId(bookId)
                    .paragraphId(paragraphId)
                    .paragraphIndex(paragraphIndex)
                    .charOffset(charOffset)
                    .percent(percent * 100)
                    .chapterId(chapterId)
                    .chapterTitle(chapterTitle)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse position JSON: {}", json, e);
            return null;
        }
    }

    private String extract(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int start = json.indexOf(pattern);
        if (start == -1) {
            pattern = "\"" + key + "\":";
            start = json.indexOf(pattern);
            if (start == -1) return "";
            start += pattern.length();
            int end = json.indexOf(",", start);
            if (end == -1) end = json.indexOf("}", start);
            if (end == -1) return "";
            return json.substring(start, end).trim();
        }
        start += pattern.length();
        int end = json.indexOf("\"", start);
        if (end == -1) return "";
        return json.substring(start, end);
    }

    private int extractInt(String json, String key) {
        try {
            return Integer.parseInt(extract(json, key));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double extractDouble(String json, String key) {
        try {
            return Double.parseDouble(extract(json, key));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Зберігає позицію в базу даних.
     */
    public void savePosition(ReaderPosition position) {
        if (position == null || position.getBookId() == null) {
            return;
        }

        if (collectionLifecyclePort == null || !collectionLifecyclePort.hasActiveCollection()) {
            return;
        }

        // Не зберігаємо початок книги
        if (position.getPercent() < 0.5 && position.getParagraphIndex() < 2) {
            return;
        }

        String sessionKey = position.getBookId();

        // Перевіряємо, чи змінилася позиція суттєво
        ReaderPosition lastSaved = lastSavedPositions.get(sessionKey);
        if (lastSaved != null) {
            if (lastSaved.getParagraphId().equals(position.getParagraphId()) &&
                    Math.abs(lastSaved.getCharOffset() - position.getCharOffset()) < 10 &&
                    Math.abs(lastSaved.getPercent() - position.getPercent()) < 1.0) {
                return;
            }
        }

        ReadingProgressDto dto = ReadingProgressDto.builder()
                .bookId(position.getBookId())
                .paragraphId(position.getParagraphId())
                .charOffset(Math.max(0, position.getCharOffset()))
                .percent(position.getPercent())
                .updatedAt(LocalDateTime.now())
                .build();

        repository.save(dto);
        lastSavedPositions.put(sessionKey, position);

        log.debug("Saved position for book {}: {}%, paragraph {}",
                position.getBookId(), (int) position.getPercent(), position.getParagraphIndex());
    }

    /**
     * Завантажує збережену позицію.
     */
    public Optional<ReaderPosition> loadPosition(String bookId) {
        if (collectionLifecyclePort == null || !collectionLifecyclePort.hasActiveCollection()) {
            return Optional.empty();
        }

        return repository.findByBookId(bookId)
                .map(dto -> ReaderPosition.builder()
                        .bookId(bookId)
                        .paragraphId(dto.getParagraphId())
                        .charOffset(dto.getCharOffset())
                        .percent(dto.getPercent())
                        .paragraphIndex(extractParagraphIndex(dto.getParagraphId()))
                        .build());
    }

    private int extractParagraphIndex(String paragraphId) {
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

    /**
     * Відновлює позицію в WebView.
     */
    public boolean restorePosition(ReaderSession session, ReaderPosition position) {
        if (session == null || session.getWebEngine() == null || !session.isActive()) {
            return false;
        }

        if (position == null) {
            // Початок книги
            try {
                session.getWebEngine().executeScript("window.scrollTo(0, 0)");
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        int total = jsBridge.getParagraphCount(session.getWebEngine());
        int index = position.getParagraphIndex();

        if (total > 0 && index >= total) {
            index = total - 1;
        }
        if (index < 0) {
            index = 0;
        }

        boolean success = jsBridge.scrollToParagraph(
                session.getWebEngine(),
                index,
                position.getCharOffset()
        );

        if (success) {
            log.info("Restored position for book {}: {}%, paragraph {}",
                    position.getBookId(), (int) position.getPercent(), index);
        } else {
            // Fallback: скрол за відсотком
            try {
                double percent = position.getPercent() / 100.0;
                String script = "window.scrollTo(0, (document.documentElement.scrollHeight - document.documentElement.clientHeight) * " + percent + ")";
                session.getWebEngine().executeScript(script);
                return true;
            } catch (Exception e) {
                log.warn("Failed to restore position via fallback", e);
                return false;
            }
        }

        return success;
    }

    public void clearCache() {
        lastSavedPositions.clear();
    }
}