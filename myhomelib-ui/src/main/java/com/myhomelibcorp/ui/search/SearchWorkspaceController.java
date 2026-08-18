package com.myhomelibcorp.ui.search;

import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.dto.GenreDto;
import com.myhomelibcorp.application.mapper.AuthorMapper;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.mapper.GenreMapper;
import com.myhomelibcorp.application.search.SearchService;
import com.myhomelibcorp.application.usecase.search.DeleteSavedSearchUseCase;
import com.myhomelibcorp.application.usecase.search.LoadSavedSearchesUseCase;
import com.myhomelibcorp.application.usecase.search.SaveSearchUseCase;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.ui.controller.SavedSearchesController;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.animation.PauseTransition;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class SearchWorkspaceController {

    private final SearchService searchService;
    private final NavigationService navigationService;
    private final AuthorMapper authorMapper;
    private final BookMapper bookMapper;
    private final GenreMapper genreMapper;
    private final ApplicationState appState;
    private final ApplicationContext springContext;
    private final DialogService dialogService;
    private final SaveSearchUseCase saveSearchUseCase;
    private final LoadSavedSearchesUseCase loadSavedSearchesUseCase;
    private final DeleteSavedSearchUseCase deleteSavedSearchUseCase;
    private final UiBackgroundExecutor executor;

    @FXML private TextField searchField;
    @FXML private VBox resultsContainer;
    @FXML private Label statusLabel;

    @FXML private VBox authorsSection;
    @FXML private ListView<AuthorDto> authorsListView;
    @FXML private Label authorsCountLabel;

    @FXML private VBox seriesSection;
    @FXML private ListView<String> seriesListView;
    @FXML private Label seriesCountLabel;

    @FXML private VBox genresSection;
    @FXML private ListView<GenreDto> genresListView;
    @FXML private Label genresCountLabel;

    @FXML private VBox booksSection;
    @FXML private ListView<BookDto> booksListView;
    @FXML private Label booksCountLabel;

    @FXML private Button saveSearchButton;
    @FXML private Button savedSearchesButton;

    private String lastQuery = "";

    private final PauseTransition debounce = new PauseTransition(Duration.millis(300));

    @FXML
    public void initialize() {
        setupListViews();
        setupSearchListener();
        setupButtons();
        searchField.requestFocus();
        setSectionVisible(authorsSection, false);
        setSectionVisible(seriesSection, false);
        setSectionVisible(genresSection, false);
        setSectionVisible(booksSection, false);
    }

    private void setupButtons() {
        saveSearchButton.setOnAction(e -> onSaveSearch());
        savedSearchesButton.setOnAction(e -> onOpenSavedSearches());
    }

    // ==================== НАЛАШТУВАННЯ СПИСКІВ ====================

    private void setupListViews() {
        authorsListView.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(AuthorDto item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getFullName());
            }
        });
        authorsListView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                AuthorDto selected = authorsListView.getSelectionModel().getSelectedItem();
                if (selected != null) navigationService.navigateToAuthor(AuthorId.fromString(selected.getId()));
            }
        });

        seriesListView.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
            }
        });
        seriesListView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                String selected = seriesListView.getSelectionModel().getSelectedItem();
                if (selected != null) navigationService.navigateToSeriesByName(selected);
            }
        });

        genresListView.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(GenreDto item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName());
            }
        });
        genresListView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                GenreDto selected = genresListView.getSelectionModel().getSelectedItem();
                if (selected != null) navigationService.navigateToGenre(GenreId.fromCode(selected.getCode()));
            }
        });

        booksListView.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(BookDto item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getTitle() + " — " + item.getAuthorsText());
            }
        });
        booksListView.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null) appState.getBookDetails().setCurrentBook(selected);
        });
        booksListView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                BookDto selected = booksListView.getSelectionModel().getSelectedItem();
                if (selected != null) navigationService.navigateToBook(BookId.fromString(selected.getId()));
            }
        });
    }

    // ==================== ПОШУК ====================

    private void setupSearchListener() {
        searchField.textProperty().addListener((obs, old, query) -> {
            debounce.stop();
            debounce.setOnFinished(e -> performSearch(query));
            debounce.playFromStart();
        });
    }

    /**
     * ВИПРАВЛЕНО: використовує Executor замість new Thread()
     */
    public void performSearch(String query) {
        this.lastQuery = query;
        if (query == null || query.isBlank()) {
            clearResults();
            statusLabel.setText("Введіть запит для пошуку");
            return;
        }

        statusLabel.setText("Пошук...");

        executor.submit(() -> {
            try {
                Map<String, Object> results = searchService.searchAll(query);
                UiExecutor.runOnUiThread(() -> updateResults(results));
                return null;
            } catch (Exception e) {
                log.error("Search failed", e);
                UiExecutor.runOnUiThread(() -> statusLabel.setText("Помилка пошуку: " + e.getMessage()));
                return null;
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void updateResults(Map<String, Object> results) {
        List<AuthorDto> authors = (List<AuthorDto>) results.get("authors");
        List<String> series = (List<String>) results.get("series");
        List<GenreDto> genres = (List<GenreDto>) results.get("genres");
        List<BookDto> books = (List<BookDto>) results.get("books");

        setSectionVisible(authorsSection, authors != null && !authors.isEmpty());
        if (authors != null && !authors.isEmpty()) {
            authorsListView.getItems().setAll(authors);
            authorsCountLabel.setText("(" + authors.size() + ")");
        }

        setSectionVisible(seriesSection, series != null && !series.isEmpty());
        if (series != null && !series.isEmpty()) {
            seriesListView.getItems().setAll(series);
            seriesCountLabel.setText("(" + series.size() + ")");
        }

        setSectionVisible(genresSection, genres != null && !genres.isEmpty());
        if (genres != null && !genres.isEmpty()) {
            genresListView.getItems().setAll(genres);
            genresCountLabel.setText("(" + genres.size() + ")");
        }

        setSectionVisible(booksSection, books != null && !books.isEmpty());
        if (books != null && !books.isEmpty()) {
            booksListView.getItems().setAll(books);
            booksCountLabel.setText("(" + books.size() + ")");
            booksListView.getSelectionModel().selectFirst();
            statusLabel.setText("Результати пошуку для: \"" + lastQuery + "\"");
        } else {
            statusLabel.setText("Нічого не знайдено");
            appState.getBookDetails().setCurrentBook(null);
        }
    }

    private void setSectionVisible(VBox section, boolean visible) {
        section.setVisible(visible);
        section.setManaged(visible);
    }

    public void clearResults() {
        setSectionVisible(authorsSection, false);
        authorsListView.getItems().clear();
        setSectionVisible(seriesSection, false);
        seriesListView.getItems().clear();
        setSectionVisible(genresSection, false);
        genresListView.getItems().clear();
        setSectionVisible(booksSection, false);
        booksListView.getItems().clear();
        appState.getBookDetails().setCurrentBook(null);
        statusLabel.setText("Введіть запит для пошуку");
    }

    public void setInitialQuery(String query) {
        if (query != null && !query.isBlank()) {
            searchField.setText(query);
            performSearch(query);
        } else {
            clearResults();
        }
    }

    public void setResults(List<BookDto> results) {
        if (results != null && !results.isEmpty()) {
            setSectionVisible(booksSection, true);
            booksListView.getItems().setAll(results);
            booksCountLabel.setText("(" + results.size() + ")");
            setSectionVisible(authorsSection, false);
            setSectionVisible(seriesSection, false);
            setSectionVisible(genresSection, false);
            statusLabel.setText("Знайдено книг: " + results.size());
            booksListView.getSelectionModel().selectFirst();
        } else {
            setSectionVisible(booksSection, false);
            booksListView.getItems().clear();
            appState.getBookDetails().setCurrentBook(null);
            statusLabel.setText("Книги не знайдено");
        }
    }

    // ==================== ЗБЕРЕЖЕНІ ПОШУКИ ====================

    @FXML
    private void onSaveSearch() {
        String query = searchField.getText();
        if (query == null || query.isBlank()) {
            dialogService.showWarning("Увага", "Введіть запит для збереження");
            return;
        }

        String name = dialogService.showTextInput(
                "Зберегти пошук",
                "Введіть назву для пошуку",
                "Назва:",
                query.length() > 30 ? query.substring(0, 30) + "..." : query
        ).orElse(null);

        if (name == null || name.isBlank()) {
            return;
        }

        try {
            saveSearchUseCase.execute(name, query, null);
            dialogService.showInfo("Успішно", "Пошук '" + name + "' збережено");
        } catch (Exception e) {
            log.error("Помилка збереження пошуку", e);
            dialogService.showError("Помилка", "Не вдалося зберегти пошук: " + e.getMessage());
        }
    }

    @FXML
    private void onOpenSavedSearches() {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/view/saved-searches.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Parent root = loader.load();

            SavedSearchesController controller = loader.getController();
            controller.setOnSearchSelected(query -> {
                searchField.setText(query);
                performSearch(query);
            });

            Stage stage = new Stage();
            stage.setTitle("Збережені пошуки");
            stage.setScene(new Scene(root, 450, 500));
            stage.initModality(Modality.WINDOW_MODAL);
            stage.initOwner(searchField.getScene().getWindow());
            stage.show();

        } catch (Exception e) {
            log.error("Помилка відкриття збережених пошуків", e);
            dialogService.showError("Помилка", "Не вдалося відкрити діалог: " + e.getMessage());
        }
    }

    @FXML
    public void onSearch() {
        String q = searchField.getText();
        if (q != null && !q.isBlank()) performSearch(q);
    }

    @FXML
    public void onClear() {
        searchField.clear();
        clearResults();
        searchField.requestFocus();
    }
}