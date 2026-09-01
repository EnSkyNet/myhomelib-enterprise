package com.myhomelibcorp.ui.search;

import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.dto.GenreDto;
import com.myhomelibcorp.application.search.GlobalSearchResult;
import com.myhomelibcorp.application.search.SearchService;
import com.myhomelibcorp.application.filter.BookFilterStateService;
import com.myhomelibcorp.ui.filter.BookFilterDialogService;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.service.BookSelectionService;
import com.myhomelibcorp.ui.service.MainLayoutService;
import com.myhomelibcorp.application.query.search.SearchRequest;
import com.myhomelibcorp.application.query.search.SearchMode;
import com.myhomelibcorp.application.usecase.search.SaveSearchUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.LanguageCode;
import com.myhomelibcorp.ui.controller.SavedSearchesController;
import com.myhomelibcorp.ui.navigation.NavigationPanelController;
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
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.geometry.Pos;
import javafx.scene.text.Text;
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
import java.util.Locale;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
@Slf4j
public class SearchWorkspaceController {

    private final SearchService searchService;
    private final NavigationService navigationService;
    private final ApplicationState appState;
    private final ApplicationContext springContext;
    private final DialogService dialogService;
    private final SaveSearchUseCase saveSearchUseCase;
    private final UiBackgroundExecutor executor;
    private final BookFilterStateService filterStateService;
    private final BookFilterDialogService filterDialogService;
    private final LocalizationService i18n;
    private final BookSelectionService bookSelectionService;
    private final NavigationPanelController navigationPanelController;
    private final MainLayoutService mainLayoutService;

