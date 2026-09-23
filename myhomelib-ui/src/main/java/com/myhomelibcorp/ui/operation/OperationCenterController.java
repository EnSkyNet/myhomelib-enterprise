package com.myhomelibcorp.ui.operation;


import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.application.progress.OperationStage;
import com.myhomelibcorp.ui.navigation.WorkspaceLifecycle;
import com.myhomelibcorp.ui.util.UiExecutor;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
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
    private Timeline durationTicker;

    @FXML private Label summaryLabel;
    @FXML private TableView<OperationCenterEntry> operationsTable;
    @FXML private TableColumn<OperationCenterEntry, String> stateColumn;
    @FXML private TableColumn<OperationCenterEntry, String> titleColumn;
    @FXML private TableColumn<OperationCenterEntry, String> kindColumn;
    @FXML private TableColumn<OperationCenterEntry, String> stageColumn;
    @FXML private TableColumn<OperationCenterEntry, String> progressColumn;
    @FXML private TableColumn<OperationCenterEntry, String> startedColumn;
    @FXML private TableColumn<OperationCenterEntry, String> finishedColumn;
    @FXML private TableColumn<OperationCenterEntry, String> durationColumn;
    @FXML private TableColumn<OperationCenterEntry, String> resultColumn;
    @FXML private TextArea detailsArea;

    @FXML
    public void initialize() {
        stateColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(stateText(cell.getValue())));
        titleColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().title()));
        kindColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(kindText(cell.getValue().kind())));
        stageColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(stageText(cell.getValue().stage())));
        progressColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(progressText(cell.getValue())));
        startedColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(TIME_FORMAT.format(cell.getValue().startedAt())));
        finishedColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(finishedText(cell.getValue())));
        durationColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(durationText(cell.getValue().duration(Instant.now()))));
        resultColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(resultText(cell.getValue())));
        operationsTable.setItems(rows);
        operationsTable.setRowFactory(table -> {
            TableRow<OperationCenterEntry> row = new TableRow<>();
            row.getStyleClass().add("operation-center-row");
            return row;
        });
        operationsTable.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, selected) -> showDetails(selected));

        registration = operationCenter.addListener(snapshot -> UiExecutor.runOnUiThread(() -> applySnapshot(snapshot)));
        durationTicker = new Timeline(new KeyFrame(javafx.util.Duration.seconds(1), event -> {
            if (rows.stream().anyMatch(OperationCenterEntry::active)) {
                operationsTable.refresh();
                showDetails(operationsTable.getSelectionModel().getSelectedItem());
            }
        }));
        durationTicker.setCycleCount(Timeline.INDEFINITE);
        durationTicker.play();
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
                ? UiText.text("ui.java.operation_center_controller.62e882cef6") + rows.size()
                : UiText.text("ui.java.operation_center_controller.45335a5794") + active + UiText.text("ui.java.operation_center_controller.177ca47f04") + rows.size());

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
        text.append(UiText.text("ui.java.operation_center_controller.384f693f20")).append(stateText(entry)).append('\n');
        text.append(UiText.text("ui.java.operation_center_controller.9a32dc9dce")).append(kindText(entry.kind())).append('\n');
        text.append(UiText.text("ui.java.operation_center_controller.16299a6f6e")).append(stageText(entry.stage())).append('\n');
        text.append(UiText.text("ui.java.operation_center_controller.88044a908f")).append(progressText(entry)).append('\n');
        text.append(UiText.text("ui.java.operation_center_controller.dcf55c7dd2")).append(TIME_FORMAT.format(entry.startedAt())).append('\n');
        text.append(UiText.text("ui.java.operation_center_controller.d093002eea")).append(finishedText(entry)).append('\n');
        text.append(UiText.text("ui.java.operation_center_controller.7ad64391a4")).append(durationText(entry.duration(Instant.now()))).append('\n');
        text.append(UiText.text("ui.java.operation_center_controller.fb174e7936")).append(resultText(entry)).append('\n');
        if (!entry.collectionId().isBlank()) text.append(UiText.text("ui.java.operation_center_controller.a8897fc87d")).append(entry.collectionId()).append('\n');
        if (entry.inserted() != 0 || entry.updated() != 0 || entry.deleted() != 0) {
            text.append(UiText.text("ui.java.operation_center_controller.4eb7a3577f")).append(entry.inserted())
                    .append(UiText.text("ui.java.operation_center_controller.39b11d0f55")).append(entry.updated())
                    .append(UiText.text("ui.java.operation_center_controller.d11d582dcd")).append(entry.deleted()).append('\n');
        }
        if (entry.skipped() != 0 || entry.duplicates() != 0 || entry.warnings() != 0 || entry.errors() != 0) {
            text.append(UiText.text("ui.java.operation_center_controller.9353edc31a")).append(entry.skipped())
                    .append(UiText.text("ui.java.operation_center_controller.d7fab22b55")).append(entry.duplicates())
                    .append(UiText.text("ui.java.operation_center_controller.dae00d4c2f")).append(entry.warnings())
                    .append(UiText.text("ui.java.operation_center_controller.37eb1b14b0")).append(entry.errors()).append('\n');
        }
        if (!entry.currentItem().isBlank()) text.append(UiText.text("ui.java.operation_center_controller.ac657d3875")).append(entry.currentItem()).append('\n');
        if (!entry.errorMessage().isBlank()) text.append(UiText.text("ui.java.operation_center_controller.eb7ae2e067")).append(entry.errorMessage()).append('\n');
        text.append(UiText.text("ui.java.operation_center_controller.3163064c5d")).append(entry.operationId());
        detailsArea.setText(text.toString());
    }

    private static String finishedText(OperationCenterEntry entry) {
        if (entry == null || entry.finishedAt() == null) return "—";
        return TIME_FORMAT.format(entry.finishedAt());
    }

    private static String resultText(OperationCenterEntry entry) {
        if (entry == null) return "";
        if (!entry.errorMessage().isBlank()) return entry.errorMessage();
        if (!entry.currentItem().isBlank() && !entry.active()) return entry.currentItem();
        return switch (entry.stage()) {
            case COMPLETED -> UiText.text("common.success");
            case CANCELLED -> UiText.text("ui.import.stage.cancelled");
            case FAILED -> UiText.text("common.error");
            default -> entry.currentItem().isBlank() ? UiText.text("ui.java.operation_center_controller.af4dd92893") : entry.currentItem();
        };
    }

    private static String stateText(OperationCenterEntry entry) {
        if (entry == null) return "";
        return switch (entry.stage()) {
            case COMPLETED -> UiText.text("ui.java.operation_center_controller.1e5232f94c");
            case CANCELLED -> UiText.text("ui.java.operation_center_controller.8000fc2623");
            case FAILED -> UiText.text("ui.java.operation_center_controller.d189aeea0d");
            default -> UiText.text("ui.java.operation_center_controller.cd0bf2e403");
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
            case CHECKING_SERVER -> UiText.text("ui.import.stage.checking_server");
            case DOWNLOADING -> UiText.text("ui.import.stage.downloading");
            case CREATING_CHECKPOINT -> UiText.text("ui.import.stage.creating_checkpoint");
            case VALIDATING -> UiText.text("ui.java.operation_center_controller.d5cb0c3a91");
            case READING_CATALOG -> UiText.text("ui.import.stage.reading_catalog");
            case IMPORTING -> UiText.text("ui.import.action.generic");
            case UPDATING_AUTHORS -> UiText.text("ui.import.stage.updating_authors");
            case APPLYING_DELETIONS -> UiText.text("ui.import.stage.applying_deletions");
            case UPDATING_SEARCH_INDEX -> UiText.text("ui.import.stage.updating_index");
            case ROLLING_BACK -> UiText.text("ui.java.operation_center_controller.d0f715afcd");
            case REFRESHING_STATISTICS -> UiText.text("ui.java.operation_center_controller.7c96a35ead");
            case INTEGRITY_CHECKS -> UiText.text("ui.import.stage.integrity_checks");
            case SCANNING_DUPLICATES -> UiText.text("ui.import.stage.scanning_duplicates");
            case SYNCHRONIZING_FILES -> UiText.text("ui.import.stage.synchronizing_files");
            case OPTIMIZING_DATABASE -> UiText.text("ui.java.operation_center_controller.bd69af2a4d");
            case BACKING_UP -> UiText.text("ui.backup.confirm.start.title");
            case RESTORING -> UiText.text("ui.import.stage.restoring");
            case CREATING_COLLECTION -> UiText.text("ui.import.stage.creating_collection");
            case DELETING_COLLECTION -> UiText.text("ui.import.stage.deleting_collection");
            case FINALIZING -> UiText.text("ui.fxml.operation_center_workspace.finalizing");
            case BOOK_DOWNLOAD -> UiText.text("ui.import.stage.book_download");
            case CONVERTING -> UiText.text("ui.import.stage.converting");
            case COMPLETED -> UiText.text("ui.java.operation_center_controller.398c7d4f7b");
            case CANCELLED -> UiText.text("ui.import.stage.cancelled");
            case FAILED -> UiText.text("common.error");
        };
    }

    @Override
    public void dispose() {
        if (durationTicker != null) {
            durationTicker.stop();
            durationTicker = null;
        }
        AutoCloseable current = registration;
        registration = null;
        if (current != null) {
            try { current.close(); } catch (Exception ignored) { }
        }
    }
    private static String kindText(OperationKind kind) {
        if (kind == null) return UiText.text("ui.java.operation_center_controller.390dc352b4");
        return switch (kind) {
            case COLLECTION_CREATE -> UiText.text("ui.import.stage.creating_collection");
            case COLLECTION_DELETE -> UiText.text("ui.import.stage.deleting_collection");
            case CATALOG_IMPORT -> UiText.text("ui.java.operation_center_controller.282c34fc52");
            case CATALOG_UPDATE -> UiText.text("ui.fxml.updates_workspace.catalog_updates");
            case INDEX_REBUILD -> UiText.text("ui.fxml.integrity_check.search_index");
            case BACKUP -> UiText.text("ui.backup.operation.title");
            case RESTORE -> UiText.text("ui.import.stage.restoring");
            case INTEGRITY_CHECK -> UiText.text("ui.import.stage.integrity_checks");
            case MAINTENANCE -> UiText.text("ui.java.operation_center_controller.ac5b03f144");
            case BOOK_DOWNLOAD -> UiText.text("ui.import.stage.book_download");
            case BOOK_CONVERSION -> UiText.text("ui.import.stage.converting");
            case GENERIC -> UiText.text("ui.java.operation_center_controller.390dc352b4");
        };
    }

}
