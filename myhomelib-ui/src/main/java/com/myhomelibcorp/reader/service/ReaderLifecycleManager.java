package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.reader.core.ReaderSettings;
import com.myhomelibcorp.reader.session.ReaderSession;
import com.myhomelibcorp.reader.session.ReaderSessionManager;
import jakarta.annotation.PreDestroy;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.concurrent.Worker;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.web.WebEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReaderLifecycleManager {

    private static final int MAX_RETRIES = 3;
    private static final long RESTORE_DELAY_MS = 400;
    private static final long SAVE_INTERVAL_SECONDS = 3;
    private static final long DEBOUNCE_DELAY_MS = 500;

    private final ReaderContentLoader contentLoader;
    private final ReaderProgressManager progressManager;
    private final ReaderScheduler scheduler;
    private final ReaderJsBridge jsBridge;
    private final CollectionLifecyclePort collectionLifecyclePort;
    private final ReaderSessionManager sessionManager;
    private final ReaderFacade readerFacade;

    private final ScheduledExecutorService restoreExecutor = Executors.newSingleThreadScheduledExecutor(
            r -> {
                Thread t = new Thread(r, "ReaderRestore");
                t.setDaemon(true);
                return t;
            }
    );

    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    private ScheduledFuture<?> saveTask;
    private ScheduledFuture<?> debounceFuture;
    private volatile boolean isSavingActive = false;

    // ==================== ПУБЛІЧНІ МЕТОДИ ====================

    public void openBook(BookDto book, WebEngine engine, ProgressBar bar, Label label) {
        readerFacade.openBook(
                BookId.fromString(book.getId()),
                null, // WebView передається окремо
                engine,
                bar,
                label
        );
    }

    public void closeBook(ReaderSession session) {
        readerFacade.closeBook();
    }

    public void setupProgressListener(ReaderSession session) {
        if (session == null || session.getWebEngine() == null) {
            return;
        }

        String sessionId = session.getSessionId();
        if (!sessionManager.isCurrentSession(sessionId)) {
            log.debug("Сесія неактивна, пропускаємо налаштування listener-а");
            return;
        }

        if (session.getWebEngine().getLoadWorker().getState() == Worker.State.SUCCEEDED) {
            jsBridge.setupScrollListener(session.getWebEngine());
            session.setProgressListenerSetup(true);

            if (session.isContentLoaded() && session.isActive()) {
                restorePosition(session);
            }
        }
    }

    public void restorePosition(ReaderSession session) {
        if (session == null || session.getWebEngine() == null || !session.isActive()) {
            return;
        }

        String sessionId = session.getSessionId();
        if (!sessionManager.isCurrentSession(sessionId)) {
            log.debug("Сесія неактивна, пропускаємо відновлення");
            return;
        }

        if (!session.isContentLoaded()) {
            log.debug("Контент ще не завантажено, пропускаємо відновлення");
            return;
        }

        if (session.getWebEngine().getLoadWorker().getState() != Worker.State.SUCCEEDED) {
            log.debug("Сторінка не завантажена, пропускаємо відновлення");
            return;
        }

        final String finalSessionId = sessionId;
        final String bookId = session.getBookId();

        restoreExecutor.schedule(() -> {
            if (!sessionManager.isCurrentSession(finalSessionId)) {
                log.debug("Сесія {} вже неактивна, пропускаємо відновлення", finalSessionId);
                return;
            }

            ReaderSession currentSession = sessionManager.getCurrentSession();
            if (currentSession == null || !currentSession.isActive()) {
                return;
            }

            Platform.runLater(() -> {
                if (!sessionManager.isCurrentSession(finalSessionId)) {
                    return;
                }
                doRestore(currentSession, bookId);
            });
        }, RESTORE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    public void startSaving(ReaderSession session) {
        if (session == null || session.getWebEngine() == null || !session.isActive()) {
            return;
        }

        String sessionId = session.getSessionId();
        if (!sessionManager.isCurrentSession(sessionId)) {
            return;
        }

        if (session.getWebEngine().getLoadWorker().getState() != Worker.State.SUCCEEDED) {
            log.debug("Сторінка не завантажена, таймер не запускається");
            return;
        }

        stopSaving(sessionId);
        isSavingActive = true;

        final String finalSessionId = sessionId;

        saveTask = restoreExecutor.scheduleAtFixedRate(() -> {
            if (!isSavingActive || !sessionManager.isCurrentSession(finalSessionId) || isShuttingDown.get()) {
                return;
            }

            ReaderSession currentSession = sessionManager.getCurrentSession();
            if (currentSession == null || currentSession.getWebEngine() == null || !currentSession.isActive()) {
                return;
            }

            Platform.runLater(() -> {
                if (!sessionManager.isCurrentSession(finalSessionId)) {
                    return;
                }

                ReaderSession s = sessionManager.getCurrentSession();
                if (s == null || s.getWebEngine() == null) {
                    return;
                }

                if (s.getWebEngine().getLoadWorker().getState() != Worker.State.SUCCEEDED) {
                    return;
                }

                var progress = progressManager.getCurrentProgress(s);
                if (progress == null) {
                    return;
                }

                double percent = progress.getPercent() / 100.0;
                if (s.getProgressBar() != null) {
                    s.getProgressBar().setProgress(percent);
                }
                if (s.getProgressLabel() != null) {
                    s.getProgressLabel().setText((int) (percent * 100) + "%");
                }

                if (progressManager.shouldSave(s, progress)) {
                    scheduleDebouncedSave(s, progress);
                }
            });
        }, SAVE_INTERVAL_SECONDS, SAVE_INTERVAL_SECONDS, TimeUnit.SECONDS);

        log.info("Таймер збереження запущено для сесії: {}", sessionId);
    }

    public void saveState(ReaderSession session) {
        readerFacade.saveCurrentPosition();
    }

    public BookDto getCurrentBook() {
        ReaderSession session = sessionManager.getCurrentSession();
        return session != null ? session.getBook() : null;
    }

    public String getTextAtPosition(double position) {
        ReaderSession session = sessionManager.getCurrentSession();
        if (session == null || session.getWebEngine() == null || !session.isActive()) {
            return "";
        }
        return jsBridge.getTextAtPosition(session.getWebEngine(), position);
    }

    public String getCurrentChapterTitle() {
        ReaderSession session = sessionManager.getCurrentSession();
        if (session == null || session.getWebEngine() == null || !session.isActive()) {
            return "Без заголовка";
        }
        return jsBridge.getCurrentChapterTitle(session.getWebEngine());
    }

    public boolean isReaderOpen() {
        return readerFacade.isBookOpen();
    }

    // ==================== ПРИВАТНІ МЕТОДИ ====================

    private void loadBookContent(ReaderSession session) {
        if (session == null || !session.isActive()) {
            return;
        }

        final String sessionId = session.getSessionId();
        final BookDto book = session.getBook();
        final WebEngine targetEngine = session.getWebEngine();

        log.info("Початок завантаження контенту для книги: {} (сесія: {})", book.getTitle(), sessionId);

        restoreExecutor.execute(() -> {
            try {
                if (!sessionManager.isCurrentSession(sessionId)) {
                    log.debug("Сесія {} вже неактивна, скасовуємо завантаження", sessionId);
                    return;
                }

                String html = contentLoader.loadBookContent(book);
                String css = ReaderSettings.getInstance().toCss();
                String fullHtml = injectStyles(html, css);

                if (!sessionManager.isCurrentSession(sessionId)) {
                    log.debug("Сесія {} вже неактивна, скасовуємо завантаження HTML", sessionId);
                    return;
                }

                final String finalFullHtml = fullHtml;
                final String finalSessionId = sessionId;

                Platform.runLater(() -> {
                    ReaderSession currentSession = sessionManager.getCurrentSession();
                    if (currentSession == null || !currentSession.isActive()) {
                        log.debug("Немає активної сесії, скасовуємо завантаження HTML");
                        return;
                    }

                    if (!currentSession.getSessionId().equals(finalSessionId)) {
                        log.debug("Сесія змінилася (була: {}, поточна: {}), скасовуємо завантаження HTML",
                                finalSessionId, currentSession.getSessionId());
                        return;
                    }

                    if (currentSession.getWebEngine() != targetEngine) {
                        log.debug("WebEngine змінився, скасовуємо завантаження HTML");
                        return;
                    }

                    currentSession.setLastLoadedHtml(finalFullHtml);
                    loadHtmlToWebView(currentSession, finalFullHtml);
                });

            } catch (Exception e) {
                log.error("Помилка завантаження книги: {}", book.getTitle(), e);
                Platform.runLater(() -> {
                    ReaderSession currentSession = sessionManager.getCurrentSession();
                    if (currentSession != null && currentSession.getSessionId().equals(sessionId)) {
                        String errorHtml = createErrorHtml(book, e);
                        loadHtmlToWebView(currentSession, errorHtml);
                    }
                });
            }
        });
    }

    private void loadHtmlToWebView(ReaderSession session, String html) {
        if (session == null || session.getWebEngine() == null || !session.isActive()) {
            log.warn("loadHtmlToWebView: session null або неактивна");
            return;
        }

        final String sessionId = session.getSessionId();
        final WebEngine engine = session.getWebEngine();

        log.info("Завантаження HTML у WebView, сесія: {}, HTML розмір: {} chars", sessionId, html.length());

        // Логуємо перші 500 символів для перевірки
        String preview = html.length() > 500 ? html.substring(0, 500) : html;
        log.debug("Перші 500 символів HTML: {}", preview);

        // Створюємо listener
        ChangeListener<Worker.State> listener = (obs, oldState, newState) -> {
            log.info("loadListener: сесія={}, oldState={}, newState={}", sessionId, oldState, newState);

            if (!sessionManager.isCurrentSession(sessionId)) {
                log.debug("Сесія {} вже неактивна, пропускаємо", sessionId);
                return;
            }

            if (newState == Worker.State.SUCCEEDED) {
                log.info("✅ WebView успішно завантажив HTML, сесія: {}", sessionId);
                session.setContentLoaded(true);
                session.setRetryCount(0);

                // Перевіряємо вміст DOM
                try {
                    // Перевіряємо body
                    Object hasBody = engine.executeScript("document.body !== null");
                    log.info("📄 document.body exists: {}", hasBody);

                    if (Boolean.TRUE.equals(hasBody)) {
                        Object bodyContent = engine.executeScript("document.body.innerText.length");
                        log.info("📄 Довжина тексту в body: {} символів", bodyContent);

                        // Перевіряємо HTML вміст
                        Object htmlContent = engine.executeScript("document.documentElement.outerHTML.length");
                        log.info("📄 Довжина HTML: {} символів", htmlContent);
                    } else {
                        log.warn("⚠️ document.body is null! WebView не відображає контент.");
                        // Спроба примусово встановити контент
                        engine.loadContent("<html><body><h1>Тестовий контент</h1><p>Якщо ви бачите цей текст, WebView працює.</p></body></html>");
                    }
                } catch (Exception e) {
                    log.warn("Не вдалося отримати інформацію з DOM", e);
                }

                restorePosition(sessionManager.getCurrentSession());
                startSaving(session);

            } else if (newState == Worker.State.FAILED) {
                log.error("❌ WebView не зміг завантажити HTML, сесія: {}", sessionId);
                session.setContentLoaded(false);

                Throwable exception = engine.getLoadWorker().getException();
                if (exception != null) {
                    log.error("Причина помилки: ", exception);
                }

                if (session.getLastLoadedHtml() != null && session.getRetryCount() < MAX_RETRIES) {
                    session.setRetryCount(session.getRetryCount() + 1);
                    log.info("Повторна спроба завантаження HTML ({} з {})", session.getRetryCount(), MAX_RETRIES);
                    Platform.runLater(() -> {
                        if (sessionManager.isCurrentSession(sessionId)) {
                            // Спрощений HTML для тесту
                            String testHtml = "<html><head><meta charset='UTF-8'/></head><body><h1>Тест</h1><p>Спроба " + session.getRetryCount() + "</p></body></html>";
                            engine.loadContent(testHtml);
                        }
                    });
                }
            }
        };

        // Видаляємо старий listener
        try {
            engine.getLoadWorker().stateProperty().removeListener(listener);
        } catch (Exception e) {
            log.debug("Не вдалося видалити старий listener: {}", e.getMessage());
        }

        engine.getLoadWorker().stateProperty().addListener(listener);

        // ОЧИЩУЄМО ТА ЗАВАНТАЖУЄМО
        try {
            // Спершу завантажуємо простий HTML для перевірки
            String testHtml = "<html><head><meta charset='UTF-8'/></head><body><h1>Завантаження...</h1><p>Книга завантажується...</p></body></html>";
            engine.loadContent(testHtml);

            // Невелика затримка
            Thread.sleep(100);

            // Потім завантажуємо основний HTML
            session.setCurrentHtml(html);
            session.setContentLoaded(false);
            engine.loadContent(html);
            log.info("✅ HTML передано у WebView для завантаження, сесія: {}", sessionId);
        } catch (Exception e) {
            log.error("Помилка завантаження HTML у WebView", e);
            String fallbackHtml = "<html><head><meta charset='UTF-8'/></head><body><h1>Помилка</h1><p>" + e.getMessage() + "</p></body></html>";
            engine.loadContent(fallbackHtml);
        }
    }

    private void scheduleDebouncedSave(ReaderSession session, com.myhomelibcorp.application.dto.ReadingProgressDto progress) {
        if (session == null || !session.isActive()) {
            return;
        }

        final String sessionId = session.getSessionId();

        if (debounceFuture != null && !debounceFuture.isDone()) {
            debounceFuture.cancel(false);
        }

        debounceFuture = restoreExecutor.schedule(() -> {
            if (!sessionManager.isCurrentSession(sessionId) || isShuttingDown.get()) {
                return;
            }

            Platform.runLater(() -> {
                ReaderSession currentSession = sessionManager.getCurrentSession();
                if (currentSession == null || !currentSession.isActive()) {
                    return;
                }
                if (!currentSession.getSessionId().equals(sessionId)) {
                    return;
                }
                var finalProgress = progressManager.getCurrentProgress(currentSession);
                if (finalProgress != null) {
                    progressManager.saveProgress(currentSession, finalProgress);
                }
            });
        }, DEBOUNCE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void forceSaveNow(ReaderSession session) {
        if (session == null || session.getWebEngine() == null || !session.isActive()) {
            return;
        }

        var progress = progressManager.getCurrentProgress(session);
        if (progress != null) {
            progressManager.saveProgress(session, progress);
        }
    }

    private void stopSaving(String sessionId) {
        isSavingActive = false;
        if (saveTask != null && !saveTask.isDone()) {
            saveTask.cancel(false);
            saveTask = null;
        }
        if (debounceFuture != null && !debounceFuture.isDone()) {
            debounceFuture.cancel(false);
            debounceFuture = null;
        }
        log.debug("Таймер збереження зупинено для сесії: {}", sessionId);
    }

    private void cleanupWebView(ReaderSession session) {
        if (session == null || session.getWebEngine() == null) {
            return;
        }

        Platform.runLater(() -> {
            try {
                jsBridge.cleanup(session.getWebEngine());
                session.getWebEngine().loadContent("");
                session.getWebEngine().getLoadWorker().cancel();
            } catch (Exception e) {
                log.warn("Помилка очищення WebView: {}", e.getMessage());
            }
        });

        session.setWebEngine(null);
        session.setWebView(null);
        session.setCurrentHtml(null);
        session.setLastLoadedHtml(null);
        session.setContentLoaded(false);
        session.setProgressListenerSetup(false);
    }

    private void doRestore(ReaderSession session, String bookId) {
        if (session == null || session.getWebEngine() == null || !session.isActive()) {
            return;
        }
        progressManager.restorePosition(session, bookId);
    }

    private String injectStyles(String html, String css) {
        if (html.contains("<head>")) {
            return html.replace("</head>", "<style>" + css + "</style></head>");
        } else if (html.contains("<body>")) {
            return html.replace("<body>", "<body><style>" + css + "</style>");
        }
        return "<!DOCTYPE html><html><head><style>" + css + "</style></head><body>" + html + "</body></html>";
    }

    private String createErrorHtml(BookDto book, Exception e) {
        return """
                <html>
                <head><meta charset="UTF-8"/></head>
                <body>
                    <h1>Помилка завантаження книги</h1>
                    <p><b>Назва:</b> %s</p>
                    <p><b>Автор:</b> %s</p>
                    <p><b>Помилка:</b> %s</p>
                </body>
                </html>
                """.formatted(
                book.getTitle() != null ? book.getTitle() : "Без назви",
                book.getAuthorsText() != null ? book.getAuthorsText() : "Невідомий автор",
                e.getMessage()
        );
    }

    @PreDestroy
    public void cleanup() {
        if (isShuttingDown.getAndSet(true)) {
            return;
        }

        log.info("ReaderLifecycleManager.cleanup()");
        sessionManager.closeCurrentSession();

        if (restoreExecutor != null && !restoreExecutor.isShutdown()) {
            restoreExecutor.shutdownNow();
            try {
                if (!restoreExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("restoreExecutor не завершив роботу примусово");
                }
            } catch (InterruptedException e) {
                restoreExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        log.info("ReaderLifecycleManager очищено");
    }
}