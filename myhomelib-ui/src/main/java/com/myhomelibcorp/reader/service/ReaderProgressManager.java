package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.application.usecase.book.UpdateBookUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.web.WebEngine;
import javafx.util.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

@Service
@Scope("prototype")
@RequiredArgsConstructor
@Slf4j
public class ReaderProgressManager {

    private final UpdateBookUseCase updateBookUseCase;

    private Timeline progressUpdateTimer;
    private double lastProgress = -1;

    public void startProgressTimer(final WebEngine webEngine,
                                   final String bookId,
                                   final ProgressBar progressBar,
                                   final Label progressLabel,
                                   final Consumer<Double> onProgressChanged) {
        if (bookId == null) {
            log.warn("startProgressTimer: bookId is null");
            return;
        }
        if (progressUpdateTimer != null) {
            progressUpdateTimer.stop();
        }
        progressUpdateTimer = new Timeline(new KeyFrame(Duration.millis(500), e -> {
            updateProgress(webEngine, bookId, progressBar, progressLabel, onProgressChanged);
        }));
        progressUpdateTimer.setCycleCount(Timeline.INDEFINITE);
        progressUpdateTimer.play();
        log.debug("Таймер збереження прогресу запущено для книги {}", bookId);
    }

    public void stopProgressTimer() {
        if (progressUpdateTimer != null) {
            progressUpdateTimer.stop();
            progressUpdateTimer = null;
            log.debug("Таймер збереження прогресу зупинено");
        }
    }

    private void updateProgress(final WebEngine webEngine,
                                final String bookId,
                                final ProgressBar progressBar,
                                final Label progressLabel,
                                final Consumer<Double> onProgressChanged) {
        try {
            Object progressObj = webEngine.executeScript("window.progress");
            if (progressObj instanceof Number) {
                double progress = ((Number) progressObj).doubleValue();
                if (progress < 0) progress = 0;
                if (progress > 1) progress = 1;

                final double finalProgress = progress;

                Platform.runLater(() -> {
                    progressBar.setProgress(finalProgress);
                    progressLabel.setText((int) (finalProgress * 100) + "%");
                });

                if (Math.abs(finalProgress - lastProgress) > 0.01) {
                    lastProgress = finalProgress;
                    int progressPercent = (int) (finalProgress * 100);

                    // Зберігаємо в БД через окремий потік
                    new Thread(() -> {
                        try {
                            updateBookUseCase.updateProgress(BookId.fromString(bookId), progressPercent);
                            log.trace("Збережено прогрес: {}% для книги {}", progressPercent, bookId);
                        } catch (Exception ex) {
                            log.error("Помилка збереження прогресу в БД для книги {}: {}", bookId, ex.getMessage());
                        }
                    }).start();

                    if (onProgressChanged != null) {
                        onProgressChanged.accept(finalProgress);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Помилка оновлення прогресу: {}", e.getMessage());
        }
    }

    public void restoreScrollPosition(final WebEngine webEngine, final double progress) {
        if (progress > 0) {
            Platform.runLater(() -> {
                try {
                    String script = "window.scrollTo(0, (document.documentElement.scrollHeight - document.documentElement.clientHeight) * " + progress + ")";
                    webEngine.executeScript(script);
                    log.debug("Відновлено позицію скролу: {}%", progress * 100);
                } catch (Exception e) {
                    log.debug("Не вдалося відновити позицію скролу: {}", e.getMessage());
                }
            });
        } else {
            log.debug("Прогрес = 0, скрол не відновлюється");
        }
    }

    public void saveProgress(final WebEngine webEngine, final String bookId) {
        if (bookId == null) return;
        try {
            Object scrollY = webEngine.executeScript("window.progress");
            if (scrollY instanceof Double) {
                int progress = (int) (((Double) scrollY) * 100);
                if (progress > 0 && progress <= 100) {
                    updateBookUseCase.updateProgress(BookId.fromString(bookId), progress);
                    log.debug("Примусово збережено прогрес: {}% для книги {}", progress, bookId);
                }
            }
        } catch (Exception e) {
            log.debug("Не вдалося примусово зберегти прогрес: {}", e.getMessage());
        }
    }
}