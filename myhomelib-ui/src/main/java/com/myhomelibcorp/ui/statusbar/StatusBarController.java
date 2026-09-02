package com.myhomelibcorp.ui.statusbar;

import com.myhomelibcorp.application.dto.LibraryStatistics;
import com.myhomelibcorp.application.statistics.StatisticsService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.navigation.WorkspaceManager;
import com.myhomelibcorp.ui.operation.OperationCenterEntry;
import com.myhomelibcorp.ui.operation.OperationCenterService;
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
import java.util.Objects;

@Component
@RequiredArgsConstructor
@Slf4j
public class StatusBarController {

    private final ApplicationState appState;
    private final StatisticsService statisticsService;
    private final UiBackgroundExecutor executor;
    private final OperationCenterService operationCenter;
    private final WorkspaceManager workspaceManager;

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
        operationCenter.addListener(snapshot -> UiExecutor.runOnUiThread(() -> updateOperationsLabel(snapshot)));

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
                    ? String.format("Статистика: оновлюється… | RAM: %d MB", usedMB)
                    : String.format("Книг: %d | Авторів: %d | Серій: %d | RAM: %d MB",
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
        operationsLabel.setText(active > 0 ? "Операції: " + active + " актив." : "Операції: " + total);
    }

}