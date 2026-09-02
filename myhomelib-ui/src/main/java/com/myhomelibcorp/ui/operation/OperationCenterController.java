package com.myhomelibcorp.ui.operation;

import com.myhomelibcorp.application.progress.OperationStage;
import com.myhomelibcorp.ui.navigation.WorkspaceLifecycle;
import com.myhomelibcorp.ui.util.UiExecutor;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
@RequiredArgsConstructor
public class OperationCenterController implements WorkspaceLifecycle {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final OperationCenterService operationCenter;
    private final ObservableList<OperationCenterEntry> rows = FXCollections.observableArrayList();
    private AutoCloseable registration;

    @FXML private Label summaryLabel;
    @FXML private TableView<OperationCenterEntry> operationsTable;
    @FXML private TableColumn<OperationCenterEntry, String> stateColumn;
    @FXML private TableColumn<OperationCenterEntry, String> titleColumn;
    @FXML private TableColumn<OperationCenterEntry, String> stageColumn;
    @FXML private TableColumn<OperationCenterEntry, String> progressColumn;
    @FXML private TableColumn<OperationCenterEntry, String> startedColumn;
    @FXML private TableColumn<OperationCenterEntry, String> durationColumn;
    @FXML private TextArea detailsArea;

    @FXML
    public void initialize() {
        stateColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(stateText(cell.getValue())));
        titleColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().title()));
        stageColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(stageText(cell.getValue().stage())));
        progressColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(progressText(cell.getValue())));
        startedColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(TIME_FORMAT.format(cell.getValue().startedAt())));
        durationColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(durationText(cell.getValue().duration(Instant.now()))));
        operationsTable.setItems(rows);
        operationsTable.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, selected) -> showDetails(selected));

        registration = operationCenter.addListener(snapshot -> UiExecutor.runOnUiThread(() -> applySnapshot(snapshot)));
    }

    @FXML
    public void onRefresh() {
        applySnapshot(operationCenter.snapshot());
    }

    @FXML
    public void onClearCompleted() {
        operationCenter.clearCompleted();
    }

    private void applySnapshot(List<OperationCenterEntry> snapshot) {
        String selectedId = operationsTable.getSelectionModel().getSelectedItem() == null
                ? null : operationsTable.getSelectionModel().getSelectedItem().operationId();
        rows.setAll(snapshot == null ? List.of() : snapshot);
        int active = 0;
        for (OperationCenterEntry entry : rows) if (entry.active()) active++;
        summaryLabel.setText(active == 0
                ? "Активних операцій немає · Історія: " + rows.size()
                : "Активні: " + active + " · Усього в журналі: " + rows.size());

        if (selectedId != null) {
            for (OperationCenterEntry entry : rows) {
                if (selectedId.equals(entry.operationId())) {
                    operationsTable.getSelectionModel().select(entry);
                    return;
                }
            }
        }
        if (!rows.isEmpty()) operationsTable.getSelectionModel().selectFirst();
        else showDetails(null);
    }

    private void showDetails(OperationCenterEntry entry) {
        if (entry == null) {
            detailsArea.clear();
            return;
        }
        StringBuilder text = new StringBuilder();
        text.append(entry.title()).append('\n');
        text.append("Стан: ").append(stateText(entry)).append('\n');
        text.append("Етап: ").append(stageText(entry.stage())).append('\n');
        text.append("Прогрес: ").append(progressText(entry)).append('\n');
        text.append("Початок: ").append(TIME_FORMAT.format(entry.startedAt())).append('\n');
        text.append("Тривалість: ").append(durationText(entry.duration(Instant.now()))).append('\n');
        if (!entry.collectionId().isBlank()) text.append("Collection ID: ").append(entry.collectionId()).append('\n');
        if (entry.inserted() != 0 || entry.updated() != 0 || entry.deleted() != 0) {
            text.append("Додано: ").append(entry.inserted())
                    .append(" · Оновлено: ").append(entry.updated())
                    .append(" · DEL/змінено: ").append(entry.deleted()).append('\n');
        }
        if (entry.skipped() != 0 || entry.duplicates() != 0 || entry.warnings() != 0 || entry.errors() != 0) {
            text.append("Пропущено: ").append(entry.skipped())
                    .append(" · Дублікатів: ").append(entry.duplicates())
                    .append(" · Попереджень: ").append(entry.warnings())
                    .append(" · Помилок: ").append(entry.errors()).append('\n');
        }
        if (!entry.currentItem().isBlank()) text.append("Деталі: ").append(entry.currentItem()).append('\n');
        if (!entry.errorMessage().isBlank()) text.append("Помилка: ").append(entry.errorMessage()).append('\n');
        text.append("ID: ").append(entry.operationId());
        detailsArea.setText(text.toString());
    }

    private static String stateText(OperationCenterEntry entry) {
        if (entry == null) return "";
        return switch (entry.stage()) {
            case COMPLETED -> "✓ Завершено";
            case CANCELLED -> "○ Скасовано";
            case FAILED -> "⚠ Помилка";
            default -> "⟳ Виконується";
        };
    }

    private static String progressText(OperationCenterEntry entry) {
        if (entry == null) return "";
        if (entry.total() > 0) {
            double percent = Math.max(0, Math.min(100, entry.fraction() * 100.0));
            return String.format("%,d / %,d (%.1f%%)", entry.processed(), entry.total(), percent);
        }
        return entry.processed() > 0 ? String.format("%,d", entry.processed()) : "—";
    }

    private static String durationText(Duration duration) {
        long seconds = Math.max(0, duration == null ? 0 : duration.toSeconds());
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long rest = seconds % 60;
        return hours > 0 ? String.format("%02d:%02d:%02d", hours, minutes, rest) : String.format("%02d:%02d", minutes, rest);
    }

    private static String stageText(OperationStage stage) {
        if (stage == null) return "";
        return switch (stage) {
            case CHECKING_SERVER -> "Перевірка сервера";
            case DOWNLOADING -> "Завантаження";
            case VALIDATING -> "Перевірка";
            case READING_CATALOG -> "Читання каталогу";
            case IMPORTING -> "Імпорт";
            case UPDATING_AUTHORS -> "Оновлення авторів";
            case APPLYING_DELETIONS -> "Обробка DEL";
            case UPDATING_SEARCH_INDEX -> "Lucene";
            case REFRESHING_STATISTICS -> "Статистика";
            case INTEGRITY_CHECKS -> "Перевірка цілісності";
            case SYNCHRONIZING_FILES -> "Синхронізація файлів";
            case OPTIMIZING_DATABASE -> "Оптимізація БД";
            case BACKING_UP -> "Резервне копіювання";
            case RESTORING -> "Відновлення";
            case CREATING_COLLECTION -> "Створення колекції";
            case FINALIZING -> "Завершення";
            case BOOK_DOWNLOAD -> "Завантаження книги";
            case COMPLETED -> "Готово";
            case CANCELLED -> "Скасовано";
            case FAILED -> "Помилка";
        };
    }

    @Override
    public void dispose() {
        AutoCloseable current = registration;
        registration = null;
        if (current != null) {
            try { current.close(); } catch (Exception ignored) { }
        }
    }
}
