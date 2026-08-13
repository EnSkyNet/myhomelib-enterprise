package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.application.dto.ReadingProgressDto;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import com.myhomelibcorp.reader.model.ReaderPosition;
import com.myhomelibcorp.reader.session.ReaderSession;
import javafx.application.Platform;
import javafx.scene.web.WebEngine;
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
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * Отримує поточну позицію з WebView через FX поток.
     */
    public ReaderPosition getCurrentPosition(ReaderSession session) {
        if (session == null || session.getWebEngine() == null || !session.isActive()) {
            return null;
        }

        WebEngine engine = session.getWebEngine();

        // Перевіряємо чи завантажено контент через FX поток
        if (!isContentLoadedOnFxThread(engine)) {
            return null;
        }

        // Виконуємо JS на FX потоці
        return getPositionOnFxThread(session);
    }

    /**
     * Перевіряє чи завантажено контент на FX потоці.
     */
    private boolean isContentLoadedOnFxThread(WebEngine engine) {
        if (engine == null) {
            return false;
        }

        if (Platform.isFxApplicationThread()) {
            return jsBridge.isContentLoaded(engine);
        }

        // Якщо не на FX потоці - виконуємо через Platform.runLater з очікуванням
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                boolean loaded = jsBridge.isContentLoaded(engine);
                future.complete(loaded);
            } catch (Exception e) {
                future.complete(false);
            }
        });

        try {
            return future.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("Failed to check content loaded: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Отримує позицію на FX потоці.
     */
    private ReaderPosition getPositionOnFxThread(ReaderSession session) {
        if (session == null || session.getWebEngine() == null) {
            return null;
        }

        if (Platform.isFxApplicationThread()) {
            return getPositionSync(session);
        }

        // Якщо не на FX потоці - виконуємо через Platform.runLater з очікуванням
        CompletableFuture<ReaderPosition> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                ReaderPosition pos = getPositionSync(session);
                future.complete(pos);
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

    /**
     * Синхронне отримання позиції (має викликатися тільки на FX потоці).
     */
    private ReaderPosition getPositionSync(ReaderSession session) {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Must be called on FX application thread");
        }

        WebEngine engine = session.getWebEngine();
        if (engine == null || !jsBridge.isContentLoaded(engine)) {
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
     * Зберігає позицію негайно (на FX потоці).
     */
    public void savePositionNow(ReaderSession session) {
        if (session == null || !session.isActive()) {
            return;
        }

        if (Platform.isFxApplicationThread()) {
            ReaderPosition pos = getPositionSync(session);
            if (pos != null) {
                savePosition(pos);
            }
        } else {
            // Якщо не на FX потоці - переносимо на FX
            Platform.runLater(() -> {
                if (session.isActive()) {
                    ReaderPosition pos = getPositionSync(session);
                    if (pos != null) {
                        savePosition(pos);
                    }
                }
            });
        }
    }

    /**
     * Планує збереження позиції з дебаунсом.
     */
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
                // Виконуємо на FX потоці
                Platform.runLater(() -> {
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

    /**
     * Зберігає позицію в базу даних.
     */
    public void savePosition(ReaderPosition position) {
        if (position == null || position.getBookId() == null) {
            return;
        }

        if (collectionLifecyclePort == null || !collectionLifecyclePort.hasActiveCollection()) {
            log.warn("No active collection, cannot save position");
            return;
        }

        if (position.getPercent() < 0.5 && position.getParagraphIndex() < 2) {
            return;
        }

        String bookId = position.getBookId();

        ReaderPosition lastSaved = lastSavedPositions.get(bookId);
        if (lastSaved != null) {
            if (lastSaved.getParagraphId().equals(position.getParagraphId()) &&
                    Math.abs(lastSaved.getCharOffset() - position.getCharOffset()) < 20 &&
                    Math.abs(lastSaved.getPercent() - position.getPercent()) < 2.0) {
                return;
            }
        }

        try {
            ReadingProgressDto dto = ReadingProgressDto.builder()
                    .bookId(position.getBookId())
                    .paragraphId(position.getParagraphId())
                    .charOffset(Math.max(0, position.getCharOffset()))
                    .percent(position.getPercent())
                    .updatedAt(LocalDateTime.now())
                    .build();

            repository.save(dto);
            lastSavedPositions.put(bookId, position);
            log.debug("Saved position for book {}: {}%, paragraph {}",
                    position.getBookId(), (int) position.getPercent(), position.getParagraphIndex());
        } catch (Exception e) {
            log.warn("Failed to save position: {}", e.getMessage());
        }
    }

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

    public boolean restorePosition(ReaderSession session, ReaderPosition position) {
        if (session == null || session.getWebEngine() == null || !session.isActive()) {
            return false;
        }

        if (position == null) {
            // Якщо позиції немає - скролимо на початок
            Platform.runLater(() -> {
                try {
                    session.getWebEngine().executeScript("window.scrollTo(0, 0)");
                } catch (Exception e) {
                    // ignore
                }
            });
            return true;
        }

        // Використовуємо AtomicReference для результату
        AtomicReference<Boolean> resultRef = new AtomicReference<>(false);

        Platform.runLater(() -> {
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
                    log.info("Restored position for book {}: {}%, paragraph {}",
                            position.getBookId(), (int) position.getPercent(), index);
                } else {
                    // Fallback: скрол за відсотком
                    double percent = position.getPercent() / 100.0;
                    String script = "window.scrollTo(0, (document.documentElement.scrollHeight - document.documentElement.clientHeight) * " + percent + ")";
                    session.getWebEngine().executeScript(script);
                    success = true;
                }

                resultRef.set(success);

            } catch (Exception e) {
                log.warn("Failed to restore position: {}", e.getMessage());
                resultRef.set(false);
            }
        });

        // Чекаємо максимум 2 секунди
        int attempts = 0;
        while (attempts < 20 && resultRef.get() == null) {
            try {
                Thread.sleep(100);
                attempts++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        return resultRef.get() != null && resultRef.get();
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
                // Виконуємо на FX потоці
                Platform.runLater(() -> {
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
}