    @FXML private TextField searchField;
    @FXML private VBox resultsContainer;
    @FXML private Label statusLabel;
    @FXML private Label filterIndicatorLabel;

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
    @FXML private TableView<BookDto> booksTableView;
    @FXML private TableColumn<BookDto, Void> selectColumn;
    @FXML private TableColumn<BookDto, String> titleColumn;
    @FXML private TableColumn<BookDto, String> authorColumn;
    @FXML private TableColumn<BookDto, String> seriesColumn;
    @FXML private TableColumn<BookDto, String> bookGenresColumn;
    @FXML private TableColumn<BookDto, String> seqNumberColumn;
    @FXML private TableColumn<BookDto, String> yearColumn;
    @FXML private TableColumn<BookDto, String> localColumn;
    @FXML private TableColumn<BookDto, String> fileSizeColumn;
    @FXML private TableColumn<BookDto, String> ratingColumn;
    @FXML private TableColumn<BookDto, String> progressColumn;
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
    private CheckBox masterSelectionCheckBox;
    private boolean suppressSearchListener;
    private final AtomicLong searchGeneration = new AtomicLong();

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
        updateFilterIndicator();
    }

    private void setupButtons() {
        saveSearchButton.setOnAction(e -> onSaveSearch());
        savedSearchesButton.setOnAction(e -> onOpenSavedSearches());
    }

    @FXML
    public void onGlobalFilters() {
        filterDialogService.show(searchField.getScene() == null ? null : searchField.getScene().getWindow())
                .ifPresent(spec -> {
                    updateFilterIndicator();
                    navigationPanelController.refreshForFilterChange();
                    performSearch(searchField.getText());
                });
    }

    private void updateFilterIndicator() {
        if (filterIndicatorLabel == null) return;
        var spec = filterStateService.current();
        filterIndicatorLabel.setText(spec.isActive()
                ? i18n.tr("Фільтр активний") + " (" + spec.activeCriteriaCount() + ")"
                : i18n.tr("Фільтр вимкнено"));
    }

    // ==================== НАЛАШТУВАННЯ СПИСКІВ ====================

    private void setupListViews() {
        seriesListView.setCellFactory(lv -> new ListCell<>() {
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

        genresListView.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(GenreDto item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : i18n.genreName(item.getCode(), item.getName()));
            }
        });
        genresListView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                GenreDto selected = genresListView.getSelectionModel().getSelectedItem();
                if (selected != null) navigationService.navigateToGenre(GenreId.fromCode(selected.getCode()));
            }
        });

        configureBookResultsTable();
    }

    private void configureBookResultsTable() {
        booksTableView.setFixedCellSize(28.0);
        configureSelectionColumn();
        installHighlightedColumn(titleColumn, BookDto::getTitle);
        installHighlightedColumn(authorColumn, BookDto::getAuthorsText);
        installHighlightedColumn(seriesColumn, BookDto::getSeries);
        installHighlightedColumn(bookGenresColumn, this::localizedGenres);
        seqNumberColumn.setCellValueFactory(cell -> new javafx.beans.property.ReadOnlyStringWrapper(
                cell.getValue().getSequenceNumber() == null || cell.getValue().getSequenceNumber() <= 0
                        ? "" : String.valueOf(cell.getValue().getSequenceNumber())));
        yearColumn.setCellValueFactory(cell -> new javafx.beans.property.ReadOnlyStringWrapper(
                cell.getValue().getYear() == null || cell.getValue().getYear() <= 0 ? "" : String.valueOf(cell.getValue().getYear())));
        localColumn.setCellValueFactory(cell -> new javafx.beans.property.ReadOnlyStringWrapper(cell.getValue().getLocalStatus()));
        fileSizeColumn.setCellValueFactory(cell -> new javafx.beans.property.ReadOnlyStringWrapper(cell.getValue().getFileSizeFormatted()));
        ratingColumn.setCellValueFactory(cell -> new javafx.beans.property.ReadOnlyStringWrapper(cell.getValue().getRateStars()));
        progressColumn.setCellValueFactory(cell -> new javafx.beans.property.ReadOnlyStringWrapper(cell.getValue().getProgressFormatted()));

        booksTableView.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) ->
                appState.getBookDetails().setCurrentBook(selected));
        booksTableView.setRowFactory(tv -> {
            TableRow<BookDto> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty() && row.getItem() != null) {
                    navigationService.navigateToBook(BookId.fromString(row.getItem().getId()));
                }
            });
            return row;
        });
    }

    private void configureSelectionColumn() {
        masterSelectionCheckBox = new CheckBox();
        masterSelectionCheckBox.setAllowIndeterminate(true);
        masterSelectionCheckBox.setTooltip(new Tooltip("Вибрати всі книги з результату пошуку"));
        masterSelectionCheckBox.setOnAction(event -> {
            List<BookId> ids = resultBookIds();
            BookSelectionService.SelectionState state = bookSelectionService.stateIds(ids);
            bookSelectionService.setSelectedIds(ids, state != BookSelectionService.SelectionState.ALL);
            booksTableView.refresh();
            refreshMasterSelection();
        });
        selectColumn.setGraphic(masterSelectionCheckBox);
        selectColumn.setSortable(false);
        selectColumn.setCellFactory(ignored -> new TableCell<>() {
            private final CheckBox checkBox = new CheckBox();
            {
                checkBox.setOnAction(event -> {
                    BookDto book = getTableRow() == null ? null : getTableRow().getItem();
                    BookId id = bookId(book);
                    if (id != null) bookSelectionService.setSelected(id, checkBox.isSelected());
                });
            }
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                BookDto book = getTableRow() == null ? null : getTableRow().getItem();
                BookId id = empty ? null : bookId(book);
                if (id == null) {
                    setGraphic(null);
                    return;
                }
                checkBox.setSelected(bookSelectionService.isSelected(id));
                setGraphic(checkBox);
            }
        });
        bookSelectionService.selectedCountProperty().addListener((obs, oldValue, newValue) -> {
            refreshMasterSelection();
            booksTableView.refresh();
        });
    }

    private List<BookId> resultBookIds() {
        return booksTableView.getItems().stream().map(this::bookId).filter(java.util.Objects::nonNull).toList();
    }

    private BookId bookId(BookDto book) {
        if (book == null || book.getId() == null || book.getId().isBlank()) return null;
        try { return BookId.fromString(book.getId()); } catch (RuntimeException ignored) { return null; }
    }

    private void refreshMasterSelection() {
        if (masterSelectionCheckBox == null) return;
        List<BookId> ids = resultBookIds();
        BookSelectionService.SelectionState state = bookSelectionService.stateIds(ids);
        masterSelectionCheckBox.setDisable(ids.isEmpty());
        masterSelectionCheckBox.setIndeterminate(state == BookSelectionService.SelectionState.PARTIAL);
        masterSelectionCheckBox.setSelected(state == BookSelectionService.SelectionState.ALL);
    }

    private void installHighlightedColumn(TableColumn<BookDto, String> column, Function<BookDto, String> extractor) {
        column.setCellValueFactory(cell -> new javafx.beans.property.ReadOnlyStringWrapper(safe(extractor.apply(cell.getValue()))));
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.isBlank()) {
                    setText(null);
                    setGraphic(null);
                    setTooltip(null);
                    return;
                }
                setText(null);
                setGraphic(highlightedInline(item, highlightNeedle()));
                setTooltip(new Tooltip(item));
            }
        });
    }

    private HBox highlightedInline(String value, String needle) {
        HBox flow = new HBox(0);
        flow.setAlignment(Pos.CENTER_LEFT);
        flow.setMinHeight(20);
        flow.setPrefHeight(20);
        flow.setMaxHeight(20);
        if (needle == null || needle.isBlank()) {
            Text plain = new Text(value);
            plain.setStyle("-fx-fill: -mhl-text;");
            flow.getChildren().add(plain);
            return flow;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        String wanted = needle.toLowerCase(Locale.ROOT);
        int from = 0;
        while (from < value.length()) {
            int hit = lower.indexOf(wanted, from);
            if (hit < 0) {
                Text tail = new Text(value.substring(from));
                tail.setStyle("-fx-fill: -mhl-text;");
                flow.getChildren().add(tail);
                break;
            }
            if (hit > from) {
                Text prefix = new Text(value.substring(from, hit));
                prefix.setStyle("-fx-fill: -mhl-text;");
                flow.getChildren().add(prefix);
            }
            Text match = new Text(value.substring(hit, hit + wanted.length()));
            match.setStyle("-fx-fill: -mhl-text; -fx-font-weight: bold;");
            flow.getChildren().add(match);
            from = hit + wanted.length();
        }
        return flow;
    }

    private String highlightNeedle() {
        String value = lastQuery == null ? "" : lastQuery.trim();
        if (value.isBlank()) {
            for (TextField field : List.of(authorFilter, titleFilter, seriesFilter, genreFilter)) {
                String candidate = text(field);
                if (!candidate.isBlank()) { value = candidate; break; }
            }
        }
        if (value.startsWith("%") && value.endsWith("%") && value.length() > 2) value = value.substring(1, value.length() - 1);
        if (value.startsWith("=\"") && value.endsWith("\"") && value.length() > 3) value = value.substring(2, value.length() - 1);
        if (value.contains(":")) value = value.substring(value.lastIndexOf(':') + 1);
        value = value.replace("\"", "").trim();
        return value;
    }

    private String localizedGenres(BookDto book) {
        if (book == null) return "";
        if (!book.getGenreItems().isEmpty()) {
            List<String> siblingCodes = book.getGenreItems().stream()
                    .filter(java.util.Objects::nonNull)
                    .map(GenreDto::getCode)
                    .filter(code -> code != null && !code.isBlank())
                    .toList();
            return book.getGenreItems().stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(genre -> i18n.shouldDisplayGenre(genre.getCode(), siblingCodes))
                    .map(genre -> i18n.genreName(genre.getCode(), genre.getName()))
                    .filter(value -> value != null && !value.isBlank())
                    .distinct()
                    .collect(java.util.stream.Collectors.joining(", "));
        }
        // Genre items carry stable codes. Raw genresText can contain internal identifiers,
        // therefore it is intentionally not used as a UI fallback.
        return "";
    }

    private static String safe(String value) { return value == null ? "" : value; }

    // ==================== ПОШУК ====================

    private void setupSearchListener() {
        searchField.textProperty().addListener((obs, old, query) -> {
            if (suppressSearchListener) return;
            debounce.stop();
            debounce.setOnFinished(e -> performSearch(query));
            debounce.playFromStart();
        });
    }

    /**
     * ВИПРАВЛЕНО: використовує Executor замість new Thread()
     */
    public void performSearch(String query) {
        debounce.stop();
        this.lastQuery = query == null ? "" : query;
        performSearchPage(query);
    }

    private void performSearchPage(String query) {
        long generation = searchGeneration.incrementAndGet();
        if ((query == null || query.isBlank()) && !hasAdvancedFilters() && !filterStateService.current().isActive()) {
            clearResults();
            statusLabel.setText("Введіть запит або задайте фільтри");
            return;
        }

        statusLabel.setText("Пошук…");
        if (!hasAdvancedFilters()) {
            executor.submit(() -> searchService.searchAll(query)).thenAccept(results ->
                    UiExecutor.runOnUiThread(() -> {
                        if (generation != searchGeneration.get()) return;
                        updateResults(results);
                    })).exceptionally(ex -> {
                log.error("Search failed", ex);
                UiExecutor.runOnUiThread(() -> {
                    if (generation == searchGeneration.get()) statusLabel.setText("Помилка пошуку: " + ex.getMessage());
                });
                return null;
            });
            return;
        }

        SearchRequest request = buildAdvancedRequest(query);
        String authorQuery = text(authorFilter);
        executor.submit(() -> new AdvancedSearchUiResult(
                searchService.searchAll(request),
                authorQuery.isBlank() ? List.of() : searchService.searchAuthorsAll(authorQuery)
        )).thenAccept(result ->
                UiExecutor.runOnUiThread(() -> {
                    if (generation != searchGeneration.get()) return;
                    if (!authorQuery.isBlank()) {
                        mainLayoutService.setLeftSidebarVisible(true);
                        navigationPanelController.showAuthorSearchResults(authorQuery, result.authors());
                    } else {
                        navigationPanelController.clearAuthorSearchResults();
                    }
                    setAdvancedResults(result.books());
                })).exceptionally(ex -> {
            log.error("Advanced search failed", ex);
            UiExecutor.runOnUiThread(() -> {
                if (generation == searchGeneration.get()) statusLabel.setText("Помилка пошуку: " + ex.getMessage());
            });
            return null;
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
                .limit(500)
                .offset(0)
                .mode(SearchMode.PHRASE);
        String lang = text(languageFilter);
        if (!lang.isBlank()) {
            try { b.language(LanguageCode.of(lang)); } catch (Exception ignored) { }
        }
        return b.build();
    }

    private void setAdvancedResults(List<BookDto> books) {
        setSectionVisible(authorsSection, false);
        setSectionVisible(seriesSection, false);
        setSectionVisible(genresSection, false);
        boolean hasBooks = books != null && !books.isEmpty();
        setSectionVisible(booksSection, hasBooks);
        booksTableView.getItems().setAll(books == null ? List.of() : books);
        booksTableView.getSelectionModel().clearSelection();
        refreshMasterSelection();
        appState.getBookDetails().setCurrentBook(null);
        booksCountLabel.setText("(" + (books == null ? 0 : books.size()) + ")");
        statusLabel.setText(hasBooks
                ? "Розширений пошук: знайдено " + books.size() + " книг"
                : "Книги не знайдено");
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
        if (rf != null || rt != null) clauses.add("library_rate:[" + (rf == null ? "0" : rf) + " TO " + (rt == null ? "5" : rt) + "]");
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

    private void updateResults(GlobalSearchResult results) {
        if (results.authors().isEmpty()) {
            navigationPanelController.clearAuthorSearchResults();
        } else {
            mainLayoutService.setLeftSidebarVisible(true);
            navigationPanelController.showAuthorSearchResults(lastQuery, results.authors());
        }
        setSectionVisible(authorsSection, false);
        authorsListView.getItems().clear();
        authorsCountLabel.setText("(" + results.authors().size() + ")");

        List<String> series = results.series();
        List<GenreDto> rawGenres = results.genres().stream()
                .filter(java.util.Objects::nonNull)
                .toList();
        List<String> resultGenreCodes = rawGenres.stream()
                .map(GenreDto::getCode)
                .filter(code -> code != null && !code.isBlank())
                .toList();
        List<GenreDto> genres = rawGenres.stream()
                .filter(genre -> i18n.shouldDisplayGenre(genre.getCode(), resultGenreCodes))
                .filter(genre -> {
                    String label = i18n.genreName(genre.getCode(), genre.getName());
                    return label != null && !label.isBlank();
                })
                .toList();
        List<BookDto> books = results.books();

        setSectionVisible(seriesSection, !series.isEmpty());
        seriesListView.getItems().setAll(series);
        seriesCountLabel.setText("(" + series.size() + ")");

        setSectionVisible(genresSection, !genres.isEmpty());
        genresListView.getItems().setAll(genres);
        genresCountLabel.setText("(" + genres.size() + ")");

        setSectionVisible(booksSection, !books.isEmpty());
        booksTableView.getItems().setAll(books);
        booksTableView.getSelectionModel().clearSelection();
        booksTableView.refresh();
        refreshMasterSelection();
        booksCountLabel.setText("(" + books.size() + ")");
        appState.getBookDetails().setCurrentBook(null);

        int total = results.authors().size() + series.size() + genres.size() + books.size();
        statusLabel.setText(total > 0
                ? "Результати пошуку для: \"" + lastQuery + "\" — книг: " + books.size() + ", авторів: " + results.authors().size()
                : "Нічого не знайдено");
    }

    private void setSectionVisible(VBox section, boolean visible) {
        section.setVisible(visible);
        section.setManaged(visible);
    }

    public void clearResults() {
        searchGeneration.incrementAndGet();
        navigationPanelController.clearAuthorSearchResults();
        setSectionVisible(authorsSection, false);
        authorsListView.getItems().clear();
        setSectionVisible(seriesSection, false);
        seriesListView.getItems().clear();
        setSectionVisible(genresSection, false);
        genresListView.getItems().clear();
        setSectionVisible(booksSection, false);
        booksTableView.getItems().clear();
        refreshMasterSelection();
        appState.getBookDetails().setCurrentBook(null);
        statusLabel.setText("Введіть запит для пошуку");
    }

    /** Re-run the current query after a storage/download change without leaving Search Workspace. */
    public void refreshStorageState() {
        performSearch(lastQuery);
    }

    public void setInitialQuery(String query) {
        if (query != null && !query.isBlank()) {
            setSearchTextWithoutDebounce(query);
            performSearch(query);
        } else {
            clearResults();
        }
    }

    public void setResults(List<BookDto> results) {
        if (results != null && !results.isEmpty()) {
            setSectionVisible(booksSection, true);
            booksTableView.getItems().setAll(results);
            booksTableView.getSelectionModel().clearSelection();
            booksTableView.refresh();
            refreshMasterSelection();
            booksCountLabel.setText("(" + results.size() + ")");
            setSectionVisible(authorsSection, false);
            setSectionVisible(seriesSection, false);
            setSectionVisible(genresSection, false);
            statusLabel.setText("Знайдено книг: " + results.size());
            appState.getBookDetails().setCurrentBook(null);
        } else {
            setSectionVisible(booksSection, false);
            booksTableView.getItems().clear();
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
                setSearchTextWithoutDebounce(query);
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
        setSearchTextWithoutDebounce("");
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

    private void setSearchTextWithoutDebounce(String value) {
        debounce.stop();
        suppressSearchListener = true;
        try {
            searchField.setText(value == null ? "" : value);
        } finally {
            suppressSearchListener = false;
        }
    }

    private record AdvancedSearchUiResult(List<BookDto> books, List<AuthorDto> authors) {
        private AdvancedSearchUiResult {
            books = books == null ? List.of() : List.copyOf(books);
            authors = authors == null ? List.of() : List.copyOf(authors);
        }
    }
}
