package com.myhomelibcorp.ui.imports;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import lombok.extern.slf4j.Slf4j;

import java.text.NumberFormat;
import java.util.Locale;

@Slf4j
public class ImportProgressDialog {

    private final Stage stage;
    private final ProgressBar progressBar;
    private final ProgressIndicator spinner;
    private final Label titleLabel;
    private final Label statusLabel;
    private final Label detailLabel;
    private final Label speedLabel;
    private final NumberFormat numberFormat = NumberFormat.getInstance(Locale.getDefault());

    private long startTime;
    private long lastUpdateTime;
    private long lastProcessed;
    private volatile boolean cancelled;

    public ImportProgressDialog(String title) {
        this.stage = new Stage();
        stage.initStyle(StageStyle.UNDECORATED);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle(title);

        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(500);
        progressBar.setMinHeight(20);

        spinner = new ProgressIndicator();
        spinner.setPrefSize(40, 40);

        titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        titleLabel.setTextAlignment(TextAlignment.CENTER);

        statusLabel = new Label("Підготовка...");
        statusLabel.setStyle("-fx-font-size: 14px;");

        detailLabel = new Label("0 / 0 книг");
        detailLabel.getStyleClass().add("muted-text");

        speedLabel = new Label("0 книг/с");
        speedLabel.getStyleClass().addAll("muted-text", "small-text");

        VBox content = new VBox(10);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(30));
        content.getStyleClass().add("progress-dialog-content");
        content.getChildren().addAll(
                titleLabel,
                spinner,
                progressBar,
                statusLabel,
                detailLabel,
                speedLabel
        );

        Scene scene = new Scene(content, 560, 300);
        scene.getStylesheets().add(getClass().getResource("/css/import-progress.css").toExternalForm());
        stage.setScene(scene);
        stage.setResizable(false);

        startTime = System.currentTimeMillis();
        lastUpdateTime = startTime;
        lastProcessed = 0;
        cancelled = false;

        // Закриття по ESC
        scene.setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                cancel();
            }
        });
    }

    public void show() {
        Platform.runLater(() -> {
            stage.show();
            stage.toFront();
        });
    }

    public void hide() {
        Platform.runLater(() -> {
            if (stage.isShowing()) {
                stage.hide();
            }
        });
    }

    public void close() {
        Platform.runLater(() -> {
            if (stage.isShowing()) {
                stage.close();
            }
        });
    }

    public boolean isShowing() {
        return stage.isShowing();
    }

    public void updateProgress(long processed, long total, String status) {
        Platform.runLater(() -> {
            double progress = total > 0 ? Math.min(1.0, (double) processed / total) : 0;
            progressBar.setProgress(progress);

            String formattedProcessed = numberFormat.format(processed);
            String formattedTotal = numberFormat.format(total);
            detailLabel.setText(formattedProcessed + " / " + formattedTotal + " книг");

            if (status != null && !status.isEmpty()) {
                statusLabel.setText(status);
            }

            // Розрахунок швидкості
            long now = System.currentTimeMillis();
            long elapsed = now - lastUpdateTime;
            if (elapsed > 1000 && processed > lastProcessed) {
                long delta = processed - lastProcessed;
                double speed = (double) delta / (elapsed / 1000.0);
                speedLabel.setText(String.format("%.1f книг/с", speed));
                lastUpdateTime = now;
                lastProcessed = processed;
            } else if (processed > 0) {
                long totalElapsed = now - startTime;
                if (totalElapsed > 0) {
                    double avgSpeed = (double) processed / (totalElapsed / 1000.0);
                    speedLabel.setText(String.format("%.1f книг/с (середня)", avgSpeed));
                }
            }

            // Оновлення заголовка з прогресом
            titleLabel.setText(String.format("%s — %d%%",
                    stage.getTitle(),
                    Math.round(progress * 100)));
        });
    }

    public void updateStatus(String status) {
        Platform.runLater(() -> {
            statusLabel.setText(status);
        });
    }

    public void setTotal(long total) {
        Platform.runLater(() -> {
            String formattedTotal = numberFormat.format(total);
            detailLabel.setText("0 / " + formattedTotal + " книг");
        });
    }

    public void setIndeterminate(boolean indeterminate) {
        Platform.runLater(() -> {
            progressBar.setProgress(indeterminate ? -1 : 0);
            spinner.setVisible(indeterminate);
        });
    }

    public void cancel() {
        cancelled = true;
        Platform.runLater(() -> {
            statusLabel.setText("Скасування...");
            spinner.setVisible(true);
        });
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setOnCancel(Runnable onCancel) {
        stage.setOnCloseRequest(event -> {
            if (onCancel != null) {
                onCancel.run();
            }
        });
    }
}