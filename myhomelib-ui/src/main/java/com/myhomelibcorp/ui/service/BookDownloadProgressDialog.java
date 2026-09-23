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
    private final Label statusLabel = new Label(UiText.text("ui.java.book_download_progress_dialog.4258f771b1"));
    private final Label processedLabel = new Label(UiText.text("ui.java.book_download_progress_dialog.cbdb5b46bc"));
    private final Label downloadedLabel = new Label(UiText.text("ui.java.book_download_progress_dialog.7bc13ee166"));
    private final Label savedLabel = new Label(UiText.text("ui.java.book_download_progress_dialog.d6cbdcd070"));
    private final Label localLabel = new Label(UiText.text("ui.java.book_download_progress_dialog.4a8bf619ca"));
    private final Label failedLabel = new Label(UiText.text("ui.java.book_download_progress_dialog.556eb820b7"));
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Button closeButton = new Button(UiText.text("common.close"));
    private PauseTransition autoClose;

    public BookDownloadProgressDialog(Window owner, int total) {
        stage.setTitle(UiText.text("ui.opds.tokens.scope.download"));
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
        update(0, Math.max(0, total), 0, 0, 0, UiText.text("ui.java.book_download_progress_dialog.4258f771b1"));
    }

    public void show() {
        if (!stage.isShowing()) stage.show();
    }

    public void update(int completed, int total, int downloaded, int alreadyLocal, int failed, String status) {
        int safeTotal = Math.max(0, total);
        int safeCompleted = Math.max(0, Math.min(completed, safeTotal));
        int safeDownloaded = Math.max(0, downloaded);
        progressBar.setProgress(safeTotal == 0 ? 0 : (double) safeCompleted / safeTotal);
        processedLabel.setText(UiText.text("ui.java.book_download_progress_dialog.b077de9d62") + safeCompleted + " / " + safeTotal);
        downloadedLabel.setText(UiText.text("ui.java.book_download_progress_dialog.491361bc90") + safeDownloaded + " / " + safeTotal + UiText.text("ui.java.book_download_progress_dialog.faf0c7e42c"));
        savedLabel.setText(UiText.text("ui.java.book_download_progress_dialog.6126a2c620") + safeDownloaded);
        localLabel.setText(UiText.text("ui.java.book_download_progress_dialog.9dca3fac75") + Math.max(0, alreadyLocal));
        failedLabel.setText(UiText.text("ui.java.book_download_progress_dialog.40a22f36ad") + Math.max(0, failed));
        if (status != null && !status.isBlank()) statusLabel.setText(status);
    }

    public void complete(int total, int downloaded, int alreadyLocal, int failed) {
        update(total, total, downloaded, alreadyLocal, failed,
                failed == 0 ? UiText.text("ui.java.book_download_progress_dialog.e5bb5c8529") : UiText.text("ui.java.book_download_progress_dialog.0580b2a211"));
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
