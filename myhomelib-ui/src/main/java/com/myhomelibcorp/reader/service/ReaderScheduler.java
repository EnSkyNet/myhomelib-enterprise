package com.myhomelibcorp.reader.service;

import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.web.WebEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class ReaderScheduler {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService debounceScheduler = Executors.newSingleThreadScheduledExecutor();

    private ScheduledFuture<?> saveTask;
    private ScheduledFuture<?> debounceFuture;

    public void startSaving(WebEngine engine,
                            ReaderProgressManager progressManager,
                            ProgressBar progressBar,
                            Label progressLabel,
                            Runnable onSave) {
        if (saveTask != null && !saveTask.isDone()) {
            saveTask.cancel(false);
        }

        saveTask = scheduler.scheduleAtFixedRate(() -> {
            if (engine == null || progressManager.getCurrentBook() == null) return;
            Platform.runLater(() -> {
                try {
                    var current = progressManager.getCurrentProgress(engine);
                    if (current == null) return;

                    double percent = current.getPercent() / 100.0;
                    if (progressBar != null) {
                        progressBar.setProgress(percent);
                    }
                    if (progressLabel != null) {
                        progressLabel.setText((int) (percent * 100) + "%");
                    }

                    if (progressManager.shouldSave(current)) {
                        if (debounceFuture != null && !debounceFuture.isDone()) {
                            debounceFuture.cancel(false);
                        }
                        debounceFuture = debounceScheduler.schedule(() -> {
                            Platform.runLater(() -> {
                                var finalProgress = progressManager.getCurrentProgress(engine);
                                if (finalProgress != null) {
                                    progressManager.saveProgress(finalProgress);
                                    if (onSave != null) onSave.run();
                                }
                            });
                        }, 500, TimeUnit.MILLISECONDS);
                    }
                } catch (Exception e) {
                    log.error("Помилка обробки прогресу", e);
                }
            });
        }, 3000, 1000, TimeUnit.MILLISECONDS);

        log.info("Таймер збереження прогресу запущено (з debounce)");
    }

    public void stopSaving() {
        if (saveTask != null && !saveTask.isDone()) {
            saveTask.cancel(false);
            saveTask = null;
        }
        if (debounceFuture != null && !debounceFuture.isDone()) {
            debounceFuture.cancel(false);
        }
        log.info("Таймер збереження прогресу зупинено");
    }

    public void forceSaveNow(WebEngine engine, ReaderProgressManager progressManager) {
        var progress = progressManager.getCurrentProgress(engine);
        if (progress != null) {
            progressManager.saveProgress(progress);
        }
    }

    public void shutdown() {
        stopSaving();
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        if (debounceScheduler != null && !debounceScheduler.isShutdown()) {
            debounceScheduler.shutdownNow();
        }
    }
}