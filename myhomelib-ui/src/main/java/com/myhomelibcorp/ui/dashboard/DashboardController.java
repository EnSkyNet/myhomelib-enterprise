package com.myhomelibcorp.ui.dashboard;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.dto.DashboardData;
import com.myhomelibcorp.application.dashboard.DashboardService;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.DashboardViewModel;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DashboardController {

    private final DashboardService dashboardService;
    private final ApplicationState appState;
    private final NavigationService navigationService;

    @FXML private VBox continueReadingBox;
    @FXML private Label continueTitle;
    @FXML private ProgressBar continueProgress;
    @FXML private VBox recentBooksBox;
    @FXML private VBox newBooksBox;
    @FXML private Label booksCount;
    @FXML private Label authorsCount;
    @FXML private Label seriesCount;
    @FXML private Label genresCount;

    @FXML
    public void initialize() {
        loadDashboard();
    }

    private void loadDashboard() {
        dashboardService.loadDashboardData()
                .thenAccept(data -> UiExecutor.runOnUiThread(() -> updateUI(data)))
                .exceptionally(ex -> {
                    log.error("Failed to load dashboard", ex);
                    return null;
                });
    }

    private void updateUI(DashboardData data) {
        DashboardViewModel vm = appState.getDashboard();
        vm.setContinueReading(data.getContinueReading());
        vm.setRecentBooks(data.getRecentBooks());
        vm.setNewBooks(data.getRecentAdded());
        vm.setStatistics(data.getStatistics());

        // Продовжити читання
        BookDto continueBook = data.getContinueReading();
        if (continueBook != null) {
            continueReadingBox.setVisible(true);
            continueTitle.setText(continueBook.getTitle());
            continueProgress.setProgress(continueBook.getProgress() / 100.0);
        } else {
            continueReadingBox.setVisible(false);
        }

        // Останні книги
        recentBooksBox.getChildren().clear();
        data.getRecentBooks().forEach(book -> recentBooksBox.getChildren().add(createBookLabel(book)));

        // Нові книги
        newBooksBox.getChildren().clear();
        data.getRecentAdded().forEach(book -> newBooksBox.getChildren().add(createBookLabel(book)));

        // Статистика
        var stats = data.getStatistics();
        if (stats != null) {
            booksCount.setText(String.valueOf(stats.getBooksCount()));
            authorsCount.setText(String.valueOf(stats.getAuthorsCount()));
            seriesCount.setText(String.valueOf(stats.getSeriesCount()));
            genresCount.setText(String.valueOf(stats.getGenresCount()));
        }
    }

    private Label createBookLabel(BookDto book) {
        Label label = new Label("📕 " + book.getTitle());
        label.setStyle("-fx-padding: 3 0 3 10; -fx-cursor: hand;");
        label.setOnMouseClicked(e -> navigationService.navigateToBook(BookId.fromString(book.getId())));
        return label;
    }

    @FXML
    private void onContinueReading() {
        BookDto book = appState.getDashboard().getContinueReading();
        if (book != null) {
            navigationService.navigateToBook(BookId.fromString(book.getId()));
        }
    }
}