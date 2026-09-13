package com.myhomelibcorp.ui.dashboard;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.dto.ContinueReadingItemDto;
import com.myhomelibcorp.application.dto.DashboardData;
import com.myhomelibcorp.application.usecase.dashboard.LoadDashboardDataUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.navigation.WorkspaceLifecycle;
import com.myhomelibcorp.ui.util.UiAsyncRequestGuard;
import com.myhomelibcorp.ui.util.UiAsyncRequestToken;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.util.UiSubscriptions;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@RequiredArgsConstructor
@Slf4j
public class DashboardController implements WorkspaceLifecycle {

    private final LoadDashboardDataUseCase loadDashboardDataUseCase;
    private final ApplicationState appState;
    private final NavigationService navigationService;
    private final AtomicLong loadGeneration = new AtomicLong();
    private final UiSubscriptions subscriptions = new UiSubscriptions();

    @FXML private VBox continueReadingBox;
    @FXML private VBox continueReadingItemsBox;
    @FXML private VBox recentBooksBox;
    @FXML private VBox newBooksBox;
    @FXML private Label booksCount;
    @FXML private Label authorsCount;
    @FXML private Label seriesCount;
    @FXML private Label genresCount;

    @FXML
    public void initialize() {
        subscriptions.listen(appState.getDashboard().statisticsProperty(),
                (obs, oldStats, newStats) -> renderStatistics(newStats));
        subscriptions.listen(appState.currentLibraryCollectionProperty(), (obs, oldCollection, newCollection) -> {
            String oldId = oldCollection == null ? null : oldCollection.getId();
            String newId = newCollection == null ? null : newCollection.getId();
            if (!Objects.equals(oldId, newId)) loadDashboard();
        });
        loadDashboard();
    }

    private void loadDashboard() {
        UiAsyncRequestToken requestToken = UiAsyncRequestGuard.next(loadGeneration, appState);
        loadDashboardDataUseCase.execute()
                .thenAccept(data -> UiExecutor.runOnUiThread(() -> {
                    if (UiAsyncRequestGuard.isCurrent(requestToken, loadGeneration, appState)) updateUI(data);
                }))
                .exceptionally(ex -> {
                    if (UiAsyncRequestGuard.isCurrent(requestToken, loadGeneration, appState)) {
                        log.error("Failed to load dashboard", ex);
                    }
                    return null;
                });
    }

    private void updateUI(DashboardData data) {
        var vm = appState.getDashboard();
        vm.setContinueReading(data.getContinueReading());
        vm.setRecentBooks(data.getRecentBooks());
        vm.setNewBooks(data.getRecentAdded());
        vm.setStatistics(data.getStatistics());

        var shelf = data.getContinueReadingShelf() == null ? java.util.List.<ContinueReadingItemDto>of() : data.getContinueReadingShelf();
        continueReadingItemsBox.getChildren().clear();
        if (!shelf.isEmpty()) {
            continueReadingBox.setVisible(true);
            shelf.forEach(item -> continueReadingItemsBox.getChildren().add(createContinueReadingRow(item)));
        } else {
            continueReadingBox.setVisible(false);
        }

        recentBooksBox.getChildren().clear();
        data.getRecentBooks().forEach(book -> recentBooksBox.getChildren().add(createBookLabel(book)));

        newBooksBox.getChildren().clear();
        data.getRecentAdded().forEach(book -> newBooksBox.getChildren().add(createBookLabel(book)));

        renderStatistics(data.getStatistics());
    }

    private void renderStatistics(com.myhomelibcorp.application.dto.LibraryStatistics stats) {
        if (stats == null) return;
        if (stats.isStale()) {
            booksCount.setText("Оновлюється…");
            authorsCount.setText("Оновлюється…");
            seriesCount.setText("Оновлюється…");
            genresCount.setText("Оновлюється…");
            return;
        }
        booksCount.setText(String.valueOf(stats.getBooksCount()));
        authorsCount.setText(String.valueOf(stats.getAuthorsCount()));
        seriesCount.setText(String.valueOf(stats.getSeriesCount()));
        genresCount.setText(String.valueOf(stats.getGenresCount()));
    }

    private Label createBookLabel(BookDto book) {
        Label label = new Label("📕 " + book.getTitle());
        label.setStyle("-fx-padding: 3 0 3 10; -fx-cursor: hand;");
        label.setOnMouseClicked(e -> navigationService.navigateToBook(BookId.fromString(book.getId())));
        return label;
    }

    private VBox createContinueReadingRow(ContinueReadingItemDto item) {
        Label title = new Label("📖 " + item.title());
        title.setWrapText(true);
        Label meta = new Label(String.format(java.util.Locale.ROOT, "%.1f%% · %s · %s",
                item.percent(), item.lastDevice(), formatTime(item.updatedAt())));
        meta.setStyle("-fx-opacity: 0.75;");
        ProgressBar progress = new ProgressBar(item.percent() / 100.0);
        progress.setMaxWidth(Double.MAX_VALUE);
        Button open = new Button("Читати");
        open.setOnAction(e -> navigationService.navigateToBook(BookId.fromString(item.bookId())));
        HBox header = new HBox(10, title, open);
        HBox.setHgrow(title, javafx.scene.layout.Priority.ALWAYS);
        VBox row = new VBox(4, header, meta, progress);
        row.setStyle("-fx-padding: 6 0 6 0;");
        return row;
    }

    private static String formatTime(java.time.LocalDateTime value) {
        if (value == null) return "—";
        return value.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"));
    }
    @Override
    public void dispose() {
        UiAsyncRequestGuard.invalidate(loadGeneration);
        subscriptions.close();
    }

}