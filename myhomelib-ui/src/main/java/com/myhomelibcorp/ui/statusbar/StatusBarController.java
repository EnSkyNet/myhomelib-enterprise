package com.myhomelibcorp.ui.statusbar;

import com.myhomelibcorp.application.dto.LibraryStatistics;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.statistics.StatisticsService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.navigation.WorkspaceManager;
import com.myhomelibcorp.ui.operation.OperationCenterEntry;
import com.myhomelibcorp.ui.operation.OperationCenterService;
import com.myhomelibcorp.ui.operation.LibraryOperationUiText;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.StatusBarViewModel;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;
import java.util.Objects;

@Component
@RequiredArgsConstructor
@Slf4j
public class StatusBarController {

    private final ApplicationState appState;
    private final StatisticsService statisticsService;
    private final UiBackgroundExecutor executor;
    private final OperationCenterService operationCenter;
    private final LibraryOperationCoordinator libraryOperations;
    private final WorkspaceManager workspaceManager;

    private volatile List<OperationCenterEntry> operationSnapshot = List.of();
    private volatile LibraryOperationType coordinatedOperation;

    @FXML private Label statusLabel;
    @FXML private Label operationsLabel;
    @FXML private Label statsLabel;
    @FXML private ProgressBar progressBar;

    @FXML
    public void initialize() {
        StatusBarViewModel vm = appState.getStatusBar();
        statusLabel.textProperty().bind(vm.statusTextProperty());
        progressBar.progressProperty().bind(vm.progressProperty());
        progressBar.visibleProperty().bind(vm.progressVisibleProperty());
        progressBar.managedProperty().bind(vm.progressVisibleProperty());
        operationsLabel.setTooltip(new javafx.scene.control.Tooltip(
                "Відкрити журнал поточних і завершених фонових операцій"));
        operationCenter.addListener(snapshot -> UiExecutor.runOnUiThread(() -> {
            operationSnapshot = snapshot == null ? List.of() : snapshot;
            updateOperationsLabel(operationSnapshot);
            refreshBackgroundStatus(vm);
        }));
        libraryOperations.addListener(operation -> UiExecutor.runOnUiThread(() -> {
            coordinatedOperation = operation;
            refreshBackgroundStatus(vm);
        }));

        // Оновлення статистики
        vm.statisticsProperty().addListener((obs, old, stats) -> {
            if (stats != null) {
                updateStatsLabel(stats);
            }
        });

        // Even the O(1) cache read can briefly wait on SQLITE_BUSY while another
        // startup operation owns the DB. Never perform that retry path on FX.
        String collectionId = currentCollectionId();
        executor.submit(() -> statisticsService.getStatistics())
                .thenAccept(stats -> UiExecutor.runOnUiThread(() -> {
                    if (!Objects.equals(collectionId, currentCollectionId())) return;
                    vm.setStatistics(stats);
                    updateStatsLabel(stats);
                }))
                .exceptionally(error -> {
                    log.warn("Не вдалося прочитати кеш статистики для status bar", error);
                    return null;
                });
    }

    private String currentCollectionId() {
        var collection = appState.getCurrentLibraryCollection();
        return collection == null ? null : collection.getId();
    }

    private void updateStatsLabel(LibraryStatistics stats) {
        if (stats == null) return;
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        long usedMB = heap.getUsed() / (1024 * 1024);

        UiExecutor.runOnUiThread(() -> {
            String text = stats.isStale()
                    ? String.format("Статистика: оновлюється… | ОЗП: %d МБ", usedMB)
                    : String.format("Книг: %d | Авторів: %d | Серій: %d | ОЗП: %d МБ",
                    stats.getBooksCount(),
                    stats.getAuthorsCount(),
                    stats.getSeriesCount(),
                    usedMB);
            statsLabel.setText(text);
        });
    }
    @FXML
    private void onOperationsClick() {
        workspaceManager.showOperationCenterWorkspace();
    }

    private void updateOperationsLabel(java.util.List<OperationCenterEntry> snapshot) {
        int active = 0;
        if (snapshot != null) for (OperationCenterEntry entry : snapshot) if (entry.active()) active++;
        int total = snapshot == null ? 0 : snapshot.size();
        int completed = Math.max(0, total - active);
        operationsLabel.setText(active > 0
                ? "Операції: " + active + " актив. · " + completed + " заверш."
                : "Операції: " + completed + " заверш.");
    }

    private void refreshBackgroundStatus(StatusBarViewModel vm) {
        OperationCenterEntry entry = selectVisibleOperation(operationSnapshot, coordinatedOperation);
        if (entry != null) {
            double fraction = entry.fraction();
            vm.setBackgroundOperation(LibraryOperationUiText.entryStatus(entry),
                    fraction >= 0.0 ? fraction : -1.0, true);
            return;
        }
        if (coordinatedOperation != null) {
            vm.setBackgroundOperation(LibraryOperationUiText.activeStatus(coordinatedOperation), -1.0, true);
            return;
        }
        vm.clearBackgroundOperation();
    }

    static OperationCenterEntry selectVisibleOperation(List<OperationCenterEntry> snapshot,
                                                       LibraryOperationType coordinatedOperation) {
        if (snapshot == null || snapshot.isEmpty()) return null;
        if (coordinatedOperation != null) {
            for (OperationCenterEntry entry : snapshot) {
                if (LibraryOperationUiText.matches(coordinatedOperation, entry)) return entry;
            }
            return null;
        }
        for (OperationCenterEntry entry : snapshot) {
            if (entry.active()) return entry;
        }
        return null;
    }

}