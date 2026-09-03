package com.myhomelibcorp.ui.imports;

import com.myhomelibcorp.application.progress.OperationProgress;
import com.myhomelibcorp.application.progress.OperationStage;
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
    private final Label countsLabel;
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

        speedLabel = new Label("0 записів/с");
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
                    "Додано: %s · Оновлено: %s · Змінено стан: %s · Пропущено: %s · Дублікатів: %s · Помилок: %s",
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
                ? formattedProcessed + " / " + numberFormat.format(total) + " записів"
                : formattedProcessed + " записів");

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
            speedLabel.setText(String.format("%.1f записів/с", speed));
            lastUpdateTime = now;
            lastProcessed = processed;
        } else if (processed > 0) {
            long totalElapsed = now - startTime;
            if (totalElapsed > 0) {
                double avgSpeed = (double) processed / (totalElapsed / 1000.0);
                speedLabel.setText(String.format("%.1f записів/с (середня)", avgSpeed));
            }
        }
    }

    private static String stageText(OperationStage stage) {
        if (stage == null) return "Обробка";
        return switch (stage) {
            case CHECKING_SERVER -> "Перевірка сервера";
            case DOWNLOADING -> "Завантаження";
            case CREATING_CHECKPOINT -> "Створення точки відновлення";
            case VALIDATING -> "Перевірка даних";
            case READING_CATALOG -> "Читання каталогу";
            case IMPORTING -> "Імпорт каталогу";
            case UPDATING_AUTHORS -> "Оновлення авторів";
            case APPLYING_DELETIONS -> "Обробка DEL";
            case UPDATING_SEARCH_INDEX -> "Оновлення Lucene";
            case ROLLING_BACK -> "Відкат оновлення";
            case REFRESHING_STATISTICS -> "Перерахунок статистики";
            case INTEGRITY_CHECKS -> "Перевірка цілісності";
            case SYNCHRONIZING_FILES -> "Синхронізація файлів";
            case OPTIMIZING_DATABASE -> "Оптимізація БД";
            case BACKING_UP -> "Резервне копіювання";
            case RESTORING -> "Відновлення";
            case CREATING_COLLECTION -> "Створення колекції";
            case DELETING_COLLECTION -> "Видалення колекції";
            case FINALIZING -> "Завершення";
            case BOOK_DOWNLOAD -> "Завантаження книги";
            case COMPLETED -> "Завершено";
            case CANCELLED -> "Скасовано";
            case FAILED -> "Помилка";
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