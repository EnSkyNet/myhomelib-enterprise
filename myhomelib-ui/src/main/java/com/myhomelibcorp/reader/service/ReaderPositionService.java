package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.application.dto.ReadingProgressDto;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import com.myhomelibcorp.reader.model.ReaderPosition;
import com.myhomelibcorp.reader.session.ReaderSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReaderPositionService {

    private final ReadingProgressRepository repository;
    private final CollectionLifecyclePort collectionLifecyclePort;
    private final ReaderJsBridge jsBridge;
    private final ReaderScheduler scheduler;

    private final ConcurrentMap<String, ReaderPosition> lastSavedPositions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ScheduledFuture<?>> saveTasks = new ConcurrentHashMap<>();

    private static final long SAVE_DELAY_MS = 1000;
    private static final long SAVE_INTERVAL_SECONDS = 5;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== Отримання позиції з DOM Range ====================

    public ReaderPosition getCurrentPosition(ReaderSession session) {
        if (session == null || session.getWebEngine() == null || !session.isActive()) {
            return null;
        }

        if (!jsBridge.isContentLoaded(session.getWebEngine())) {
            return null;
        }

        CompletableFuture<ReaderPosition> future = new CompletableFuture<>();
        scheduler.runOnFxThread(() -> {
            try {
                future.complete(getPositionSync(session));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        try {
            return future.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Failed to get position: {}", e.getMessage());
            return null;
        }
    }

    private ReaderPosition getPositionSync(ReaderSession session) {
        if (!javafx.application.Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Must be called on FX application thread");
        }

        var engine = session.getWebEngine();
        if (engine == null || !jsBridge.isContentLoaded(engine)) {
            return null;
        }

        try {
            // ВИПРАВЛЕНО: використовуємо DOM Range для точного визначення позиції
            String script = """
                (function() {
                    function getSelectionPosition() {
                        var sel = window.getSelection();
                        if (!sel || sel.rangeCount === 0) {
                            // Якщо немає виділення, використовуємо скрол
                            return getScrollPosition();
                        }
                        
                        var range = sel.getRangeAt(0);
                        var startContainer = range.startContainer;
                        var startOffset = range.startOffset;
                        
                        // Знаходимо найближчий параграф
                        var paragraph = findParentParagraph(startContainer);
                        if (!paragraph) {
                            return getScrollPosition();
                        }
                        
                        var paragraphId = paragraph.getAttribute('data-paragraph-id');
                        if (!paragraphId) {
                            return getScrollPosition();
                        }
                        
                        // Знаходимо індекс параграфа
                        var allParagraphs = document.querySelectorAll('p[data-paragraph-id]');
                        var index = -1;
                        for (var i = 0; i < allParagraphs.length; i++) {
                            if (allParagraphs[i] === paragraph) {
                                index = i;
                                break;
                            }
                        }
                        
                        if (index === -1) {
                            return getScrollPosition();
                        }
                        
                        // Обчислюємо точний charOffset всередині параграфа
                        var textNodes = [];
                        var walker = document.createTreeWalker(
                            paragraph,
                            NodeFilter.SHOW_TEXT,
                            {
                                acceptNode: function(node) {
                                    var text = node.textContent;
                                    if (text && text.trim().length > 0) {
                                        return NodeFilter.FILTER_ACCEPT;
                                    }
                                    return NodeFilter.FILTER_REJECT;
                                }
                            }
                        );
                        
                        var node;
                        while (node = walker.nextNode()) {
                            textNodes.push(node);
                        }
                        
                        var charOffset = 0;
                        var found = false;
                        for (var i = 0; i < textNodes.length; i++) {
                            var textNode = textNodes[i];
                            if (textNode === startContainer) {
                                charOffset += startOffset;
                                found = true;
                                break;
                            } else {
                                charOffset += textNode.textContent.length;
                            }
                        }
                        
                        // Якщо не знайшли точне положення - використовуємо приблизне
                        if (!found) {
                            charOffset = Math.floor(range.getBoundingClientRect().top / paragraph.getBoundingClientRect().height * paragraph.innerText.length);
                        }
                        
                        // Обчислюємо відсоток прокрутки
                        var scrollTop = document.documentElement.scrollTop || document.body.scrollTop || 0;
                        var scrollHeight = document.documentElement.scrollHeight - document.documentElement.clientHeight;
                        var percent = scrollHeight > 0 ? scrollTop / scrollHeight : 0;
                        
                        // Знаходимо назву розділу
                        var chapterTitle = '';
                        var chapterEl = paragraph.closest('.chapter');
                        if (chapterEl) {
                            var titleEl = chapterEl.querySelector('.chapter-title');
                            if (titleEl) {
                                chapterTitle = titleEl.innerText || '';
                            }
                        }
                        
                        return {
                            paragraphId: paragraphId,
                            paragraphIndex: index,
                            charOffset: charOffset,
                            percent: percent,
                            chapterTitle: chapterTitle,
                            totalParagraphs: allParagraphs.length
                        };
                    }
                    
                    function findParentParagraph(node) {
                        while (node && node.nodeType !== Node.ELEMENT_NODE) {
                            node = node.parentNode;
                        }
                        while (node) {
                            if (node.tagName === 'P' && node.getAttribute('data-paragraph-id')) {
                                return node;
                            }
                            node = node.parentNode;
                        }
                        return null;
                    }
                    
                    function getScrollPosition() {
                        var paragraphs = document.querySelectorAll('p[data-paragraph-id]');
                        if (paragraphs.length === 0) {
                            return {
                                paragraphId: '',
                                paragraphIndex: 0,
                                charOffset: 0,
                                percent: 0,
                                chapterTitle: '',
                                totalParagraphs: 0
                            };
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
                        
                        return {
                            paragraphId: el.getAttribute('data-paragraph-id') || '',
                            paragraphIndex: firstVisible,
                            charOffset: charOffset,
                            percent: percent,
                            chapterTitle: chapterTitle,
                            totalParagraphs: paragraphs.length
                        };
                    }
                    
                    var result = getSelectionPosition();
                    return JSON.stringify(result);
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
            JsonNode node = objectMapper.readTree(json);

            String paragraphId = node.has("paragraphId") ? node.get("paragraphId").asText() : "";
            int paragraphIndex = node.has("paragraphIndex") ? node.get("paragraphIndex").asInt() : 0;
            int charOffset = node.has("charOffset") ? node.get("charOffset").asInt() : 0;
            double percent = node.has("percent") ? node.get("percent").asDouble() * 100 : 0;
            String chapterTitle = node.has("chapterTitle") ? node.get("chapterTitle").asText() : "";
            int totalParagraphs = node.has("totalParagraphs") ? node.get("totalParagraphs").asInt() : 0;

            return ReaderPosition.builder()
                    .bookId(bookId)
                    .paragraphId(paragraphId)
                    .paragraphIndex(paragraphIndex)
                    .charOffset(charOffset)
                    .percent(percent)
                    .chapterTitle(chapterTitle)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse position JSON: {}", json, e);
            return null;
        }
    }

    // ==================== Збереження позиції ====================

    public void savePositionNow(ReaderSession session) {
        if (session == null || !session.isActive()) {
            return;
        }

        scheduler.runOnFxThread(() -> {
            if (session.isActive()) {
                ReaderPosition pos = getPositionSync(session);
                if (pos != null) {
                    savePosition(pos);
                }
            }
        });
    }

    public void scheduleSave(ReaderSession session) {
        if (session == null || session.getBookId() == null) {
            return;
        }

        String sessionKey = session.getSessionId();

        ScheduledFuture<?> oldTask = saveTasks.remove(sessionKey);
        if (oldTask != null) {
            oldTask.cancel(false);
        }

        ScheduledFuture<?> newTask = scheduler.schedule(() -> {
            saveTasks.remove(sessionKey);
            if (session.isActive()) {
                scheduler.runOnFxThread(() -> {
                    if (session.isActive()) {
                        ReaderPosition pos = getPositionSync(session);
                        if (pos != null) {
                            savePosition(pos);
                        }
                    }
                });
            }
        }, SAVE_DELAY_MS, TimeUnit.MILLISECONDS);

        saveTasks.put(sessionKey, newTask);
    }

    public void savePosition(ReaderPosition position) {
        if (position == null || position.getBookId() == null) {
            return;
        }

        if (collectionLifecyclePort == null || !collectionLifecyclePort.hasActiveCollection()) {
            return;
        }

        if (position.getPercent() < 1.0) {
            return;
        }

        String bookId = position.getBookId();

        ReaderPosition lastSaved = lastSavedPositions.get(bookId);
        if (lastSaved != null) {
            boolean sameParagraph = lastSaved.getParagraphId().equals(position.getParagraphId());
            boolean sameOffset = Math.abs(lastSaved.getCharOffset() - position.getCharOffset()) < 5;
            boolean samePercent = Math.abs(lastSaved.getPercent() - position.getPercent()) < 0.5;

            if (sameParagraph && sameOffset && samePercent) {
                return;
            }
        }

        try {
            ReadingProgressDto dto = ReadingProgressDto.builder()
                    .bookId(position.getBookId())
                    .paragraphId(position.getParagraphId())
                    .charOffset(Math.max(0, position.getCharOffset()))
                    .percent(Math.max(0, Math.min(100, position.getPercent())))
                    .updatedAt(LocalDateTime.now())
                    .build();

            repository.save(dto);
            lastSavedPositions.put(bookId, position);
            log.debug("Saved position for book {}: {}%, paragraph {}, charOffset {}",
                    position.getBookId(), (int) position.getPercent(), position.getParagraphIndex(), position.getCharOffset());
        } catch (Exception e) {
            log.warn("Failed to save position: {}", e.getMessage());
        }
    }

    // ==================== Завантаження позиції ====================

    public Optional<ReaderPosition> loadPosition(String bookId) {
        if (collectionLifecyclePort == null || !collectionLifecyclePort.hasActiveCollection()) {
            return Optional.empty();
        }

        try {
            return repository.findByBookId(bookId)
                    .map(dto -> ReaderPosition.builder()
                            .bookId(bookId)
                            .paragraphId(dto.getParagraphId())
                            .charOffset(dto.getCharOffset())
                            .percent(dto.getPercent())
                            .paragraphIndex(extractParagraphIndex(dto.getParagraphId()))
                            .build());
        } catch (Exception e) {
            log.warn("Failed to load position for book {}: {}", bookId, e.getMessage());
            return Optional.empty();
        }
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

    // ==================== Відновлення позиції ====================

    public void restorePosition(ReaderSession session, ReaderPosition position, Runnable onComplete) {
        if (session == null || session.getWebEngine() == null || !session.isActive()) {
            if (onComplete != null) {
                scheduler.runOnFxThread(onComplete);
            }
            return;
        }

        if (position == null) {
            scheduler.runOnFxThread(() -> {
                try {
                    session.getWebEngine().executeScript("window.scrollTo(0, 0)");
                } catch (Exception e) {
                    log.debug("Failed to scroll to top: {}", e.getMessage());
                }
                if (onComplete != null) {
                    onComplete.run();
                }
            });
            return;
        }

        scheduler.runOnFxThread(() -> {
            try {
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
                    log.info("Restored position for book {}: {}%, paragraph {}, charOffset {}",
                            position.getBookId(), (int) position.getPercent(), index, position.getCharOffset());
                } else {
                    // Fallback: використовуємо відсоток
                    double percent = position.getPercent() / 100.0;
                    String script = "window.scrollTo(0, (document.documentElement.scrollHeight - document.documentElement.clientHeight) * " + percent + ")";
                    session.getWebEngine().executeScript(script);
                    log.info("Restored position using fallback: {}%", (int) position.getPercent());
                }
            } catch (Exception e) {
                log.warn("Failed to restore position: {}", e.getMessage());
            } finally {
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        });
    }

    public void restorePosition(ReaderSession session, ReaderPosition position) {
        restorePosition(session, position, null);
    }

    // ==================== Періодичне збереження ====================

    public void startPeriodicSaving(ReaderSession session) {
        if (session == null || session.getSessionId() == null) {
            return;
        }

        String sessionKey = session.getSessionId() + "_periodic";

        ScheduledFuture<?> oldTask = saveTasks.remove(sessionKey);
        if (oldTask != null) {
            oldTask.cancel(false);
        }

        ScheduledFuture<?> newTask = scheduler.scheduleAtFixedRate(() -> {
            if (session.isActive()) {
                scheduler.runOnFxThread(() -> {
                    if (session.isActive()) {
                        ReaderPosition pos = getPositionSync(session);
                        if (pos != null) {
                            savePosition(pos);
                        }
                    }
                });
            }
        }, SAVE_INTERVAL_SECONDS, SAVE_INTERVAL_SECONDS, TimeUnit.SECONDS);

        saveTasks.put(sessionKey, newTask);
        log.debug("Started periodic saving for session: {}", session.getSessionId());
    }

    public void stopPeriodicSaving(ReaderSession session) {
        if (session == null || session.getSessionId() == null) {
            return;
        }

        String sessionKey = session.getSessionId() + "_periodic";
        ScheduledFuture<?> task = saveTasks.remove(sessionKey);
        if (task != null) {
            task.cancel(false);
            log.debug("Stopped periodic saving for session: {}", session.getSessionId());
        }
    }

    public void clearCache() {
        lastSavedPositions.clear();
        for (ScheduledFuture<?> task : saveTasks.values()) {
            if (task != null) {
                task.cancel(false);
            }
        }
        saveTasks.clear();
    }
}