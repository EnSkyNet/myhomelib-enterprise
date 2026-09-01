package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.progress.OperationProgress;
import com.myhomelibcorp.application.progress.OperationStage;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Throttled/coalesced catalog-update telemetry UI. The application layer never depends on JavaFX;
 * this class only renders immutable OperationProgress snapshots.
 */
public final class CatalogUpdateProgressDialog {
    private static final Duration UI_THROTTLE = Duration.millis(180);

    private final Stage stage = new Stage();
    private final ProgressBar progressBar = new ProgressBar(-1);
    private final Label stageLabel = new Label("Підготовка…");
    private final Label currentItemLabel = new Label("");
    private final Label processedLabel = new Label("—");
    private final Label bytesLabel = new Label("—");
    private final Label insertedLabel = new Label("0");
    private final Label updatedLabel = new Label("0");
    private final Label deletedLabel = new Label("0");
    private final Label skippedLabel = new Label("0");
    private final Label duplicatesLabel = new Label("0");
    private final Label warningsLabel = new Label("0");
    private final Label errorsLabel = new Label("0");
    private final Button cancelButton = new Button("Скасувати");
    private final NumberFormat number = NumberFormat.getIntegerInstance(Locale.getDefault());
    private final AtomicReference<OperationProgress> pending = new AtomicReference<>();
    private final AtomicBoolean scheduled = new AtomicBoolean(false);

    private volatile Runnable onCancel;
    private volatile boolean terminal;

    public CatalogUpdateProgressDialog(Window owner) {
        stage.setTitle("Оновлення каталогу");
        stage.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) stage.initOwner(owner);
        stage.setResizable(false);

        Label title = new Label("Оновлення каталогу");
        title.setStyle("-fx-font-size: 17px; -fx-font-weight: bold;");
        stageLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        currentItemLabel.setWrapText(true);
        currentItemLabel.setMaxWidth(Double.MAX_VALUE);
        currentItemLabel.getStyleClass().add("muted-text");
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefWidth(580);

        GridPane counters = new GridPane();
        counters.setHgap(22);
        counters.setVgap(7);
        addCounter(counters, 0, 0, "Оброблено", processedLabel);
        addCounter(counters, 0, 1, "Додано", insertedLabel);
        addCounter(counters, 0, 2, "Оновлено", updatedLabel);
        addCounter(counters, 0, 3, "Видалено", deletedLabel);
        addCounter(counters, 1, 0, "Пропущено", skippedLabel);
        addCounter(counters, 1, 1, "Дублікати", duplicatesLabel);
        addCounter(counters, 1, 2, "Попередження", warningsLabel);
        addCounter(counters, 1, 3, "Помилки", errorsLabel);
        addCounter(counters, 0, 4, "Завантажено", bytesLabel);

        cancelButton.setOnAction(e -> requestCancel());
        HBox actions = new HBox(cancelButton);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(12, title, stageLabel, progressBar, currentItemLabel, counters, actions);
        root.setPadding(new Insets(20));
        VBox.setVgrow(currentItemLabel, Priority.NEVER);
        stage.setScene(new Scene(root, 640, 360));

