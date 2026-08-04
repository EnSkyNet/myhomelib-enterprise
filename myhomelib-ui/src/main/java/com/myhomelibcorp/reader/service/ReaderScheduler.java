package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.application.dto.BookDto;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.web.WebEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
public class ReaderScheduler {

    private static final int SAVE_INTERVAL_SECONDS = 3;
    private static final int DEBOUNCE_DELAY_MS = 500;

    private final ScheduledExecutorService scheduler;
    private final ScheduledExecutorService debounceScheduler;
    private ScheduledFuture<?> saveTask;
    private ScheduledFuture<?> debounceFuture;
    private final AtomicBoolean isActive = new AtomicBoolean(false);
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    private String currentBookId;

    public ReaderScheduler() {
        this.scheduler = Executors.newScheduledThreadPool(
                1,
                r -> {
                    Thread t = new Thread(r, "reader-save");
                    t.setDaemon(true);
                    return t;
                }
        );
        this.debounceScheduler = Executors.newScheduledThreadPool(
                1,
                r -> {
                    Thread t = new Thread(r, "reader-debounce");
                    t.setDaemon(true);
                    return t;
                }
        );
        log.info("ReaderScheduler ініціалізовано");
    }

    public void startSaving(WebEngine engine,
                            ReaderProgressManager progressManager,
                            ProgressBar progressBar,
                            Label progressLabel,
                            Runnable onSave) {
        // Перевіряємо, чи не завершується
        if (isShuttingDown.get()) {
            log.debug("ReaderScheduler завершується, таймер не запускається");
            return;
        }

        // Зупиняємо попередній таймер
        stopSaving();

        // Перевіряємо, чи активний Reader
        if (engine == null || progressManager == null || !progressManager.isReaderActive()) {
            log.debug("Reader неактивний, таймер збереження не запускається");
            return;
        }

        // Перевіряємо, чи завантажена сторінка
        if (engine.getLoadWorker().getState() != Worker.State.SUCCEEDED) {
            log.debug("Сторінка не завантажена, таймер збереження не запускається");
            return;
        }

        // ЗБЕРІГАЄМО ID ПОТОЧНОЇ КНИГИ
        BookDto currentBook = progressManager.getCurrentBook();
        if (currentBook == null) {
            log.debug("Немає поточної книги, таймер не запускається");
            return;
        }
        this.currentBookId = currentBook.getId();
        log.info("Запуск таймера для книги: {}", currentBook.getTitle());

        isActive.set(true);

        try {
            saveTask = scheduler.scheduleAtFixedRate(() -> {
                // ПЕРЕВІРКА: чи активний Reader і чи та ж книга
                if (!isActive.get() || !progressManager.isReaderActive() || isShuttingDown.get()) {
                    log.trace("Reader деактивовано або завершується, пропускаємо збереження");
                    return;
                }
                if (engine == null || progressManager.getCurrentBook() == null) return;

                // ПЕРЕВІРКА: чи не змінилася книга
                BookDto current = progressManager.getCurrentBook();
                if (current == null || !current.getId().equals(currentBookId)) {
                    log.info("Книга змінилася (була: {}, стала: {}), зупиняємо таймер",
                            currentBookId, current != null ? current.getId() : "null");
                    stopSaving();
                    return;
                }

                Platform.runLater(() -> {
                    try {
                        if (isShuttingDown.get()) return;

                        // Перевіряємо, чи завантажена сторінка
                        if (engine.getLoadWorker().getState() != Worker.State.SUCCEEDED) {
                            log.trace("Сторінка не завантажена, пропускаємо");
                            return;
                        }

                        var currentProgress = progressManager.getCurrentProgress(engine);
                        if (currentProgress == null) return;

                        double percent = currentProgress.getPercent() / 100.0;
                        if (progressBar != null) {
                            progressBar.setProgress(percent);
                        }
                        if (progressLabel != null) {
                            progressLabel.setText((int) (percent * 100) + "%");
                        }

                        if (progressManager.shouldSave(currentProgress)) {
                            if (debounceFuture != null && !debounceFuture.isDone()) {
                                debounceFuture.cancel(false);
                            }
                            debounceFuture = debounceScheduler.schedule(() -> {
                                Platform.runLater(() -> {
                                    if (isActive.get() && progressManager.isReaderActive() && !isShuttingDown.get()) {
                                        // ПЕРЕВІРКА ПЕРЕД ЗБЕРЕЖЕННЯМ
                                        BookDto book = progressManager.getCurrentBook();
                                        if (book != null && book.getId().equals(currentBookId)) {
                                            var finalProgress = progressManager.getCurrentProgress(engine);
                                            if (finalProgress != null) {
                                                progressManager.saveProgress(finalProgress);
                                                if (onSave != null) onSave.run();
                                            }
                                        }
                                    }
                                });
                            }, DEBOUNCE_DELAY_MS, TimeUnit.MILLISECONDS);
                        }
                    } catch (Exception e) {
                        log.error("Помилка обробки прогресу", e);
                    }
                });
            }, SAVE_INTERVAL_SECONDS, SAVE_INTERVAL_SECONDS, TimeUnit.SECONDS);

            log.info("Таймер збереження прогресу запущено для книги: {}", currentBookId);
        } catch (RejectedExecutionException e) {
            log.warn("Не вдалося запустити таймер: пул завершено", e);
            isActive.set(false);
        }
    }

    public void stopSaving() {
        isActive.set(false);
        if (saveTask != null && !saveTask.isDone()) {
            saveTask.cancel(false);
            saveTask = null;
        }
        if (debounceFuture != null && !debounceFuture.isDone()) {
            debounceFuture.cancel(false);
            debounceFuture = null;
        }
        if (currentBookId != null) {
            log.info("Таймер збереження прогресу зупинено для книги: {}", currentBookId);
            currentBookId = null;
        } else {
            log.info("Таймер збереження прогресу зупинено");
        }
    }

    public void forceSaveNow(WebEngine engine, ReaderProgressManager progressManager) {
        if (!isActive.get() || !progressManager.isReaderActive() || isShuttingDown.get()) {
            log.trace("Reader неактивний, пропускаємо примусове збереження");
            return;
        }

        // ПЕРЕВІРКА: чи та ж книга
        BookDto current = progressManager.getCurrentBook();
        if (current == null || !current.getId().equals(currentBookId)) {
            log.trace("Книга змінилася, пропускаємо примусове збереження");
            return;
        }

        var progress = progressManager.getCurrentProgress(engine);
        if (progress != null) {
            progressManager.saveProgress(progress);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (isShuttingDown.getAndSet(true)) {
            log.debug("ReaderScheduler вже завершується, пропускаємо повторний виклик");
            return;
        }

        stopSaving();

        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
            try {
                if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    log.warn("scheduler не завершив роботу примусово");
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (debounceScheduler != null && !debounceScheduler.isShutdown()) {
            debounceScheduler.shutdownNow();
            try {
                if (!debounceScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    log.warn("debounceScheduler не завершив роботу примусово");
                }
            } catch (InterruptedException e) {
                debounceScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        log.info("ReaderScheduler завершено");
    }
}