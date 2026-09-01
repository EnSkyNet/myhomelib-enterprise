package com.myhomelibcorp.ui.service;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/** Compact non-blocking progress window for one or many online book downloads. */
public final class BookDownloadProgressDialog {
    private final Stage stage = new Stage();
    private final Label statusLabel = new Label("Підключення до online-бібліотеки…");
    private final Label processedLabel = new Label("Оброблено: 0 / 0");
    private final Label downloadedLabel = new Label("Завантажено: 0 / 0 книг");
    private final Label savedLabel = new Label("Збережено до бібліотеки: 0");
    private final Label localLabel = new Label("Уже локальні: 0");
    private final Label failedLabel = new Label("Помилок: 0");
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Button closeButton = new Button("Закрити");
    private PauseTransition autoClose;

    public BookDownloadProgressDialog(Window owner, int total) {
        stage.setTitle("Завантаження книг");
        stage.initModality(Modality.NONE);
        if (owner != null) stage.initOwner(owner);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        closeButton.setDisable(true);
        closeButton.setOnAction(e -> stage.close());
        VBox root = new VBox(10, statusLabel, progressBar, processedLabel, downloadedLabel,
                savedLabel, localLabel, failedLabel, closeButton);
        root.setPadding(new Insets(14));
        root.setPrefWidth(430);
        stage.setScene(new Scene(root));
        update(0, Math.max(0, total), 0, 0, 0, "Підключення до online-бібліотеки…");
    }

    public void show() {
        if (!stage.isShowing()) stage.show();
    }

    public void update(int completed, int total, int downloaded, int alreadyLocal, int failed, String status) {
        int safeTotal = Math.max(0, total);
        int safeCompleted = Math.max(0, Math.min(completed, safeTotal));
        int safeDownloaded = Math.max(0, downloaded);
        progressBar.setProgress(safeTotal == 0 ? 0 : (double) safeCompleted / safeTotal);
        processedLabel.setText("Оброблено: " + safeCompleted + " / " + safeTotal);
        downloadedLabel.setText("Завантажено: " + safeDownloaded + " / " + safeTotal + " книг");
        savedLabel.setText("Збережено до бібліотеки: " + safeDownloaded);
        localLabel.setText("Уже локальні: " + Math.max(0, alreadyLocal));
        failedLabel.setText("Помилок: " + Math.max(0, failed));
        if (status != null && !status.isBlank()) statusLabel.setText(status);
    }

    public void complete(int total, int downloaded, int alreadyLocal, int failed) {
        update(total, total, downloaded, alreadyLocal, failed,
                failed == 0 ? "Готово. Книги збережено до бібліотеки." : "Завершено з помилками.");
        closeButton.setDisable(false);
        stage.toFront();
        if (autoClose != null) autoClose.stop();
        autoClose = new PauseTransition(Duration.seconds(failed == 0 ? 1.2 : 2.5));
        autoClose.setOnFinished(event -> {
            if (stage.isShowing()) stage.close();
        });
        autoClose.play();
    }
}
