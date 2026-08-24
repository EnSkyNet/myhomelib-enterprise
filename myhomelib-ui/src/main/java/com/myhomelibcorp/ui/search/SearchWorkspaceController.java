package com.myhomelibcorp.ui.search;

import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.dto.GenreDto;
import com.myhomelibcorp.application.mapper.AuthorMapper;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.mapper.GenreMapper;
import com.myhomelibcorp.application.search.SearchService;
import com.myhomelibcorp.application.query.search.SearchRequest;
import com.myhomelibcorp.application.query.search.SearchMode;
import com.myhomelibcorp.application.usecase.search.DeleteSavedSearchUseCase;
import com.myhomelibcorp.application.usecase.search.LoadSavedSearchesUseCase;
import com.myhomelibcorp.application.usecase.search.SaveSearchUseCase;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.LanguageCode;
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
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
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

    @FXML private TextField titleFilter;
    @FXML private TextField authorFilter;
    @FXML private TextField seriesFilter;
    @FXML private TextField genreFilter;
    @FXML private TextField keywordFilter;
    @FXML private TextField annotationFilter;
    @FXML private TextField fileFilter;
    @FXML private TextField languageFilter;
    @FXML private TextField ratingFromFilter;
    @FXML private TextField ratingToFilter;
    @FXML private TextField yearFromFilter;
    @FXML private TextField yearToFilter;
    @FXML private DatePicker addedFromPicker;
    @FXML private DatePicker addedToPicker;
    @FXML private CheckBox localOnlyCheck;

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
        this.lastQuery = query == null ? "" : query;
        if ((query == null || query.isBlank()) && !hasAdvancedFilters()) {
            clearResults();
            statusLabel.setText("Введіть запит або задайте фільтри");
            return;
        }

        statusLabel.setText("Пошук...");
        if (!hasAdvancedFilters()) {
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
            return;
        }

        SearchRequest request = buildAdvancedRequest(query);
        executor.submit(() -> {
            try {
                List<BookDto> books = searchService.search(request);
                UiExecutor.runOnUiThread(() -> {
                    setResults(books);
                    statusLabel.setText("Розширений пошук: знайдено " + books.size() + " книг");
                });
                return null;
            } catch (Exception e) {
                log.error("Advanced search failed", e);
                UiExecutor.runOnUiThread(() -> statusLabel.setText("Помилка пошуку: " + e.getMessage()));
                return null;
            }
        });
    }

    private SearchRequest buildAdvancedRequest(String freeText) {
        SearchRequest.Builder b = SearchRequest.builder()
                .text(buildTextQuery(freeText))
                .ratingFrom(parseInt(ratingFromFilter, null))
                .ratingTo(parseInt(ratingToFilter, null))
                .yearFrom(parseInt(yearFromFilter, null))
                .yearTo(parseInt(yearToFilter, null))
                .addedFrom(addedFromPicker == null ? null : addedFromPicker.getValue())
                .addedTo(addedToPicker == null ? null : addedToPicker.getValue())
                .localOnly(localOnlyCheck != null && localOnlyCheck.isSelected() ? Boolean.TRUE : null)
                .limit(1000)
                .mode(SearchMode.PHRASE);
        String lang = text(languageFilter);
        if (!lang.isBlank()) {
            try { b.language(LanguageCode.of(lang)); } catch (Exception ignored) { }
        }
        return b.build();
    }

    private String buildTextQuery(String freeText) {
        List<String> clauses = new ArrayList<>();
        if (freeText != null && !freeText.isBlank()) clauses.add(freeText.trim());
        addFieldClause(clauses, "title", text(titleFilter));
        addFieldClause(clauses, "authors", text(authorFilter));
        addFieldClause(clauses, "series", text(seriesFilter));
        addFieldClause(clauses, "genres", text(genreFilter));
        addFieldClause(clauses, "keywords", text(keywordFilter));
        addFieldClause(clauses, "annotation", text(annotationFilter));
        addFieldClause(clauses, "file_name", text(fileFilter));
        return String.join(" AND ", clauses);
    }

    /** Canonical Lucene query so a saved advanced search is self-contained. */
    private String buildSavedQuery(String freeText) {
        List<String> clauses = new ArrayList<>();
        String textual = buildTextQuery(freeText);
        if (!textual.isBlank()) clauses.add(textual);
        String lang = text(languageFilter);
        if (!lang.isBlank()) clauses.add("language:" + quote(lang));
        Integer rf = parseInt(ratingFromFilter, null), rt = parseInt(ratingToFilter, null);
        if (rf != null || rt != null) clauses.add("rate:[" + (rf == null ? "0" : rf) + " TO " + (rt == null ? "9" : rt) + "]");
        Integer yf = parseInt(yearFromFilter, null), yt = parseInt(yearToFilter, null);
        if (yf != null || yt != null) clauses.add("year:[" + padYear(yf == null ? 0 : yf) + " TO " + padYear(yt == null ? 9999 : yt) + "]");
        LocalDate af = addedFromPicker == null ? null : addedFromPicker.getValue();
        LocalDate at = addedToPicker == null ? null : addedToPicker.getValue();
        if (af != null || at != null) clauses.add("created:[" + formatDate(af, "00000000") + " TO " + formatDate(at, "99999999") + "]");
        if (localOnlyCheck != null && localOnlyCheck.isSelected()) clauses.add("local:1");
        return String.join(" AND ", clauses);
    }

    private void addFieldClause(List<String> clauses, String field, String value) {
        if (value == null || value.isBlank()) return;
        String v = value.trim();
        if (v.startsWith("%") && v.endsWith("%") && v.length() > 2) {
            clauses.add(field + ":*" + escape(v.substring(1, v.length() - 1)) + "*");
        } else if (v.startsWith("=\"") && v.endsWith("\"") && v.length() >= 3) {
            clauses.add(field + ":" + v.substring(1));
        } else if (v.contains(" OR ")) {
            String[] parts = v.split("(?i)\\s+OR\\s+");
            List<String> ors = new ArrayList<>();
            for (String part : parts) ors.add(field + ":" + quote(part));
            clauses.add("(" + String.join(" OR ", ors) + ")");
        } else {
            clauses.add(field + ":" + quote(v));
        }
    }

    private String text(TextField field) { return field == null || field.getText() == null ? "" : field.getText().trim(); }
    private Integer parseInt(TextField field, Integer def) {
        String v = text(field); if (v.isBlank()) return def;
        try { return Integer.parseInt(v); } catch (Exception ignored) { return def; }
    }
    private String quote(String value) { return "\"" + escape(value) + "\""; }
    private String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
    private String padYear(int year) { return String.format(java.util.Locale.ROOT, "%04d", Math.max(0, Math.min(9999, year))); }
    private String formatDate(LocalDate date, String fallback) { return date == null ? fallback : date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE); }

    private boolean hasAdvancedFilters() {
        return !text(titleFilter).isBlank() || !text(authorFilter).isBlank() || !text(seriesFilter).isBlank()
                || !text(genreFilter).isBlank() || !text(keywordFilter).isBlank() || !text(annotationFilter).isBlank()
                || !text(fileFilter).isBlank() || !text(languageFilter).isBlank() || !text(ratingFromFilter).isBlank()
                || !text(ratingToFilter).isBlank() || !text(yearFromFilter).isBlank() || !text(yearToFilter).isBlank()
                || (addedFromPicker != null && addedFromPicker.getValue() != null)
                || (addedToPicker != null && addedToPicker.getValue() != null)
                || (localOnlyCheck != null && localOnlyCheck.isSelected());
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
        String query = buildSavedQuery(searchField.getText());
        if (query == null || query.isBlank()) {
            dialogService.showWarning("Увага", "Введіть запит або задайте фільтри для збереження");
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
        performSearch(searchField.getText());
    }

    @FXML
    public void onClear() {
        searchField.clear();
        for (TextField f : List.of(titleFilter, authorFilter, seriesFilter, genreFilter, keywordFilter,
                annotationFilter, fileFilter, languageFilter, ratingFromFilter, ratingToFilter, yearFromFilter, yearToFilter)) {
            if (f != null) f.clear();
        }
        if (addedFromPicker != null) addedFromPicker.setValue(null);
        if (addedToPicker != null) addedToPicker.setValue(null);
        if (localOnlyCheck != null) localOnlyCheck.setSelected(false);
        clearResults();
        searchField.requestFocus();
    }
}
