package com.myhomelibcorp.ui.statusbar;

import com.myhomelibcorp.application.dto.LibraryStatistics;
import com.myhomelibcorp.application.statistics.StatisticsService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
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

    @FXML private Label statusLabel;
    @FXML private Label statsLabel;
    @FXML private ProgressBar progressBar;

    @FXML
    public void initialize() {
        StatusBarViewModel vm = appState.getStatusBar();
        statusLabel.textProperty().bind(vm.statusTextProperty());
        progressBar.progressProperty().bind(vm.progressProperty());
        progressBar.visibleProperty().bind(vm.progressVisibleProperty());

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
            String text = String.format("Книг: %d | Авторів: %d | Серій: %d | Lucene ✓ | SQLite ✓ | RAM: %d MB",
                    stats.getBooksCount(),
                    stats.getAuthorsCount(),
                    stats.getSeriesCount(),
                    usedMB);
            statsLabel.setText(text);
        });
    }
}