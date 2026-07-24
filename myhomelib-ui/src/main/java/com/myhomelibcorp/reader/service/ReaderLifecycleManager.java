package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.dto.ReadingProgressDto;
import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import com.myhomelibcorp.application.usecase.book.UpdateBookUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.reader.core.ReaderSettings;
import jakarta.annotation.PreDestroy;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.concurrent.Worker;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.web.WebEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReaderLifecycleManager {

    private final UpdateBookUseCase updateBookUseCase;
    private final ReadingProgressRepository readingProgressRepository;
    private final ReaderContentLoader contentLoader;

    private WebEngine webEngine;
    private BookDto currentBook;
    private ReadingProgressDto lastSavedProgress = null;
    private double lastSavedPercent = -1;
    private boolean progressListenerSetup = false;
    private ProgressBar progressBar;
    private Label progressLabel;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> saveTask;
    private boolean isRestoring = false;
    private final ScheduledExecutorService debounceScheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> debounceFuture;

    // ==================== Відкриття книги ====================

    public void openBook(BookDto book, WebEngine webEngine, ProgressBar progressBar, Label progressLabel) {
        this.webEngine = webEngine;
        this.currentBook = book;
        this.progressBar = progressBar;
        this.progressLabel = progressLabel;

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
            restorePosition(bookId);
        } else if (state == Worker.State.FAILED) {
            log.warn("Сторінка не завантажилась, відновлення позиції неможливе");
        } else {
            ChangeListener<Worker.State> listener = new ChangeListener<>() {
                @Override
                public void changed(ObservableValue<? extends Worker.State> obs, Worker.State old, Worker.State newState) {
                    if (newState == Worker.State.SUCCEEDED) {
                        restorePosition(bookId);
                        webEngine.getLoadWorker().stateProperty().removeListener(this);
                    }
                }
            };
            webEngine.getLoadWorker().stateProperty().addListener(listener);
        }
    }

    public void restorePosition(String bookId) {
        if (webEngine == null || bookId == null) {
            log.warn("Неможливо відновити позицію: webEngine або bookId == null");
            return;
        }

        Optional<ReadingProgressDto> progressOpt = readingProgressRepository.findByBookId(bookId);
        if (progressOpt.isEmpty()) {
            log.info("Немає збереженої позиції для книги {}", bookId);
            return;
        }

        ReadingProgressDto progress = progressOpt.get();
        lastSavedProgress = progress;
        lastSavedPercent = progress.getPercent();

        if (progressBar != null) {
            progressBar.setProgress(progress.getPercent() / 100.0);
        }
        if (progressLabel != null) {
            progressLabel.setText((int) progress.getPercent() + "%");
        }

        isRestoring = true;

        // Отримуємо індекс із збереженого ID (якщо починається з "p", відкидаємо префікс)
        String paragraphId = progress.getParagraphId();
        int targetIndex = extractIndex(paragraphId);
        int charOffset = Math.max(0, progress.getCharOffset()); // від'ємні зсуви виправляємо

        // Отримуємо актуальну кількість абзаців
        int totalParagraphs = getParagraphCount();
        log.info("Кількість абзаців у документі: {}, збережений індекс: {}", totalParagraphs, targetIndex);

        // Коригуємо індекс, якщо він виходить за межі
        if (targetIndex >= totalParagraphs) {
            targetIndex = totalParagraphs - 1;
            log.warn("Індекс скориговано до {}", targetIndex);
        }
        if (targetIndex < 0) {
            targetIndex = 0;
        }

        // Якщо зсув виходить за межі довжини тексту, обрізаємо його
        // Але точну довжину ми дізнаємося лише в JavaScript, тому передаємо як є,
        // а в JavaScript обріжемо до довжини тексту.

        String js = String.format(
                "(function() {" +
                        "    var paragraphs = document.querySelectorAll('p[data-paragraph-id]');" +
                        "    if (paragraphs.length <= %d) return false;" +
                        "    var el = paragraphs[%d];" +
                        "    if (!el) return false;" +
                        "    var text = el.innerText;" +
                        "    if (text.length === 0) {" +
                        "        el.scrollIntoView({ block: 'center' });" +
                        "        return true;" +
                        "    }" +
                        "    var offset = %d;" +
                        "    if (offset < 0) offset = 0;" +
                        "    if (offset > text.length) offset = text.length;" +
                        "    var textNode = el.firstChild;" +
                        "    while (textNode && textNode.nodeType !== Node.TEXT_NODE) {" +
                        "        textNode = textNode.nextSibling;" +
                        "    }" +
                        "    if (textNode) {" +
                        "        var range = document.createRange();" +
                        "        range.setStart(textNode, offset);" +
                        "        range.setEnd(textNode, offset);" +
                        "        var rect = range.getClientRects()[0];" +
                        "        if (rect) {" +
                        "            var targetY = rect.top + window.scrollY - window.innerHeight * 0.3;" +
                        "            window.scrollTo({ top: targetY, behavior: 'auto' });" +
                        "        } else {" +
                        "            el.scrollIntoView({ block: 'center' });" +
                        "        }" +
                        "    } else {" +
                        "        el.scrollIntoView({ block: 'center' });" +
                        "    }" +
                        "    return true;" +
                        "})();",
                targetIndex, targetIndex, charOffset
        );

        try {
            Boolean result = (Boolean) webEngine.executeScript(js);
            if (Boolean.TRUE.equals(result)) {
                log.info("Відновлено позицію: індекс={}, зсув={}, %={}",
                        targetIndex, charOffset, progress.getPercent());
            } else {
                log.warn("Не вдалося відновити позицію для індексу {}", targetIndex);
            }
        } catch (Exception e) {
            log.error("Помилка відновлення позиції", e);
        }

        scheduler.schedule(() -> isRestoring = false, 1000, TimeUnit.MILLISECONDS);
    }

    private int extractIndex(String paragraphId) {
        if (paragraphId == null) return 0;
        // Якщо ID починається з "p", відкидаємо префікс
        if (paragraphId.startsWith("p")) {
            try {
                return Integer.parseInt(paragraphId.substring(1));
            } catch (NumberFormatException e) {
                log.warn("Неможливо розпізнати індекс з ID: {}", paragraphId);
                return 0;
            }
        }
        try {
            return Integer.parseInt(paragraphId);
        } catch (NumberFormatException e) {
            log.warn("Неможливо розпізнати індекс з ID: {}", paragraphId);
            return 0;
        }
    }

    private int getParagraphCount() {
        try {
            Object result = webEngine.executeScript("document.querySelectorAll('p[data-paragraph-id]').length");
            return result instanceof Number ? ((Number) result).intValue() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // ==================== Налаштування слухача прогресу ====================

    public void setupProgressListener(WebEngine webEngine) {
        this.webEngine = webEngine;
        try {
            webEngine.executeScript(
                    "if (typeof window.progress === 'undefined') { window.progress = 0; }" +
                            "window.addEventListener('scroll', function() {" +
                            "  var scrollTop = document.documentElement.scrollTop || document.body.scrollTop;" +
                            "  var scrollHeight = document.documentElement.scrollHeight - document.documentElement.clientHeight;" +
                            "  var progress = scrollHeight > 0 ? scrollTop / scrollHeight : 0;" +
                            "  window.progress = progress;" +
                            "});"
            );
            progressListenerSetup = true;
            log.info("Слухач прогресу налаштовано");
        } catch (Exception e) {
            log.error("Не вдалося налаштувати слухач прогресу", e);
            progressListenerSetup = false;
        }
    }

    // ==================== Визначення видимого абзацу (з індексом) ====================

    public ReadingProgressDto getVisibleParagraph() {
        if (webEngine == null || currentBook == null) {
            log.debug("Неможливо визначити видимий абзац: webEngine або currentBook == null");
            return null;
        }

        try {
            // Отримуємо індекс абзацу з найбільшою видимою площею
            Integer index = (Integer) webEngine.executeScript(
                    "(function() {" +
                            "    var paragraphs = document.querySelectorAll('p[data-paragraph-id]');" +
                            "    if (paragraphs.length === 0) return -1;" +
                            "    var viewportTop = window.scrollY;" +
                            "    var viewportBottom = viewportTop + window.innerHeight;" +
                            "    var bestIdx = 0;" +
                            "    var bestArea = 0;" +
                            "    for (var i = 0; i < paragraphs.length; i++) {" +
                            "        var rect = paragraphs[i].getBoundingClientRect();" +
                            "        var top = Math.max(rect.top + viewportTop, viewportTop);" +
                            "        var bottom = Math.min(rect.bottom + viewportTop, viewportBottom);" +
                            "        if (bottom > top) {" +
                            "            var area = bottom - top;" +
                            "            if (area > bestArea) {" +
                            "                bestArea = area;" +
                            "                bestIdx = i;" +
                            "            }" +
                            "        }" +
                            "    }" +
                            "    return bestIdx;" +
                            "})();"
            );

            if (index == null || index < 0) {
                log.warn("Не вдалося отримати індекс видимого абзацу");
                return null;
            }

            // Отримуємо зсув для цього абзацу
            Integer charOffset = (Integer) webEngine.executeScript(
                    "(function() {" +
                            "    var paragraphs = document.querySelectorAll('p[data-paragraph-id]');" +
                            "    if (paragraphs.length <= " + index + ") return 0;" +
                            "    var el = paragraphs[" + index + "];" +
                            "    var text = el.innerText;" +
                            "    if (text.length === 0) return 0;" +
                            "    var rect = el.getBoundingClientRect();" +
                            "    var viewportTop = window.scrollY;" +
                            "    var viewportBottom = viewportTop + window.innerHeight;" +
                            "    var visibleHeight = Math.min(rect.bottom + window.scrollY, viewportBottom) - " +
                            "                        Math.max(rect.top + window.scrollY, viewportTop);" +
                            "    var totalHeight = rect.bottom - rect.top;" +
                            "    if (totalHeight <= 0) return 0;" +
                            "    var ratio = visibleHeight / totalHeight;" +
                            "    if (ratio < 0) return 0;" +
                            "    if (ratio > 1) return text.length;" +
                            "    return Math.floor(ratio * text.length);" +
                            "})();"
            );

            if (charOffset == null) charOffset = 0;
            // Запобігаємо від'ємному зсуву
            if (charOffset < 0) charOffset = 0;

            double percent = getScrollPercent();
            // Якщо відсоток 0, але індекс не 0, обчислюємо через позицію абзацу
            if (percent == 0 && index > 0) {
                Double paragraphPercent = (Double) webEngine.executeScript(
                        "(function() {" +
                                "    var paragraphs = document.querySelectorAll('p[data-paragraph-id]');" +
                                "    if (paragraphs.length <= " + index + ") return 0;" +
                                "    var el = paragraphs[" + index + "];" +
                                "    var docHeight = document.documentElement.scrollHeight - document.documentElement.clientHeight;" +
                                "    if (docHeight <= 0) return 0;" +
                                "    var elTop = el.getBoundingClientRect().top + window.scrollY;" +
                                "    return elTop / docHeight;" +
                                "})();"
                );
                if (paragraphPercent != null && paragraphPercent > 0) {
                    percent = Math.min(1.0, paragraphPercent);
                }
            }

            // Зберігаємо індекс як рядок (без префікса p)
            String paragraphId = String.valueOf(index);

            log.debug("Видимий абзац: індекс={}, зсув={}, відсоток={}", index, charOffset, percent);

            return ReadingProgressDto.builder()
                    .bookId(currentBook.getId())
                    .paragraphId(paragraphId)
                    .charOffset(charOffset)
                    .percent(percent * 100)
                    .updatedAt(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            log.error("Помилка визначення видимого абзацу", e);
            return null;
        }
    }

    private double getScrollPercent() {
        try {
            Object result = webEngine.executeScript(
                    "(function() {" +
                            "    var scrollTop = document.documentElement.scrollTop || document.body.scrollTop;" +
                            "    var scrollHeight = document.documentElement.scrollHeight - document.documentElement.clientHeight;" +
                            "    if (scrollHeight <= 0) return 0;" +
                            "    return scrollTop / scrollHeight;" +
                            "})();"
            );
            if (result instanceof Number) {
                return ((Number) result).doubleValue();
            }
        } catch (Exception e) {
            log.debug("Не вдалося отримати відсоток прокрутки", e);
        }
        return 0.0;
    }

    // ==================== Збереження прогресу ====================

    public void startProgressSaving(WebEngine webEngine, ProgressBar progressBar, Label progressLabel) {
        this.webEngine = webEngine;
        this.progressBar = progressBar;
        this.progressLabel = progressLabel;

        if (!progressListenerSetup) {
            log.warn("Слухач прогресу не налаштовано, пропускаємо запуск таймера");
            return;
        }
        if (saveTask != null && !saveTask.isDone()) {
            saveTask.cancel(false);
        }

        saveTask = scheduler.scheduleAtFixedRate(() -> {
            if (currentBook == null || this.webEngine == null) return;
            if (isRestoring) {
                log.trace("Триває відновлення позиції, збереження відкладено");
                return;
            }
            Platform.runLater(() -> {
                try {
                    ReadingProgressDto currentProgress = getVisibleParagraph();
                    if (currentProgress == null) {
                        log.trace("Поточний прогрес не визначено, пропускаємо збереження");
                        return;
                    }

                    double percent = currentProgress.getPercent() / 100.0;
                    if (progressBar != null) {
                        progressBar.setProgress(percent);
                    }
                    if (progressLabel != null) {
                        progressLabel.setText((int) (percent * 100) + "%");
                    }

                    boolean shouldSave = false;
                    if (lastSavedProgress == null) {
                        shouldSave = true;
                    } else {
                        if (!lastSavedProgress.getParagraphId().equals(currentProgress.getParagraphId())) {
                            shouldSave = true;
                        } else if (Math.abs(lastSavedProgress.getCharOffset() - currentProgress.getCharOffset()) > 10) {
                            shouldSave = true;
                        } else if (Math.abs(lastSavedPercent - currentProgress.getPercent()) > 1.0) {
                            shouldSave = true;
                        }
                    }

                    if (shouldSave) {
                        if (debounceFuture != null && !debounceFuture.isDone()) {
                            debounceFuture.cancel(false);
                        }
                        debounceFuture = debounceScheduler.schedule(() -> {
                            Platform.runLater(() -> {
                                ReadingProgressDto finalProgress = getVisibleParagraph();
                                if (finalProgress != null) {
                                    // Переконуємося, що зсув не від'ємний
                                    int fixedOffset = Math.max(0, finalProgress.getCharOffset());
                                    ReadingProgressDto toSave = ReadingProgressDto.builder()
                                            .bookId(finalProgress.getBookId())
                                            .paragraphId(finalProgress.getParagraphId())
                                            .charOffset(fixedOffset)
                                            .percent(finalProgress.getPercent())
                                            .updatedAt(finalProgress.getUpdatedAt())
                                            .build();
                                    readingProgressRepository.save(toSave);
                                    lastSavedProgress = toSave;
                                    lastSavedPercent = toSave.getPercent();
                                    log.info("Збережено прогрес: абзац={}, зсув={}, %={}",
                                            toSave.getParagraphId(),
                                            toSave.getCharOffset(),
                                            (int) toSave.getPercent());
                                }
                            });
                        }, 500, TimeUnit.MILLISECONDS);
                    } else {
                        log.trace("Прогрес не змінився суттєво");
                    }
                } catch (Exception e) {
                    log.error("Помилка обробки прогресу", e);
                }
            });
        }, 3000, 1000, TimeUnit.MILLISECONDS);

        log.info("Таймер збереження прогресу запущено (з debounce)");
    }

    public void stopProgressSaving() {
        if (saveTask != null && !saveTask.isDone()) {
            saveTask.cancel(false);
            saveTask = null;
        }
        if (debounceFuture != null && !debounceFuture.isDone()) {
            debounceFuture.cancel(false);
        }
        log.info("Таймер збереження прогресу зупинено");
    }

    public void forceSaveProgressSync() {
        if (currentBook == null || webEngine == null) {
            log.warn("Неможливо синхронно зберегти прогрес: currentBook або webEngine == null");
            return;
        }
        if (!progressListenerSetup) {
            log.warn("Слухач прогресу не налаштовано, пропускаємо синхронне збереження");
            return;
        }
        try {
            ReadingProgressDto currentProgress = getVisibleParagraph();
            if (currentProgress == null) return;

            // Фіксуємо зсув
            int fixedOffset = Math.max(0, currentProgress.getCharOffset());
            ReadingProgressDto toSave = ReadingProgressDto.builder()
                    .bookId(currentProgress.getBookId())
                    .paragraphId(currentProgress.getParagraphId())
                    .charOffset(fixedOffset)
                    .percent(currentProgress.getPercent())
                    .updatedAt(currentProgress.getUpdatedAt())
                    .build();

            if (lastSavedProgress == null ||
                    !lastSavedProgress.getParagraphId().equals(toSave.getParagraphId()) ||
                    Math.abs(lastSavedProgress.getCharOffset() - toSave.getCharOffset()) > 10 ||
                    Math.abs(lastSavedPercent - toSave.getPercent()) > 1.0) {

                readingProgressRepository.save(toSave);
                lastSavedProgress = toSave;
                lastSavedPercent = toSave.getPercent();
                log.info("Синхронне збереження прогресу: абзац={}, зсув={}, %={}",
                        toSave.getParagraphId(),
                        toSave.getCharOffset(),
                        (int) toSave.getPercent());
            } else {
                log.debug("Синхронне збереження пропущено (змін немає)");
            }
        } catch (Exception e) {
            log.error("Не вдалося синхронно зберегти прогрес", e);
        }
    }

    // ==================== Додаткові методи ====================

    public BookDto getCurrentBook() {
        return currentBook;
    }

    public void setCurrentBook(BookDto book) {
        this.currentBook = book;
    }

    public void updateProgress(BookId bookId, int progress) {
        if (bookId == null || progress < 0 || progress > 100) return;
        updateBookUseCase.updateProgress(bookId, progress);
        log.debug("Оновлено прогрес через UseCase: {}% для {}", progress, bookId);
    }

    public String getTextAtPosition(WebEngine webEngine, double position) {
        try {
            String js = "(function(pos) { var body = document.body.innerText; var len = body.length; var p = Math.floor(pos * len); var start = Math.max(0, p - 100); var end = Math.min(len, p + 100); return body.substring(start, end); })(" + position + ");";
            Object result = webEngine.executeScript(js);
            return result != null ? result.toString().trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    public String getCurrentChapterTitle(WebEngine webEngine) {
        try {
            Object title = webEngine.executeScript("document.querySelector('.chapter-title')?.innerText || ''");
            return title != null ? title.toString() : "Без заголовка";
        } catch (Exception e) {
            return "Без заголовка";
        }
    }

    // ==================== Очищення ресурсів ====================

    @PreDestroy
    public void cleanup() {
        if (webEngine != null) {
            stopProgressSaving();
            forceSaveProgressSync();
        }
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        if (debounceScheduler != null && !debounceScheduler.isShutdown()) {
            debounceScheduler.shutdownNow();
        }
        log.info("ReaderLifecycleManager очищено");
    }

    public void saveState() {
        if (webEngine != null) {
            stopProgressSaving();
            forceSaveProgressSync();
        }
    }
}