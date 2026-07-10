package com.myhomelibcorp.ui.dashboard;

import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.dto.DashboardData;
import com.myhomelibcorp.application.dashboard.DashboardService;
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
    @FXML private VBox favoriteAuthorsBox;
    @FXML private Label statsLabel;

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
        vm.setFavoriteAuthors(data.getFavoriteAuthors()); // ВИПРАВЛЕНО
        vm.setStatistics(data.getStatistics());

        BookDto continueBook = data.getContinueReading();
        if (continueBook != null) {
            continueReadingBox.setVisible(true);
            continueTitle.setText(continueBook.getTitle());
            continueProgress.setProgress(continueBook.getProgress() / 100.0);
        } else {
            continueReadingBox.setVisible(false);
        }

        recentBooksBox.getChildren().clear();
        data.getRecentBooks().forEach(book -> recentBooksBox.getChildren().add(createBookLabel(book)));

        newBooksBox.getChildren().clear();
        data.getRecentAdded().forEach(book -> newBooksBox.getChildren().add(createBookLabel(book)));

        // Улюблені автори – використовуємо AuthorDto
        favoriteAuthorsBox.getChildren().clear();
        data.getFavoriteAuthors().forEach(author -> {
            Label label = new Label("✔ " + author.getFullName()); // ВИПРАВЛЕНО
            label.setStyle("-fx-padding: 2 0 2 10;");
            favoriteAuthorsBox.getChildren().add(label);
        });

        var stats = data.getStatistics();
        if (stats != null) {
            statsLabel.setText(String.format("Книг: %d | Авторів: %d | Серій: %d",
                    stats.getBooksCount(), stats.getAuthorsCount(), stats.getSeriesCount()));
        }
    }

    private Label createBookLabel(BookDto book) {
        Label label = new Label("📕 " + book.getTitle());
        label.setStyle("-fx-padding: 2 0 2 10;");
        label.setOnMouseClicked(e -> navigationService.navigateToBook(book.getId()));
        return label;
    }

    @FXML
    private void onContinueReading() {
        BookDto book = appState.getDashboard().getContinueReading();
        if (book != null) {
            navigationService.navigateToBook(book.getId());
        }
    }
}