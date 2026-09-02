package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.statistics.StatisticsService;
import com.myhomelibcorp.ui.util.UiExecutor;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
@Slf4j
public class StatisticsController {
    private final StatisticsService statisticsService;

    @FXML private Label booksCount, authorsCount, seriesCount, genresCount, languagesCount, publishersCount;
    @FXML private Label totalSize, duplicatesCount, missingCoversCount;
    @FXML private Label localBooksCount, remoteBooksCount, readBooksCount, unreadBooksCount, favoritesCount, deletedBooksCount, sourcesCount;

    @FXML public void initialize() { loadStatistics(false); }
    @FXML public void onRefresh() { loadStatistics(true); }
    @FXML public void onClose() {
        Stage stage = (Stage) booksCount.getScene().getWindow();
        if (stage != null) stage.close();
    }

    private void loadStatistics(boolean refresh) {
        setState("Завантаження…");
        CompletableFuture.supplyAsync(() -> {
            if (refresh) statisticsService.refreshStatistics();
            return statisticsService.getStatistics();
        }).whenComplete((stats, error) -> UiExecutor.runOnUiThread(() -> {
            if (error != null || stats == null) {
                log.error("Помилка завантаження статистики", error);
                setState("Недоступно");
                return;
            }
            if (stats.isStale() && !refresh) {
                setState("Оновлення…");
                loadStatistics(true);
                return;
            }
            if (stats.isStale()) {
                setState("Застаріла");
                return;
            }
            booksCount.setText(formatNumber(stats.getBooksCount()));
            authorsCount.setText(formatNumber(stats.getAuthorsCount()));
            seriesCount.setText(formatNumber(stats.getSeriesCount()));
            genresCount.setText(formatNumber(stats.getGenresCount()));
            languagesCount.setText(formatNumber(stats.getLanguagesCount()));
            publishersCount.setText(formatNumber(stats.getPublishersCount()));
            totalSize.setText(formatFileSize(stats.getTotalSizeBytes()));
            duplicatesCount.setText(formatNumber(stats.getDuplicatesCount()));
            missingCoversCount.setText(formatNumber(stats.getMissingCoversCount()));
            localBooksCount.setText(formatNumber(stats.getLocalBooksCount()));
            remoteBooksCount.setText(formatNumber(stats.getRemoteBooksCount()));
            readBooksCount.setText(formatNumber(stats.getReadBooksCount()));
            unreadBooksCount.setText(formatNumber(stats.getUnreadBooksCount()));
            favoritesCount.setText(formatNumber(stats.getFavoritesCount()));
            deletedBooksCount.setText(formatNumber(stats.getDeletedBooksCount()));
            sourcesCount.setText(formatNumber(stats.getSourcesCount()));
        }));
    }

    private void setState(String state) {
        for (Label label : List.of(booksCount, authorsCount, seriesCount, genresCount, languagesCount,
                publishersCount, totalSize, duplicatesCount, missingCoversCount, localBooksCount, remoteBooksCount,
                readBooksCount, unreadBooksCount, favoritesCount, deletedBooksCount, sourcesCount)) {
            if (label != null) label.setText(state);
        }
    }

    private String formatNumber(long number) { return String.format("%,d", number); }
    private String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB", "TB", "PB"};
        int group = Math.min(units.length - 1, (int) (Math.log(bytes) / Math.log(1024)));
        return String.format("%.1f %s", bytes / Math.pow(1024, group), units[group]);
    }
}
