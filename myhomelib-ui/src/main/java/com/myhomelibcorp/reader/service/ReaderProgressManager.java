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

    /**
     * Запускає таймер автоматичного оновлення прогресу.
     * Параметри позначені як final, щоб коректно працювати з лямбда-виразами.
     */
    public void startProgressTimer(final WebEngine webEngine,
                                   final String bookId,
                                   final ProgressBar progressBar,
                                   final Label progressLabel,
                                   final Consumer<Double> onProgressChanged) {
        if (bookId == null) return;
        if (progressUpdateTimer != null) {
            progressUpdateTimer.stop();
        }
        progressUpdateTimer = new Timeline(new KeyFrame(Duration.millis(500), e -> {
            updateProgress(webEngine, bookId, progressBar, progressLabel, onProgressChanged);
        }));
        progressUpdateTimer.setCycleCount(Timeline.INDEFINITE);
        progressUpdateTimer.play();
    }

    public void stopProgressTimer() {
        if (progressUpdateTimer != null) {
            progressUpdateTimer.stop();
            progressUpdateTimer = null;
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
                double rawProgress = ((Number) progressObj).doubleValue();
                if (rawProgress < 0) rawProgress = 0;
                if (rawProgress > 1) rawProgress = 1;

                // Создаем строго финальную переменную для передачи в лямбду
                final double finalProgress = rawProgress;

                Platform.runLater(() -> {
                    progressBar.setProgress(finalProgress);
                    progressLabel.setText((int) (finalProgress * 100) + "%");
                });

                if (Math.abs(finalProgress - lastProgress) > 0.01) {
                    lastProgress = finalProgress;
                    int progressPercent = (int) (finalProgress * 100);

                    // Выносим блокирующее сохранение в БД в фоновый поток,
                    // чтобы UI (скроллинг) не зависал каждые полсекунды
                    new Thread(() -> {
                        try {
                            updateBookUseCase.updateProgress(BookId.fromString(bookId), progressPercent);
                        } catch (Exception ex) {
                            log.error("Помилка збереження прогресу в БД", ex);
                        }
                    }).start();

                    if (onProgressChanged != null) {
                        onProgressChanged.accept(finalProgress);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Помилка оновлення прогресу", e);
        }
    }


    public void restoreScrollPosition(final WebEngine webEngine, final double progress) {
        if (progress > 0) {
            String script = "window.scrollTo(0, (document.documentElement.scrollHeight - document.documentElement.clientHeight) * " + progress + ")";
            webEngine.executeScript(script);
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
                }
            }
        } catch (Exception e) {
            log.debug("Не вдалося зберегти прогрес", e);
        }
    }
}