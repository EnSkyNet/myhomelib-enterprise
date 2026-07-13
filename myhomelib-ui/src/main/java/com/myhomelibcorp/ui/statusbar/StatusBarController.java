package com.myhomelibcorp.ui.statusbar;

import com.myhomelibcorp.application.dto.LibraryStatistics;
import com.myhomelibcorp.application.statistics.StatisticsService;
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

@Component
@RequiredArgsConstructor
@Slf4j
public class StatusBarController {

    private final ApplicationState appState;
    private final StatisticsService statisticsService;

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

        // Початкове завантаження
        statisticsService.getStatistics();
        updateStatsLabel(statisticsService.getStatistics());
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