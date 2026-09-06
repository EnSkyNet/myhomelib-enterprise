package com.myhomelibcorp.ui.imports;

import com.myhomelibcorp.application.progress.OperationProgress;
import com.myhomelibcorp.application.progress.OperationStage;
import com.myhomelibcorp.ui.service.LocalizationService;
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
    private final LocalizationService i18n;
    private final ProgressBar progressBar;
    private final ProgressIndicator spinner;
    private final Label titleLabel;
    private final Label statusLabel;
    private final Label detailLabel;
    private final Label speedLabel;
    private final Label countsLabel;
    private final NumberFormat numberFormat = NumberFormat.getInstance(Locale.getDefault());

    private long startTime;
    private long lastUpdateTime;
    private long lastProcessed;
    private volatile boolean cancelled;

    public ImportProgressDialog(LocalizationService i18n, String title) {
        this.i18n = java.util.Objects.requireNonNull(i18n, "i18n");
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

        statusLabel = new Label(i18n.text("ui.import.progress.preparing"));
        statusLabel.setStyle("-fx-font-size: 14px;");

        detailLabel = new Label(i18n.text("ui.import.progress.books_zero"));
        detailLabel.getStyleClass().add("muted-text");

        speedLabel = new Label(i18n.text("ui.import.progress.speed_zero"));
        speedLabel.getStyleClass().addAll("muted-text", "small-text");

        countsLabel = new Label("");
        countsLabel.setWrapText(true);
        countsLabel.setTextAlignment(TextAlignment.CENTER);
        countsLabel.getStyleClass().addAll("muted-text", "small-text");

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
                countsLabel,
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
        Platform.runLater(() -> applyProgress(processed, total, status));
    }

    /** Renders authoritative application-layer telemetry without inventing a synthetic 0..1000 scale. */
    public void update(OperationProgress progress) {
        if (progress == null) return;
        Platform.runLater(() -> {
            String stageText = stageText(progress.stage());
            if (!progress.currentItem().isBlank()) stageText += " · " + progress.currentItem();
            applyProgress(progress.processed(), progress.total(), stageText);
            countsLabel.setText(String.format(
                    i18n.text("ui.import.progress.counts"),
                    numberFormat.format(progress.inserted()),
                    numberFormat.format(progress.updated()),
                    numberFormat.format(progress.deleted()),
                    numberFormat.format(progress.skipped()),
                    numberFormat.format(progress.duplicates()),
                    numberFormat.format(progress.errors())));
        });
    }

    private void applyProgress(long processed, long total, String status) {
        boolean determinate = total > 0;
        double progress = determinate ? Math.min(1.0, Math.max(0.0, (double) processed / total)) : -1.0;
        progressBar.setProgress(determinate ? progress : ProgressBar.INDETERMINATE_PROGRESS);
        spinner.setVisible(!determinate);

        String formattedProcessed = numberFormat.format(Math.max(0L, processed));
        detailLabel.setText(determinate
                ? i18n.format("ui.import.progress.records_of_total", formattedProcessed, numberFormat.format(total))
                : i18n.format("ui.import.progress.records", formattedProcessed));

        if (status != null && !status.isEmpty()) statusLabel.setText(status);
        updateSpeed(Math.max(0L, processed));
        titleLabel.setText(determinate
                ? String.format("%s — %d%%", stage.getTitle(), Math.round(progress * 100))
                : stage.getTitle());
    }

    private void updateSpeed(long processed) {
        long now = System.currentTimeMillis();
        long elapsed = now - lastUpdateTime;
        if (elapsed > 1000 && processed > lastProcessed) {
            long delta = processed - lastProcessed;
            double speed = (double) delta / (elapsed / 1000.0);
            speedLabel.setText(i18n.format("ui.import.progress.speed", speed));
            lastUpdateTime = now;
            lastProcessed = processed;
        } else if (processed > 0) {
            long totalElapsed = now - startTime;
            if (totalElapsed > 0) {
                double avgSpeed = (double) processed / (totalElapsed / 1000.0);
                speedLabel.setText(i18n.format("ui.import.progress.speed_average", avgSpeed));
            }
        }
    }

    private String stageText(OperationStage stage) {
        if (stage == null) return i18n.text("ui.import.stage.processing");
        return switch (stage) {
            case CHECKING_SERVER -> i18n.text("ui.import.stage.checking_server");
            case DOWNLOADING -> i18n.text("ui.import.stage.downloading");
            case CREATING_CHECKPOINT -> i18n.text("ui.import.stage.creating_checkpoint");
            case VALIDATING -> i18n.text("ui.import.stage.validating");
            case READING_CATALOG -> i18n.text("ui.import.stage.reading_catalog");
            case IMPORTING -> i18n.text("ui.import.stage.importing");
            case UPDATING_AUTHORS -> i18n.text("ui.import.stage.updating_authors");
            case APPLYING_DELETIONS -> i18n.text("ui.import.stage.applying_deletions");
            case UPDATING_SEARCH_INDEX -> i18n.text("ui.import.stage.updating_index");
            case ROLLING_BACK -> i18n.text("ui.import.stage.rolling_back");
            case REFRESHING_STATISTICS -> i18n.text("ui.import.stage.refreshing_statistics");
            case INTEGRITY_CHECKS -> i18n.text("ui.import.stage.integrity_checks");
            case SYNCHRONIZING_FILES -> i18n.text("ui.import.stage.synchronizing_files");
            case OPTIMIZING_DATABASE -> i18n.text("ui.import.stage.optimizing_database");
            case BACKING_UP -> i18n.text("ui.import.stage.backing_up");
            case RESTORING -> i18n.text("ui.import.stage.restoring");
            case CREATING_COLLECTION -> i18n.text("ui.import.stage.creating_collection");
            case DELETING_COLLECTION -> i18n.text("ui.import.stage.deleting_collection");
            case FINALIZING -> i18n.text("ui.import.stage.finalizing");
            case BOOK_DOWNLOAD -> i18n.text("ui.import.stage.book_download");
            case COMPLETED -> i18n.text("ui.import.stage.completed");
            case CANCELLED -> i18n.text("ui.import.stage.cancelled");
            case FAILED -> i18n.text("ui.import.stage.failed");
        };
    }

    public void updateStatus(String status) {
        Platform.runLater(() -> {
            statusLabel.setText(status);
        });
    }

    public void setTotal(long total) {
        Platform.runLater(() -> {
            String formattedTotal = numberFormat.format(total);
            detailLabel.setText(i18n.format("ui.import.progress.books_of_total", formattedTotal));
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
            statusLabel.setText(i18n.text("ui.import.status.cancelling"));
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