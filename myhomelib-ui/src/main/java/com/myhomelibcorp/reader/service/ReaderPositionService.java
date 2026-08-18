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

    // Зберігаємо останню збережену позицію для кожної книги
    private final ConcurrentMap<String, ReaderPosition> lastSavedPositions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ScheduledFuture<?>> saveTasks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> lastSaveTime = new ConcurrentHashMap<>();

    private static final long SAVE_DELAY_MS = 1500; // 1.5 секунди debounce
    private static final long MIN_SAVE_INTERVAL_MS = 2000; // 2 секунди між збереженнями

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== Отримання позиції ====================

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
            String script = """
                (function() {
                    function getSelectionPosition() {
                        var sel = window.getSelection();
                        if (!sel || sel.rangeCount === 0) {
                            return getScrollPosition();
                        }
                        
                        var range = sel.getRangeAt(0);
                        var startContainer = range.startContainer;
                        var startOffset = range.startOffset;
                        
                        var paragraph = findParentParagraph(startContainer);
                        if (!paragraph) {
                            return getScrollPosition();
                        }
                        
                        var paragraphId = paragraph.getAttribute('data-paragraph-id');
                        if (!paragraphId) {
                            return getScrollPosition();
                        }
                        
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
                                charOffset += Math.min(startOffset, textNode.textContent.length);
                                found = true;
                                break;
                            } else {
                                charOffset += textNode.textContent.length;
                            }
                        }
                        
                        if (!found) {
                            var paraRect = paragraph.getBoundingClientRect();
                            var rangeRect = range.getBoundingClientRect();
                            if (paraRect.height > 0 && rangeRect.height > 0) {
                                var text = paragraph.innerText || '';
                                var ratio = (rangeRect.top - paraRect.top) / paraRect.height;
                                charOffset = Math.max(0, Math.min(Math.floor(ratio * text.length), text.length));
                            }
                        }
                        
                        var scrollTop = document.documentElement.scrollTop || document.body.scrollTop || 0;
                        var scrollHeight = document.documentElement.scrollHeight - document.documentElement.clientHeight;
                        var percent = scrollHeight > 0 ? scrollTop / scrollHeight : 0;
                        
                        var chapterId = '';
                        var chapterTitle = '';
                        var chapterEl = paragraph.closest('.chapter');
                        if (chapterEl) {
                            chapterId = chapterEl.getAttribute('data-chapter-id') || '';
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
                            chapterId: chapterId,
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
                                chapterId: '',
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
                        var paraId = el.getAttribute('data-paragraph-id') || '';
                        var text = el.innerText || '';
                        var totalHeight = el.getBoundingClientRect().height || 1;
                        var visibleTop = Math.max(el.getBoundingClientRect().top, 0);
                        var visibleBottom = Math.min(el.getBoundingClientRect().bottom, window.innerHeight);
                        var visibleHeight = Math.max(0, visibleBottom - visibleTop);
                        var ratio = Math.min(1, Math.max(0, visibleHeight / totalHeight));
                        var charOffset = Math.floor(ratio * text.length);
                        
                        var chapterId = '';
                        var chapterTitle = '';
                        var chapterEl = el.closest('.chapter');
                        if (chapterEl) {
                            chapterId = chapterEl.getAttribute('data-chapter-id') || '';
                            var titleEl = chapterEl.querySelector('.chapter-title');
                            if (titleEl) {
                                chapterTitle = titleEl.innerText || '';
                            }
                        }
                        
                        return {
                            paragraphId: paraId,
                            paragraphIndex: firstVisible,
                            charOffset: charOffset,
                            percent: percent,
                            chapterId: chapterId,
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
            String chapterId = node.has("chapterId") ? node.get("chapterId").asText() : "";
            String chapterTitle = node.has("chapterTitle") ? node.get("chapterTitle").asText() : "";

            return ReaderPosition.builder()
                    .bookId(bookId)
                    .paragraphId(paragraphId)
                    .paragraphIndex(paragraphIndex)
                    .charOffset(Math.max(0, Math.min(charOffset, 10000)))
                    .percent(percent)
                    .chapterId(chapterId)
                    .chapterTitle(chapterTitle)
                    .build();

        } catch (Exception e) {
            log.warn("Failed to parse position JSON: {}", json, e);
            return null;
        }
    }

    // ==================== Збереження позиції ====================

    /**
     * Заплановане збереження позиції з debounce.
     * Викликається при будь-якій зміні позиції.
     */
    public void scheduleSave(ReaderSession session) {
        if (session == null || session.getBookId() == null) {
            return;
        }

        String sessionKey = session.getSessionId();

        // Відміняємо попереднє заплановане збереження
        ScheduledFuture<?> oldTask = saveTasks.remove(sessionKey);
        if (oldTask != null) {
            oldTask.cancel(false);
        }

        // Плануємо нове збереження з debounce
        ScheduledFuture<?> newTask = scheduler.schedule(() -> {
            saveTasks.remove(sessionKey);
            if (session.isActive()) {
                scheduler.runOnFxThread(() -> {
                    if (session.isActive()) {
                        ReaderPosition currentPos = getPositionSync(session);
                        if (currentPos != null && isPositionChanged(currentPos)) {
                            savePosition(currentPos);
                        }
                    }
                });
            }
        }, SAVE_DELAY_MS, TimeUnit.MILLISECONDS);

        saveTasks.put(sessionKey, newTask);
        log.trace("⏳ Position save scheduled for session: {}", sessionKey);
    }

    /**
     * Примусове збереження позиції (без debounce).
     * Використовується при закритті книги.
     */
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

    /**
     * Перевіряє, чи змінилася позиція (навіть на 1 символ).
     */
    private boolean isPositionChanged(ReaderPosition newPos) {
        if (newPos == null) {
            return false;
        }

        ReaderPosition lastSaved = lastSavedPositions.get(newPos.getBookId());
        if (lastSaved == null) {
            return true; // Немає збереженої позиції - потрібно зберегти
        }

        // Перевіряємо зміну параграфа
        boolean paragraphChanged = !lastSaved.getParagraphId().equals(newPos.getParagraphId());

        // Перевіряємо зміну charOffset (навіть на 1 символ)
        boolean charOffsetChanged = Math.abs(lastSaved.getCharOffset() - newPos.getCharOffset()) > 1;

        // Перевіряємо зміну відсотка (більше ніж на 0.1%)
        boolean percentChanged = Math.abs(lastSaved.getPercent() - newPos.getPercent()) > 0.1;

        return paragraphChanged || charOffsetChanged || percentChanged;
    }

    /**
     * Внутрішній метод збереження позиції.
     */
    private void savePosition(ReaderPosition position) {
        if (position == null || position.getBookId() == null) {
            return;
        }

        if (collectionLifecyclePort == null || !collectionLifecyclePort.hasActiveCollection()) {
            return;
        }

        // Перевіряємо, чи змінилася позиція
        if (!isPositionChanged(position)) {
            log.trace("Position unchanged, skipping save");
            return;
        }

        // Перевіряємо інтервал між збереженнями
        Long lastTime = lastSaveTime.get(position.getBookId());
        if (lastTime != null && System.currentTimeMillis() - lastTime < MIN_SAVE_INTERVAL_MS) {
            log.trace("Too frequent save, skipping (last save {} ms ago)",
                    System.currentTimeMillis() - lastTime);
            return;
        }

        try {
            int safeCharOffset = Math.max(0, Math.min(position.getCharOffset(), 10000));

            ReadingProgressDto dto = ReadingProgressDto.builder()
                    .bookId(position.getBookId())
                    .paragraphId(position.getParagraphId())
                    .charOffset(safeCharOffset)
                    .percent(Math.max(0, Math.min(100, position.getPercent())))
                    .updatedAt(LocalDateTime.now())
                    .build();

            repository.save(dto);
            lastSavedPositions.put(position.getBookId(), position);
            lastSaveTime.put(position.getBookId(), System.currentTimeMillis());

            log.debug("✅ Saved position: paragraph={}, charOffset={}, percent={}%",
                    position.getParagraphId(), safeCharOffset, (int)position.getPercent());
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
                    .map(dto -> {
                        ReaderPosition pos = ReaderPosition.builder()
                                .bookId(bookId)
                                .paragraphId(dto.getParagraphId())
                                .charOffset(dto.getCharOffset())
                                .percent(dto.getPercent())
                                .paragraphIndex(extractParagraphIndex(dto.getParagraphId()))
                                .build();
                        // Відновлюємо останню збережену позицію в кеш
                        lastSavedPositions.put(bookId, pos);
                        return pos;
                    });
        } catch (Exception e) {
            log.warn("Failed to load position for book {}: {}", bookId, e.getMessage());
            return Optional.empty();
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

        if (position == null || position.getParagraphId() == null || position.getParagraphId().isEmpty()) {
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
                // Спроба відновлення за paragraphId
                boolean success = scrollToParagraphById(session, position.getParagraphId(), position.getCharOffset());
                if (success) {
                    log.info("Restored position by paragraphId: {}, charOffset: {}",
                            position.getParagraphId(), position.getCharOffset());
                    if (onComplete != null) {
                        onComplete.run();
                    }
                    return;
                }

                // Fallback: використовуємо індекс
                int index = position.getParagraphIndex();
                int total = jsBridge.getParagraphCount(session.getWebEngine());
                if (total > 0 && index >= total) {
                    index = total - 1;
                }
                if (index < 0) {
                    index = 0;
                }

                success = jsBridge.scrollToParagraph(session.getWebEngine(), index, position.getCharOffset());
                if (success) {
                    log.info("Restored position by index: {}, charOffset: {}",
                            index, position.getCharOffset());
                } else {
                    // Другий fallback: використовуємо відсоток
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

    private boolean scrollToParagraphById(ReaderSession session, String paragraphId, int charOffset) {
        if (session == null || session.getWebEngine() == null) {
            return false;
        }

        try {
            String script = """
                (function() {
                    var paragraphId = '%s';
                    var charOffset = %d;
                    
                    var paragraph = document.querySelector('p[data-paragraph-id="' + paragraphId + '"]');
                    if (!paragraph) return false;
                    
                    paragraph.scrollIntoView({ block: 'start' });
                    
                    if (charOffset > 0) {
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
                        
                        var currentOffset = 0;
                        for (var i = 0; i < textNodes.length; i++) {
                            var textNode = textNodes[i];
                            var nodeText = textNode.textContent;
                            if (currentOffset + nodeText.length >= charOffset) {
                                var localOffset = charOffset - currentOffset;
                                try {
                                    var range = document.createRange();
                                    range.setStart(textNode, Math.min(localOffset, nodeText.length));
                                    range.setEnd(textNode, Math.min(localOffset, nodeText.length));
                                    var sel = window.getSelection();
                                    sel.removeAllRanges();
                                    sel.addRange(range);
                                } catch(e) {}
                                break;
                            }
                            currentOffset += nodeText.length;
                        }
                    }
                    
                    return true;
                })();
            """.formatted(paragraphId, charOffset);

            Object result = session.getWebEngine().executeScript(script);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.debug("Failed to scroll to paragraph by id: {}", e.getMessage());
            return false;
        }
    }

    public void restorePosition(ReaderSession session, ReaderPosition position) {
        restorePosition(session, position, null);
    }

    // ==================== КЕШ ====================

    public void clearCache() {
        lastSavedPositions.clear();
        for (ScheduledFuture<?> task : saveTasks.values()) {
            if (task != null) {
                task.cancel(false);
            }
        }
        saveTasks.clear();
        lastSaveTime.clear();
        log.info("Reader position cache cleared");
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
}