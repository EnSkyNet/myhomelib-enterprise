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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private static final long SAVE_DELAY_MS = 1500;
    private static final long RESTORE_STABILIZATION_MS = 2000;

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
                    function getPositionFromViewport() {
                        var paragraphs = document.querySelectorAll('p[data-anchor-id], p[data-paragraph-id]');
                        if (paragraphs.length === 0) {
                            return getPositionFromScroll();
                        }
                        
                        var viewportHeight = window.innerHeight;
                        var readingLine = viewportHeight * 0.15;
                        
                        var best = null;
                        var bestIndex = -1;
                        var bestDistance = Infinity;
                        
                        for (var i = 0; i < paragraphs.length; i++) {
                            var rect = paragraphs[i].getBoundingClientRect();
                            
                            if (rect.bottom <= 0 || rect.top >= viewportHeight) {
                                continue;
                            }
                            
                            var distance = Math.abs(rect.top - readingLine);
                            if (distance < bestDistance) {
                                bestDistance = distance;
                                best = paragraphs[i];
                                bestIndex = i;
                            }
                        }
                        
                        if (!best) {
                            return getPositionFromScroll();
                        }
                        
                        var anchorId = best.getAttribute('data-anchor-id') || 
                                       best.getAttribute('data-paragraph-id') || '';
                        
                        var charOffset = 0;
                        var rect = best.getBoundingClientRect();
                        var x = rect.left + 10;
                        var y = rect.top + Math.max(10, rect.height * 0.3);
                        
                        var range = document.caretRangeFromPoint(x, y);
                        if (range && range.startContainer) {
                            var textNode = range.startContainer;
                            if (textNode.nodeType === Node.TEXT_NODE) {
                                charOffset = range.startOffset;
                            }
                        } else {
                            var text = best.innerText || '';
                            if (text.length > 0) {
                                var relativeY = (readingLine - rect.top) / Math.max(1, rect.height);
                                relativeY = Math.max(0, Math.min(1, relativeY));
                                charOffset = Math.floor(relativeY * text.length);
                            }
                        }
                        
                        var scrollTop = document.documentElement.scrollTop || document.body.scrollTop || 0;
                        var docHeight = document.documentElement.scrollHeight;
                        var clientHeight = document.documentElement.clientHeight;
                        var scrollHeight = docHeight - clientHeight;
                        var percent = scrollHeight > 0 ? scrollTop / scrollHeight : 0;
                        
                        var chapterTitle = '';
                        var chapterEl = best.closest('.chapter');
                        if (chapterEl) {
                            var titleEl = chapterEl.querySelector('.chapter-title');
                            if (titleEl) {
                                chapterTitle = titleEl.innerText || '';
                            }
                        }
                        
                        return {
                            anchorId: anchorId,
                            paragraphIndex: bestIndex,
                            charOffset: charOffset,
                            percent: percent * 100,
                            chapterTitle: chapterTitle,
                            totalParagraphs: paragraphs.length
                        };
                    }
                    
                    function getPositionFromScroll() {
                        var scrollTop = document.documentElement.scrollTop || document.body.scrollTop || 0;
                        var docHeight = document.documentElement.scrollHeight;
                        var clientHeight = document.documentElement.clientHeight;
                        var scrollHeight = docHeight - clientHeight;
                        var percent = scrollHeight > 0 ? scrollTop / scrollHeight : 0;
                        
                        var paragraphs = document.querySelectorAll('p[data-anchor-id], p[data-paragraph-id]');
                        var anchorId = '';
                        var paragraphIndex = 0;
                        
                        if (paragraphs.length > 0) {
                            var index = Math.floor(percent * paragraphs.length);
                            index = Math.max(0, Math.min(index, paragraphs.length - 1));
                            var el = paragraphs[index];
                            anchorId = el.getAttribute('data-anchor-id') || 
                                       el.getAttribute('data-paragraph-id') || '';
                            paragraphIndex = index;
                        }
                        
                        return {
                            anchorId: anchorId,
                            paragraphIndex: paragraphIndex,
                            charOffset: 0,
                            percent: percent * 100,
                            chapterTitle: '',
                            totalParagraphs: paragraphs.length
                        };
                    }
                    
                    var result = getPositionFromViewport();
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

            String anchorId = node.has("anchorId") ? node.get("anchorId").asText() : "";
            int paragraphIndex = node.has("paragraphIndex") ? node.get("paragraphIndex").asInt() : 0;
            int charOffset = node.has("charOffset") ? node.get("charOffset").asInt() : 0;
            double percent = node.has("percent") ? node.get("percent").asDouble() : 0;
            String chapterTitle = node.has("chapterTitle") ? node.get("chapterTitle").asText() : "";

            percent = Math.max(0, Math.min(100, percent));

            return ReaderPosition.builder()
                    .bookId(bookId)
                    .anchorId(anchorId)
                    .paragraphIndex(Math.max(0, paragraphIndex))
                    .charOffset(Math.max(0, Math.min(charOffset, 10000)))
                    .percent(percent)
                    .chapterTitle(chapterTitle != null ? chapterTitle : "")
                    .build();

        } catch (Exception e) {
            log.warn("Failed to parse position JSON: {}", json, e);
            return null;
        }
    }

    // ==================== ВІДНОВЛЕННЯ ====================

    /**
     * ВІДНОВЛЕННЯ ПОЗИЦІЇ - простий і надійний.
     */
    public void restorePosition(ReaderSession session, ReaderPosition position, Runnable onComplete) {
        if (session == null || session.getWebEngine() == null || !session.isActive()) {
            if (onComplete != null) {
                onComplete.run();
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
                String anchorId = position.getAnchorId() != null && !position.getAnchorId().isEmpty()
                        ? position.getAnchorId()
                        : String.valueOf(position.getParagraphIndex());
                int charOffset = position.getCharOffset();

                log.info("🔍 Restoring position: anchorId={}, charOffset={}", anchorId, charOffset);

                String script = buildRestoreScript(anchorId, charOffset, position.getPercent());

                session.getWebEngine().executeScript(script);
                log.info("✅ Restored position: anchor={}, charOffset={}, percent={}%",
                        anchorId, charOffset, (int)position.getPercent());

            } catch (Exception e) {
                log.warn("Failed to restore position: {}", e.getMessage(), e);
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

    private String buildRestoreScript(String anchorId, int charOffset, double percent) {
        String escapedAnchorId = escapeJsString(anchorId);

        StringBuilder script = new StringBuilder();
        script.append("(function() {\n");
        script.append("    var anchorId = '").append(escapedAnchorId).append("';\n");
        script.append("    var charOffset = ").append(charOffset).append(";\n");
        script.append("    var percent = ").append(String.format(java.util.Locale.US, "%.6f", percent / 100.0)).append(";\n");
        script.append("    \n");
        script.append("    var el = document.querySelector('[data-anchor-id=\"' + anchorId + '\"]');\n");
        script.append("    if (!el) {\n");
        script.append("        el = document.querySelector('[data-paragraph-id=\"' + anchorId + '\"]');\n");
        script.append("    }\n");
        script.append("    if (!el) {\n");
        script.append("        var index = parseInt(anchorId);\n");
        script.append("        if (!isNaN(index)) {\n");
        script.append("            var paragraphs = document.querySelectorAll('p[data-anchor-id], p[data-paragraph-id]');\n");
        script.append("            if (index >= 0 && index < paragraphs.length) {\n");
        script.append("                el = paragraphs[index];\n");
        script.append("            }\n");
        script.append("        }\n");
        script.append("    }\n");
        script.append("    \n");
        script.append("    if (!el) {\n");
        script.append("        var scrollHeight = document.documentElement.scrollHeight - document.documentElement.clientHeight;\n");
        script.append("        var targetScroll = scrollHeight * percent;\n");
        script.append("        if (targetScroll < 0) targetScroll = 0;\n");
        script.append("        window.scrollTo({ top: targetScroll, behavior: 'auto' });\n");
        script.append("        return;\n");
        script.append("    }\n");
        script.append("    \n");
        script.append("    var textNodes = [];\n");
        script.append("    var walker = document.createTreeWalker(\n");
        script.append("        el,\n");
        script.append("        NodeFilter.SHOW_TEXT,\n");
        script.append("        {\n");
        script.append("            acceptNode: function(node) {\n");
        script.append("                var text = node.textContent;\n");
        script.append("                if (text && text.trim().length > 0) {\n");
        script.append("                    return NodeFilter.FILTER_ACCEPT;\n");
        script.append("                }\n");
        script.append("                return NodeFilter.FILTER_REJECT;\n");
        script.append("            }\n");
        script.append("        }\n");
        script.append("    );\n");
        script.append("    \n");
        script.append("    var node;\n");
        script.append("    while (node = walker.nextNode()) {\n");
        script.append("        textNodes.push(node);\n");
        script.append("    }\n");
        script.append("    \n");
        script.append("    var targetNode = null;\n");
        script.append("    var targetOffset = 0;\n");
        script.append("    var currentOffset = 0;\n");
        script.append("    \n");
        script.append("    if (textNodes.length > 0) {\n");
        script.append("        for (var i = 0; i < textNodes.length; i++) {\n");
        script.append("            var textNode = textNodes[i];\n");
        script.append("            var nodeText = textNode.textContent;\n");
        script.append("            if (currentOffset + nodeText.length >= charOffset) {\n");
        script.append("                targetNode = textNode;\n");
        script.append("                targetOffset = charOffset - currentOffset;\n");
        script.append("                targetOffset = Math.min(targetOffset, nodeText.length);\n");
        script.append("                break;\n");
        script.append("            }\n");
        script.append("            currentOffset += nodeText.length;\n");
        script.append("        }\n");
        script.append("        if (!targetNode && textNodes.length > 0) {\n");
        script.append("            targetNode = textNodes[textNodes.length - 1];\n");
        script.append("            targetOffset = targetNode.textContent.length;\n");
        script.append("        }\n");
        script.append("    }\n");
        script.append("    \n");
        script.append("    var range = document.createRange();\n");
        script.append("    if (targetNode) {\n");
        script.append("        range.setStart(targetNode, Math.min(targetOffset, targetNode.textContent.length));\n");
        script.append("        range.setEnd(targetNode, Math.min(targetOffset, targetNode.textContent.length));\n");
        script.append("    } else {\n");
        script.append("        range.selectNodeContents(el);\n");
        script.append("    }\n");
        script.append("    \n");
        script.append("    var rect = range.getClientRects()[0];\n");
        script.append("    if (rect) {\n");
        script.append("        var targetY = rect.top + window.scrollY - 80;\n");
        script.append("        if (targetY < 0) targetY = 0;\n");
        script.append("        window.scrollTo({ top: targetY, behavior: 'auto' });\n");
        script.append("    } else {\n");
        script.append("        el.scrollIntoView({ block: 'start' });\n");
        script.append("    }\n");
        script.append("    \n");
        script.append("    return;\n");
        script.append("})();\n");

        return script.toString();
    }

    private String escapeJsString(String str) {
        if (str == null) {
            return "";
        }
        return str.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ==================== Збереження позиції ====================

    public void scheduleSave(ReaderSession session) {
        if (session == null || session.getBookId() == null) {
            return;
        }

        String sessionId = session.getSessionId();

        ScheduledFuture<?> oldTask = saveTasks.remove(sessionId);
        if (oldTask != null) {
            oldTask.cancel(false);
        }

        ScheduledFuture<?> newTask = scheduler.schedule(() -> {
            saveTasks.remove(sessionId);
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

        saveTasks.put(sessionId, newTask);
        log.trace("⏳ Position save scheduled for session: {}", sessionId);
    }

    public boolean savePositionNow(ReaderSession session) {
        if (session == null || !session.isActive()) {
            log.warn("Cannot save position: session is null or inactive");
            return false;
        }

        if (javafx.application.Platform.isFxApplicationThread()) {
            return savePositionSyncOnFx(session);
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        scheduler.runOnFxThread(() -> {
            try {
                future.complete(savePositionSyncOnFx(session));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        try {
            return future.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Failed to save position", e);
            return false;
        }
    }

    private boolean savePositionSyncOnFx(ReaderSession session) {
        if (!javafx.application.Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Must be called on FX application thread");
        }

        if (session == null || !session.isActive()) {
            return false;
        }

        try {
            ReaderPosition pos = getPositionSync(session);
            if (pos == null) {
                return false;
            }
            savePosition(pos);
            log.debug("✅ Position saved synchronously: anchor={}, charOffset={}, percent={}%",
                    pos.getAnchorId(), pos.getCharOffset(), (int)pos.getPercent());
            return true;
        } catch (Exception e) {
            log.error("Failed to save position synchronously", e);
            return false;
        }
    }

    private boolean isPositionChanged(ReaderPosition newPos) {
        if (newPos == null) {
            return false;
        }

        ReaderPosition lastSaved = lastSavedPositions.get(newPos.getBookId());
        if (lastSaved == null) {
            return true;
        }

        String newId = newPos.getAnchorId() != null && !newPos.getAnchorId().isEmpty()
                ? newPos.getAnchorId() : String.valueOf(newPos.getParagraphIndex());
        String oldId = lastSaved.getAnchorId() != null && !lastSaved.getAnchorId().isEmpty()
                ? lastSaved.getAnchorId() : String.valueOf(lastSaved.getParagraphIndex());

        boolean idChanged = !newId.equals(oldId);
        boolean offsetChanged = Math.abs(lastSaved.getCharOffset() - newPos.getCharOffset()) > 10;
        boolean percentChanged = Math.abs(lastSaved.getPercent() - newPos.getPercent()) > 1.0;

        return idChanged || offsetChanged || percentChanged;
    }

    private void savePosition(ReaderPosition position) {
        if (position == null || position.getBookId() == null) {
            return;
        }

        if (collectionLifecyclePort == null || !collectionLifecyclePort.hasActiveCollection()) {
            return;
        }

        if (!isPositionChanged(position)) {
            return;
        }

        try {
            String stableId = position.getAnchorId() != null && !position.getAnchorId().isEmpty()
                    ? position.getAnchorId()
                    : String.valueOf(position.getParagraphIndex());

            String paragraphId = stableId;
            String chapterTitle = position.getChapterTitle();
            if (chapterTitle == null) {
                chapterTitle = "";
            }

            ReadingProgressDto dto = ReadingProgressDto.builder()
                    .bookId(position.getBookId())
                    .anchorId(stableId)
                    .paragraphIndex(position.getParagraphIndex())
                    .paragraphId(paragraphId)
                    .charOffset(Math.max(0, Math.min(position.getCharOffset(), 10000)))
                    .percent(Math.max(0, Math.min(100, position.getPercent())))
                    .chapterTitle(chapterTitle)
                    .chapterId("")
                    .updatedAt(LocalDateTime.now())
                    .readingTimeSeconds(0)
                    .build();

            repository.save(dto);
            lastSavedPositions.put(position.getBookId(), position);

            if (log.isDebugEnabled()) {
                log.debug("✅ Saved position: anchor={}, charOffset={}, percent={}%, chapter={}",
                        stableId, position.getCharOffset(),
                        (int)position.getPercent(), chapterTitle);
            }
        } catch (Exception e) {
            log.warn("Failed to save position: {}", e.getMessage(), e);
        }
    }

    // ==================== Завантаження ====================

    public Optional<ReaderPosition> loadPosition(String bookId) {
        if (collectionLifecyclePort == null || !collectionLifecyclePort.hasActiveCollection()) {
            return Optional.empty();
        }

        try {
            return repository.findByBookId(bookId)
                    .map(dto -> {
                        String savedId = dto.getAnchorId() != null && !dto.getAnchorId().isEmpty()
                                ? dto.getAnchorId()
                                : dto.getParagraphId();

                        if (savedId == null || savedId.isEmpty()) {
                            savedId = String.valueOf(dto.getParagraphIndex());
                        }

                        log.info("📖 Loading position: savedId={}", savedId);

                        ReaderPosition pos = ReaderPosition.builder()
                                .bookId(bookId)
                                .anchorId(savedId)
                                .paragraphIndex(dto.getParagraphIndex())
                                .charOffset(dto.getCharOffset())
                                .percent(dto.getPercent())
                                .chapterTitle(dto.getChapterTitle() != null ? dto.getChapterTitle() : "")
                                .build();

                        log.info("📖 Loaded position: anchor={}, percent={}%",
                                pos.getAnchorId(), (int)pos.getPercent());

                        lastSavedPositions.put(bookId, pos);
                        return pos;
                    });
        } catch (Exception e) {
            log.warn("Failed to load position for book {}: {}", bookId, e.getMessage());
            return Optional.empty();
        }
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
        log.info("Reader position cache cleared");
    }
}