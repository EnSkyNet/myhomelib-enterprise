package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.reader.core.ReaderSettings;
import jakarta.annotation.PreDestroy;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.web.WebEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReaderLifecycleManager {

    private final ReaderContentLoader contentLoader;
    private final ReaderProgressManager progressManager;
    private final ReaderScheduler scheduler;
    private final ReaderJsBridge jsBridge;

    private WebEngine webEngine;
    private BookDto currentBook;
    private boolean isRestoring = false;
    private ProgressBar progressBar;
    private Label progressLabel;
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    private final AtomicBoolean isReaderOpen = new AtomicBoolean(false);

    private final ScheduledExecutorService restoreDelayExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ReaderRestoreDelay");
        t.setDaemon(true);
        return t;
    });

    // ==================== ПУБЛІЧНІ МЕТОДИ ====================

    public boolean isReaderOpen() {
        return isReaderOpen.get();
    }

    public void openBook(BookDto book, WebEngine engine, ProgressBar bar, Label label) {
        if (isShuttingDown.get()) {
            log.warn("ReaderLifecycleManager завершується, пропускаємо відкриття книги");
            return;
        }

        // Закриваємо попередню книгу, якщо вона була
        closeBook();

        this.webEngine = engine;
        this.currentBook = book;
        this.progressBar = bar;
        this.progressLabel = label;

        progressManager.setCurrentBook(book);
        isReaderOpen.set(true);

        log.info("Відкриття книги: {}, прогрес з БД: {}%", book.getTitle(), book.getProgress());

        loadBookContent(book);
        restorePositionWhenReady(book.getId());
    }

    public void closeBook() {
        if (isShuttingDown.get()) {
            log.debug("ReaderLifecycleManager завершується, пропускаємо closeBook");
            return;
        }

        if (!isReaderOpen.get()) {
            log.debug("Reader вже закрито");
            return;
        }

        log.info("Закриття книги: {}", currentBook != null ? currentBook.getTitle() : "none");

        // ЗУПИНЯЄМО ТАЙМЕР ПЕРШИМ
        scheduler.stopSaving();

        // ДЕАКТИВУЄМО READER
        progressManager.deactivateReader();
        isReaderOpen.set(false);

        // ОЧИЩУЄМО WEB ENGINE
        if (webEngine != null) {
            Platform.runLater(() -> {
                webEngine.loadContent("");
                webEngine.getLoadWorker().cancel();
            });
        }

        currentBook = null;
        progressBar = null;
        progressLabel = null;

        log.info("Reader закрито, таймер зупинено");
    }

    // ==================== ПРИВАТНІ МЕТОДИ ====================

    private void loadBookContent(BookDto book) {
        try {
            String html = contentLoader.loadBookContent(book);
            String css = ReaderSettings.getInstance().toCss();
            html = injectStyles(html, css);
            webEngine.loadContent(html);
            log.info("Книгу завантажено");
        } catch (Exception e) {
            log.error("Помилка завантаження книги", e);
            webEngine.loadContent("<html><body><h1>Помилка завантаження</h1><pre>" + e.getMessage() + "</pre></body></html>");
        }
    }

    private String injectStyles(String html, String css) {
        return html.replace("</head>", "<style>" + css + "</style></head>");
    }

    private void restorePositionWhenReady(String bookId) {
        if (webEngine == null || isShuttingDown.get()) return;
        Worker.State state = webEngine.getLoadWorker().getState();
        if (state == Worker.State.SUCCEEDED) {
            doRestore(bookId);
        } else if (state == Worker.State.FAILED) {
            log.warn("Сторінка не завантажилась, відновлення позиції неможливе");
        } else {
            webEngine.getLoadWorker().stateProperty().addListener((obs, old, newState) -> {
                if (newState == Worker.State.SUCCEEDED && !isShuttingDown.get() && isReaderOpen.get()) {
                    doRestore(bookId);
                }
            });
        }
    }

    private void doRestore(String bookId) {
        if (isShuttingDown.get() || !isReaderOpen.get()) {
            log.debug("Reader неактивний або завершується, пропускаємо відновлення");
            return;
        }

        if (restoreDelayExecutor.isShutdown() || restoreDelayExecutor.isTerminated()) {
            log.warn("restoreDelayExecutor завершено, неможливо запланувати відновлення");
            return;
        }

        isRestoring = true;
        boolean success = progressManager.restorePosition(webEngine, bookId);
        if (success) {
            var progress = progressManager.loadProgress(bookId).orElse(null);
            if (progress != null && progressBar != null) {
                progressBar.setProgress(progress.getPercent() / 100.0);
                progressLabel.setText((int) progress.getPercent() + "%");
            }
        }

        // Запускаємо таймер збереження ТІЛЬКИ якщо Reader відкритий
        if (isReaderOpen.get()) {
            scheduler.startSaving(webEngine, progressManager, progressBar, progressLabel, null);
        }

        try {
            restoreDelayExecutor.schedule(() -> {
                if (!isShuttingDown.get() && isReaderOpen.get()) {
                    isRestoring = false;
                }
            }, 1000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Не вдалося запланувати скидання isRestoring: {}", e.getMessage());
            isRestoring = false;
        }
    }

    // ==================== ПУБЛІЧНІ СЕРВІСНІ МЕТОДИ ====================

    public void setupProgressListener(WebEngine engine) {
        if (isShuttingDown.get()) return;
        this.webEngine = engine;
        jsBridge.setupScrollListener(engine);
        log.info("Слухач прогресу налаштовано");
    }

    public void saveState() {
        if (isShuttingDown.get()) return;
        if (webEngine != null && progressManager.isReaderActive() && isReaderOpen.get()) {
            scheduler.forceSaveNow(webEngine, progressManager);
        }
    }

    public BookDto getCurrentBook() {
        return currentBook;
    }

    public String getTextAtPosition(double position) {
        if (isShuttingDown.get() || webEngine == null || !progressManager.isReaderActive() || !isReaderOpen.get()) {
            return "";
        }
        return jsBridge.getTextAtPosition(webEngine, position);
    }

    public String getCurrentChapterTitle() {
        if (isShuttingDown.get() || webEngine == null || !progressManager.isReaderActive() || !isReaderOpen.get()) {
            return "Без заголовка";
        }
        return jsBridge.getCurrentChapterTitle(webEngine);
    }

    public void updateProgress(BookId bookId, int progress) {
        // Для зовнішніх викликів
    }

    public void restorePosition(String bookId) {
        if (isShuttingDown.get() || webEngine == null || currentBook == null || !isReaderOpen.get()) {
            log.debug("Неможливо відновити позицію: Reader неактивний або завершується");
            return;
        }
        doRestore(bookId);
    }

    // ==================== ЗАВЕРШЕННЯ ====================

    @PreDestroy
    public void cleanup() {
        if (isShuttingDown.getAndSet(true)) {
            log.debug("ReaderLifecycleManager вже завершується, пропускаємо повторний виклик");
            return;
        }

        log.info("ReaderLifecycleManager.cleanup()");
        saveState();
        closeBook();
        scheduler.shutdown();

        if (restoreDelayExecutor != null && !restoreDelayExecutor.isShutdown()) {
            restoreDelayExecutor.shutdown();
            try {
                if (!restoreDelayExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    restoreDelayExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                restoreDelayExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        log.info("ReaderLifecycleManager очищено");
    }
}