        stage.setOnCloseRequest(event -> {
            if (!terminal) {
                event.consume();
                requestCancel();
            }
        });
    }

    public void setOnCancel(Runnable onCancel) {
        this.onCancel = onCancel;
    }

    public void show() {
        runFx(() -> {
            if (!stage.isShowing()) stage.show();
            stage.toFront();
        });
    }

    public void close() {
        terminal = true;
        runFx(() -> {
            if (stage.isShowing()) stage.close();
        });
    }

    /**
     * Coalesces producer events and renders at most about 5–6 snapshots/second.
     * Terminal state is rendered immediately.
     */
    public void update(OperationProgress progress) {
        if (progress == null || terminal) return;
        pending.set(progress);
        if (isTerminal(progress.stage())) {
            runFx(this::flushPending);
            return;
        }
        if (!scheduled.compareAndSet(false, true)) return;
        runFx(() -> {
            PauseTransition pause = new PauseTransition(UI_THROTTLE);
            pause.setOnFinished(e -> {
                scheduled.set(false);
                flushPending();
                // A producer may have replaced the snapshot while the UI was rendering.
                if (pending.get() != null && !terminal) update(pending.get());
            });
            pause.play();
        });
    }

    private void flushPending() {
        OperationProgress progress = pending.getAndSet(null);
        if (progress == null) return;
        apply(progress);
    }

    private void apply(OperationProgress progress) {
        stageLabel.setText(stageText(progress.stage()));
        currentItemLabel.setText(progress.currentItem());
        currentItemLabel.setVisible(!progress.currentItem().isBlank());
        currentItemLabel.setManaged(currentItemLabel.isVisible());

        double fraction = progress.fraction();
        progressBar.setProgress(fraction < 0 ? ProgressBar.INDETERMINATE_PROGRESS : fraction);
        if (progress.total() >= 0) {
            processedLabel.setText(number.format(progress.processed()) + " / " + number.format(progress.total()));
        } else {
            processedLabel.setText(number.format(progress.processed()));
        }
        insertedLabel.setText(number.format(progress.inserted()));
        updatedLabel.setText(number.format(progress.updated()));
        deletedLabel.setText(number.format(progress.deleted()));
        skippedLabel.setText(number.format(progress.skipped()));
        duplicatesLabel.setText(number.format(progress.duplicates()));
        warningsLabel.setText(number.format(progress.warnings()));
        errorsLabel.setText(number.format(progress.errors()));
        if (progress.bytesProcessed() > 0 || progress.bytesTotal() >= 0) {
            bytesLabel.setText(progress.bytesTotal() >= 0
                    ? formatBytes(progress.bytesProcessed()) + " / " + formatBytes(progress.bytesTotal())
                    : formatBytes(progress.bytesProcessed()));
        } else {
            bytesLabel.setText("—");
        }
        cancelButton.setDisable(!progress.cancellable());

        if (isTerminal(progress.stage())) {
            terminal = true;
            cancelButton.setDisable(true);
        }
    }

    private void requestCancel() {
        if (terminal || cancelButton.isDisabled()) return;
        cancelButton.setDisable(true);
        stageLabel.setText("Скасування…");
        Runnable action = onCancel;
        if (action != null) action.run();
    }

    private static void addCounter(GridPane grid, int columnPair, int row, String title, Label value) {
        int base = columnPair * 2;
        Label name = new Label(title + ":");
        name.getStyleClass().add("muted-text");
        value.setStyle("-fx-font-weight: bold;");
        grid.add(name, base, row);
        grid.add(value, base + 1, row);
    }

    private static String stageText(OperationStage stage) {
        if (stage == null) return "Обробка…";
        return switch (stage) {
            case CHECKING_SERVER -> "Перевірка сервера";
            case DOWNLOADING -> "Завантаження";
            case VALIDATING -> "Перевірка даних";
            case READING_CATALOG -> "Читання каталогу";
            case IMPORTING -> "Імпорт каталогу";
            case UPDATING_AUTHORS -> "Оновлення авторів";
            case APPLYING_DELETIONS -> "Застосування видалень";
            case UPDATING_SEARCH_INDEX -> "Оновлення пошукового індексу";
            case INTEGRITY_CHECKS -> "Перевірка цілісності";
            case FINALIZING -> "Завершення";
            case BOOK_DOWNLOAD -> "Завантаження книги";
            case COMPLETED -> "Завершено";
            case CANCELLED -> "Скасовано";
            case FAILED -> "Помилка";
        };
    }


    private static String formatBytes(long bytes) {
        long value = Math.max(0L, bytes);
        if (value < 1024) return value + " B";
        double kb = value / 1024.0;
        if (kb < 1024.0) return String.format(Locale.ROOT, "%.1f KiB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024.0) return String.format(Locale.ROOT, "%.1f MiB", mb);
        return String.format(Locale.ROOT, "%.2f GiB", mb / 1024.0);
    }

    private static boolean isTerminal(OperationStage stage) {
        return stage == OperationStage.COMPLETED || stage == OperationStage.CANCELLED || stage == OperationStage.FAILED;
    }

    private static void runFx(Runnable action) {
        if (Platform.isFxApplicationThread()) action.run();
        else Platform.runLater(action);
    }
}
