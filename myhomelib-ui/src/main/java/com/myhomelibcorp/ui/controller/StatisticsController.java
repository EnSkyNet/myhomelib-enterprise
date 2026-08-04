package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.dto.LibraryStatistics;
import com.myhomelibcorp.application.statistics.StatisticsService;
import com.myhomelibcorp.ui.util.UiExecutor;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class StatisticsController {

    private final StatisticsService statisticsService;

    @FXML private Label booksCount;
    @FXML private Label authorsCount;
    @FXML private Label seriesCount;
    @FXML private Label genresCount;
    @FXML private Label languagesCount;
    @FXML private Label publishersCount;
    @FXML private Label totalSize;
    @FXML private Label duplicatesCount;
    @FXML private Label missingCoversCount;

    @FXML
    public void initialize() {
        loadStatistics();
    }

    @FXML
    public void onRefresh() {
        loadStatistics();
    }

    @FXML
    public void onClose() {
        Stage stage = (Stage) booksCount.getScene().getWindow();
        if (stage != null) {
            stage.close();
        }
    }

    private void loadStatistics() {
        try {
            LibraryStatistics stats = statisticsService.getStatistics();
            if (stats != null) {
                booksCount.setText(formatNumber(stats.getBooksCount()));
                authorsCount.setText(formatNumber(stats.getAuthorsCount()));
                seriesCount.setText(formatNumber(stats.getSeriesCount()));
                genresCount.setText(formatNumber(stats.getGenresCount()));
                languagesCount.setText(formatNumber(stats.getLanguagesCount()));
                publishersCount.setText(formatNumber(stats.getPublishersCount()));
                totalSize.setText(formatFileSize(stats.getTotalSizeBytes()));
                duplicatesCount.setText(formatNumber(stats.getDuplicatesCount()));
                missingCoversCount.setText(formatNumber(stats.getMissingCoversCount()));
            }
        } catch (Exception e) {
            log.error("Помилка завантаження статистики", e);
        }
    }

    private String formatNumber(long number) {
        return String.format("%,d", number);
    }

    private String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        return String.format("%.1f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }
}