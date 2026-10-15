package com.myhomelibcorp.ui.controller;


import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.application.statistics.StatisticsService;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.ui.util.UiAsyncRequestGuard;
import com.myhomelibcorp.ui.util.UiAsyncRequestToken;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.navigation.WorkspaceLifecycle;
import com.myhomelibcorp.ui.util.UiSubscriptions;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CancellationException;

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@RequiredArgsConstructor
@Slf4j
public class StatisticsController implements WorkspaceLifecycle {
    private final StatisticsService statisticsService;
    private final ApplicationState appState;
    private final UiBackgroundExecutor backgroundExecutor;
    private final AtomicLong loadGeneration = new AtomicLong();
    private final AtomicBoolean refreshPending = new AtomicBoolean();
    private final UiSubscriptions subscriptions = new UiSubscriptions();

    @FXML private Label booksCount, authorsCount, seriesCount, genresCount, languagesCount, publishersCount;
    @FXML private Label totalSize, duplicatesCount, missingCoversCount;
    @FXML private Label localBooksCount, remoteBooksCount, readBooksCount, unreadBooksCount, favoritesCount, deletedBooksCount, sourcesCount;

    @FXML public void initialize() {
        subscriptions.listen(appState.currentLibraryCollectionProperty(), (obs, oldCollection, newCollection) -> {
            String oldId = oldCollection == null ? null : oldCollection.getId();
            String newId = newCollection == null ? null : newCollection.getId();
            if (!Objects.equals(oldId, newId)) loadStatistics(false);
        });
        loadStatistics(false);
    }
    @FXML public void onRefresh() { loadStatistics(true); }
    @FXML public void onClose() {
        Stage stage = (Stage) booksCount.getScene().getWindow();
        if (stage != null) stage.close();
    }

    private void loadStatistics(boolean refresh) {
        // A queued refresh must be coalesced; repeated clicks must not start concurrent full
        // SQLite counts while a long INDEX rebuild holds the collection barrier.
        if (refresh && !refreshPending.compareAndSet(false, true)) return;
        UiAsyncRequestToken requestToken = UiAsyncRequestGuard.next(loadGeneration, appState);
        String collectionId = appState.getCurrentLibraryCollection() == null ? null
                : appState.getCurrentLibraryCollection().getId();
        setState(UiText.text("ui.fxml.book_workspace.loading"));
        java.util.concurrent.CompletableFuture<com.myhomelibcorp.application.dto.LibraryStatistics> future = refresh
                ? backgroundExecutor.submitCollectionScopedAwait(LibraryOperationType.EDIT, () -> {
                    String currentId = appState.getCurrentLibraryCollection() == null ? null
                            : appState.getCurrentLibraryCollection().getId();
                    if (!Objects.equals(collectionId, currentId)) {
                        throw new CancellationException("Collection changed before statistics refresh");
                    }
                    statisticsService.refreshStatistics();
                    return statisticsService.getStatistics();
                })
                : backgroundExecutor.submitCollectionScoped(LibraryOperationType.QUERY,
                        statisticsService::getStatistics);
        future.whenComplete((stats, error) -> {
            if (refresh) refreshPending.set(false);
            UiExecutor.runOnUiThread(() -> {
            if (!UiAsyncRequestGuard.isCurrent(requestToken, loadGeneration, appState)) return;
            if (error != null || stats == null) {
                log.error("Помилка завантаження статистики", error);
                setState(UiText.text("ui.java.statistics_controller.949ac1f255"));
                return;
            }
            if (stats.isStale() && !refresh) {
                setState(UiText.text("ui.java.statistics_controller.693d8c7846"));
                loadStatistics(true);
                return;
            }
            if (stats.isStale()) {
                setState(UiText.text("ui.java.statistics_controller.631035dcc4"));
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
            });
        });
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
    @Override
    public void dispose() {
        UiAsyncRequestGuard.invalidate(loadGeneration);
        subscriptions.close();
    }

}
