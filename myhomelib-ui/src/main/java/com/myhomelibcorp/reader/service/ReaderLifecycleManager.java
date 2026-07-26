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

    // ==================== Відкриття книги ====================

    public void openBook(BookDto book, WebEngine engine, ProgressBar bar, Label label) {
        this.webEngine = engine;
        this.currentBook = book;
        this.progressBar = bar;
        this.progressLabel = label;

        progressManager.setCurrentBook(book);

        log.info("Відкриття книги: {}, прогрес з БД: {}%", book.getTitle(), book.getProgress());

        loadBookContent(book);
        restorePositionWhenReady(book.getId());
    }

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

    // ==================== Відновлення позиції ====================

    private void restorePositionWhenReady(String bookId) {
        if (webEngine == null) return;
        Worker.State state = webEngine.getLoadWorker().getState();
        if (state == Worker.State.SUCCEEDED) {
            doRestore(bookId);
        } else if (state == Worker.State.FAILED) {
            log.warn("Сторінка не завантажилась, відновлення позиції неможливе");
        } else {
            webEngine.getLoadWorker().stateProperty().addListener((obs, old, newState) -> {
                if (newState == Worker.State.SUCCEEDED) {
                    doRestore(bookId);
                }
            });
        }
    }

    private void doRestore(String bookId) {
        isRestoring = true;
        boolean success = progressManager.restorePosition(webEngine, bookId);
        if (success) {
            var progress = progressManager.loadProgress(bookId).orElse(null);
            if (progress != null && progressBar != null) {
                progressBar.setProgress(progress.getPercent() / 100.0);
                progressLabel.setText((int) progress.getPercent() + "%");
            }
        }
        // Даємо час на прокрутку
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
                .schedule(() -> isRestoring = false, 1000, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Публічний метод для відновлення позиції ззовні (наприклад, після зміни теми)
     */
    public void restorePosition(String bookId) {
        if (webEngine != null) {
            doRestore(bookId);
        }
    }

    // ==================== Налаштування ====================

    public void setupProgressListener(WebEngine engine) {
        this.webEngine = engine;
        jsBridge.setupScrollListener(engine);
        scheduler.startSaving(engine, progressManager, progressBar, progressLabel, null);
        log.info("Слухач прогресу налаштовано");
    }

    public void saveState() {
        if (webEngine != null && progressManager.getCurrentBook() != null) {
            scheduler.forceSaveNow(webEngine, progressManager);
        }
    }

    // ==================== Додаткові методи ====================

    public BookDto getCurrentBook() {
        return progressManager.getCurrentBook();
    }

    public void updateProgress(BookId bookId, int progress) {
        // Для зовнішніх викликів (наприклад, закладки)
    }

    /**
     * Публічний метод для отримання тексту навколо позиції
     */
    public String getTextAtPosition(double position) {
        if (webEngine == null) return "";
        return jsBridge.getTextAtPosition(webEngine, position);
    }

    /**
     * Публічний метод для отримання назви поточного розділу
     */
    public String getCurrentChapterTitle() {
        if (webEngine == null) return "Без заголовка";
        return jsBridge.getCurrentChapterTitle(webEngine);
    }

    // ==================== Очищення ====================

    @PreDestroy
    public void cleanup() {
        saveState();
        scheduler.shutdown();
        if (webEngine != null) {
            Platform.runLater(() -> {
                webEngine.loadContent("");
                webEngine.getLoadWorker().cancel();
            });
        }
        log.info("ReaderLifecycleManager очищено");
    }
}