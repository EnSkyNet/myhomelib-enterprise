package com.myhomelibcorp.ui.search;

import com.myhomelibcorp.shared.util.ThrowableMessages;
import com.myhomelibcorp.application.annotation.AnnotationManagerFilter;
import com.myhomelibcorp.application.annotation.AnnotationManagerItem;
import com.myhomelibcorp.application.annotation.AnnotationManagerPage;
import com.myhomelibcorp.application.annotation.AnnotationManagerService;
import com.myhomelibcorp.application.content.search.ContentSearchResultItem;
import com.myhomelibcorp.application.content.search.ContentSearchResultPage;
import com.myhomelibcorp.application.content.search.ContentSearchService;
import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.dto.GenreDto;
import com.myhomelibcorp.application.dto.PinnedSmartCollectionDto;
import com.myhomelibcorp.application.search.GlobalSearchResult;
import com.myhomelibcorp.application.search.SearchService;
import com.myhomelibcorp.application.filter.BookFilterStateService;
import com.myhomelibcorp.ui.filter.BookFilterDialogService;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.service.BookSelectionService;
import com.myhomelibcorp.ui.service.MainLayoutService;
import com.myhomelibcorp.ui.service.FxmlLoaderFactory;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.application.query.search.SearchRequest;
import com.myhomelibcorp.application.usecase.search.SaveSearchUseCase;
import com.myhomelibcorp.application.usecase.search.LoadSavedSearchesUseCase;
import com.myhomelibcorp.application.usecase.search.BuildSmartCollectionSearchRequestUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.ui.controller.SavedSearchesController;
import com.myhomelibcorp.ui.navigation.NavigationPanelController;
import com.myhomelibcorp.ui.navigation.WorkspaceManager;
import com.myhomelibcorp.ui.navigation.WorkspaceLifecycle;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.util.UiAsyncRequestGuard;
import com.myhomelibcorp.ui.util.UiAsyncRequestToken;
import com.myhomelibcorp.ui.util.UiSubscriptions;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.animation.PauseTransition;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.geometry.Pos;
import javafx.scene.text.Text;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.StringConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@RequiredArgsConstructor
@Slf4j
public class SearchWorkspaceController implements WorkspaceLifecycle {

    private final SearchService searchService;
    private final NavigationService navigationService;
    private final ApplicationState appState;
    private final FxmlLoaderFactory fxmlLoaderFactory;
    private final DialogService dialogService;
    private final SaveSearchUseCase saveSearchUseCase;
    private final LoadSavedSearchesUseCase loadSavedSearchesUseCase;
    private final BuildSmartCollectionSearchRequestUseCase buildSmartCollectionRequestUseCase;
    private final UiBackgroundExecutor executor;
    private final BookFilterStateService filterStateService;
    private final BookFilterDialogService filterDialogService;
    private final LocalizationService i18n;
    private final BookSelectionService bookSelectionService;
    private final NavigationPanelController navigationPanelController;
    private final MainLayoutService mainLayoutService;
    private final AnnotationManagerService annotationManagerService;
    private final ContentSearchService contentSearchService;
    private final WorkspaceManager workspaceManager;

    @FXML private TextField searchField;
    @FXML private ChoiceBox<SearchScope> searchModeChoice;
    @FXML private VBox resultsContainer;
    @FXML private Label statusLabel;
    @FXML private Label filterIndicatorLabel;
    @FXML private FlowPane pinnedScopesPane;
    @FXML private Label activeScopeLabel;
    @FXML private Button clearScopeButton;

    @FXML private VBox authorsSection;
    @FXML private ListView<AuthorDto> authorsListView;
    @FXML private Label authorsCountLabel;

    @FXML private VBox seriesSection;
    @FXML private ListView<String> seriesListView;
    @FXML private Label seriesCountLabel;

    @FXML private VBox genresSection;
    @FXML private ListView<GenreDto> genresListView;
    @FXML private Label genresCountLabel;

    @FXML private VBox annotationsSection;
    @FXML private ListView<AnnotationManagerItem> annotationsListView;
    @FXML private Label annotationsCountLabel;
    @FXML private Label annotationsTitleLabel;

    @FXML private VBox contentSection;
    @FXML private ListView<ContentSearchResultItem> contentListView;
    @FXML private Label contentCountLabel;
    @FXML private Label contentTitleLabel;

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
    @FXML private Button loadMoreBooksButton;

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
    private static final int BOOK_PAGE_SIZE = 500;
    private static final int ANNOTATION_RESULT_LIMIT = 100;
    private static final int CONTENT_RESULT_LIMIT = 200;
    private volatile Future<?> contentSearchTask;
    private SearchRequest activeBookRequest;
    private String activeSmartCollectionId;
    private String activeSmartCollectionName;
    private long activeBookTotal;
    private boolean loadingMoreBooks;

    private final PauseTransition debounce = new PauseTransition(Duration.millis(300));
    private final UiSubscriptions subscriptions = new UiSubscriptions();

    @FXML
    public void initialize() {
        setupListViews();
        setupSearchMode();
        setupSearchListener();
        setupButtons();
        searchField.requestFocus();
        setSectionVisible(authorsSection, false);
        setSectionVisible(seriesSection, false);
        setSectionVisible(genresSection, false);
        setSectionVisible(annotationsSection, false);
        setSectionVisible(contentSection, false);
        setSectionVisible(booksSection, false);
        if (annotationsTitleLabel != null) annotationsTitleLabel.setText(i18n.text("ui.search.annotations.title"));
        if (contentTitleLabel != null) contentTitleLabel.setText(i18n.text("ui.search.contents.title"));
        updateFilterIndicator();
        refreshPinnedScopes();
        updateActiveScopeIndicator();
        subscriptions.listen(appState.currentLibraryCollectionProperty(), (obs, oldCollection, newCollection) -> {
            String oldId = oldCollection == null ? null : oldCollection.getId();
            String newId = newCollection == null ? null : newCollection.getId();
            if (!java.util.Objects.equals(oldId, newId)) {
                UiAsyncRequestGuard.invalidate(searchGeneration);
                cancelContentSearch();
                debounce.stop();
                resetBookPaging();
                clearResults();
                navigationPanelController.clearAuthorSearchResults();
                activeSmartCollectionId = null;
                activeSmartCollectionName = null;
                updateActiveScopeIndicator();
            }
        });
    }

    private void setupSearchMode() {
        if (searchModeChoice == null) return;
        searchModeChoice.getItems().setAll(SearchScope.values());
        searchModeChoice.setConverter(new StringConverter<>() {
            @Override public String toString(SearchScope scope) {
                if (scope == null) return "";
                return i18n.text(scope.i18nKey);
            }
            @Override public SearchScope fromString(String string) { return SearchScope.BOTH; }
        });
        searchModeChoice.getSelectionModel().select(SearchScope.BOTH);
        searchModeChoice.getSelectionModel().selectedItemProperty().addListener((obs, old, current) -> {
            if (old != current && searchField != null && searchField.getText() != null && !searchField.getText().isBlank()) {
                performSearch(searchField.getText());
            }
        });
        searchModeChoice.setAccessibleText(i18n.text("ui.search.mode.accessible"));
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
                ? i18n.text("ui.search.filter.active") + " (" + spec.activeCriteriaCount() + ")"
                : i18n.text("ui.search.filter.disabled"));
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

        annotationsListView.setAccessibleText(i18n.text("ui.search.annotations.accessible"));
        annotationsListView.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(AnnotationManagerItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                    setAccessibleText(null);
                    return;
                }
                String summary = annotationSummary(item);
                String label = item.bookTitle().isBlank() ? summary : item.bookTitle() + " — " + summary;
                setText(label);
                setTooltip(new Tooltip(annotationDetails(item)));
                setAccessibleText(label);
            }
        });
        annotationsListView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) openSelectedAnnotation();
        });
        annotationsListView.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) openSelectedAnnotation();
        });

        if (contentListView != null) {
            contentListView.setAccessibleText(i18n.text("ui.search.contents.accessible"));
            contentListView.setCellFactory(lv -> new ListCell<>() {
                @Override protected void updateItem(ContentSearchResultItem item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null); setTooltip(null); setAccessibleText(null); return;
                    }
                    String chapter = item.chapterTitle().isBlank() ? i18n.text("ui.search.contents.chapter_unknown") : item.chapterTitle();
                    String header = item.bookTitle() + " — " + chapter;
                    String body = item.snippet().isBlank() ? header : header + "\n" + item.snippet();
                    setText(body);
                    setTooltip(new Tooltip(body));
                    setAccessibleText(body);
                }
            });
            contentListView.setOnMouseClicked(e -> { if (e.getClickCount() == 2) openSelectedContentHit(); });
            contentListView.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER) openSelectedContentHit(); });
        }

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
        masterSelectionCheckBox.setTooltip(new Tooltip(i18n.text("ui.search.select_all.tooltip")));
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
        subscriptions.listen(bookSelectionService.selectedCountProperty(), (obs, oldValue, newValue) -> {
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
        cancelContentSearch();
        // A pinned Smart Collection is an explicit search scope. Keep it active until
        // the user clears the scope; typing a query refines the scoped result instead
        // of silently falling back to the entire library.
        this.lastQuery = query == null ? "" : query;
        SearchScope scope = currentSearchScope();
        if (scope.includesMetadata()) {
            performSearchPage(query);
        } else {
            UiAsyncRequestGuard.next(searchGeneration, appState);
            clearMetadataResultsOnly();
        }
        if (scope.includesContents() && activeSmartCollectionId == null) {
            performContentSearch(query, scope);
        } else {
            // ContentSearchService is collection-scoped, not SmartCollection-scoped. Do not
            // leak unscoped content hits while a Smart Collection is visibly active.
            updateContentResults(ContentSearchResultPage.empty(CONTENT_RESULT_LIMIT));
            if (scope == SearchScope.CONTENTS && activeSmartCollectionId != null) {
                statusLabel.setText(i18n.text("ui.search.scope.content_requires_global"));
            }
        }
    }

    private void performSearchPage(String query) {
        UiAsyncRequestToken requestToken = UiAsyncRequestGuard.next(searchGeneration, appState);
        SearchFormInput form = currentSearchForm(query);
        boolean advanced = SearchQueryFactory.hasAdvancedFilters(form);
        resetBookPaging();
        if (form.freeText().isBlank() && !advanced && !filterStateService.current().isActive()
                && activeSmartCollectionId == null) {
            clearResults();
            statusLabel.setText(i18n.text("ui.search.status.enter_query_or_filters"));
            return;
        }

        statusLabel.setText(i18n.text("ui.search.status.searching"));
        if (!advanced) {
            SearchRequest request = applyActiveSmartCollectionScope(
                    SearchQueryFactory.basic(form.freeText(), BOOK_PAGE_SIZE, 0));
            boolean scoped = activeSmartCollectionId != null;
            executor.submit(() -> new SearchUiPage(
                    scoped ? GlobalSearchResult.empty() : searchService.searchOverview(form.freeText()),
                    searchService.searchPage(request),
                    scoped ? null : searchAnnotations(form.freeText()),
                    request
            )).thenAccept(result ->
                    UiExecutor.runOnUiThread(() -> {
                        if (!UiAsyncRequestGuard.isCurrent(requestToken, searchGeneration, appState)) return;
                        activeBookRequest = result.request();
                        updateResults(result.overview(), result.books(), result.annotations());
                    })).exceptionally(ex -> {
                log.error("Search failed", ex);
                UiExecutor.runOnUiThread(() -> {
                    if (UiAsyncRequestGuard.isCurrent(requestToken, searchGeneration, appState)) statusLabel.setText(i18n.format("ui.search.status.error", ex.getMessage()));
                });
                return null;
            });
            return;
        }

        SearchRequest request = applyActiveSmartCollectionScope(
                SearchQueryFactory.advanced(form, BOOK_PAGE_SIZE, 0));
        String authorQuery = form.author();
        boolean scoped = activeSmartCollectionId != null;
        executor.submit(() -> new AdvancedSearchUiResult(
                searchService.searchPage(request),
                scoped || authorQuery.isBlank() ? List.of() : searchService.searchAuthors(authorQuery, 200),
                scoped ? null : searchAnnotations(annotationQueryText(form)),
                request
        )).thenAccept(result ->
                UiExecutor.runOnUiThread(() -> {
                    if (!UiAsyncRequestGuard.isCurrent(requestToken, searchGeneration, appState)) return;
                    if (!scoped && !authorQuery.isBlank()) {
                        mainLayoutService.setLeftSidebarVisible(true);
                        navigationPanelController.showAuthorSearchResults(authorQuery, result.authors());
                    } else {
                        navigationPanelController.clearAuthorSearchResults();
                    }
                    activeBookRequest = result.request();
                    setAdvancedResults(result.books(), result.annotations());
                })).exceptionally(ex -> {
            log.error("Advanced search failed", ex);
            UiExecutor.runOnUiThread(() -> {
                if (UiAsyncRequestGuard.isCurrent(requestToken, searchGeneration, appState)) statusLabel.setText(i18n.format("ui.search.status.error", ex.getMessage()));
            });
            return null;
        });
    }

    private void performContentSearch(String query, SearchScope scope) {
        String normalized = query == null ? "" : query.trim();
        String collectionId = appState.getCurrentLibraryCollectionId();
        if (normalized.isBlank() || collectionId == null || collectionId.isBlank()) {
            updateContentResults(ContentSearchResultPage.empty(CONTENT_RESULT_LIMIT));
            return;
        }
        UiAsyncRequestToken token = UiAsyncRequestGuard.snapshot(searchGeneration, appState);
        contentSearchTask = executor.submitCancellable(() -> {
            try {
                ContentSearchResultPage page = contentSearchService.search(collectionId, normalized, 0, CONTENT_RESULT_LIMIT);
                if (Thread.currentThread().isInterrupted()) return null;
                UiExecutor.runOnUiThread(() -> {
                    if (!UiAsyncRequestGuard.isCurrent(token, searchGeneration, appState)) return;
                    updateContentResults(page);
                    if (scope == SearchScope.CONTENTS) {
                        statusLabel.setText(page.total() == 0
                                ? i18n.text("ui.search.status.nothing_found")
                                : i18n.format("ui.search.contents.status", page.items().size(), page.total()));
                    }
                });
            } catch (java.util.concurrent.CancellationException cancelled) {
                // A newer query owns the workspace now.
            } catch (RuntimeException failure) {
                if (!Thread.currentThread().isInterrupted()) {
                    log.error("Content search failed", failure);
                    UiExecutor.runOnUiThread(() -> {
                        if (UiAsyncRequestGuard.isCurrent(token, searchGeneration, appState))
                            statusLabel.setText(i18n.format("ui.search.status.error", ThrowableMessages.rootMessage(failure, i18n.text("common.error.unknown"))));
                    });
                }
            }
            return null;
        });
    }

    private void updateContentResults(ContentSearchResultPage page) {
        if (contentListView == null || contentSection == null) return;
        ContentSearchResultPage effective = page == null ? ContentSearchResultPage.empty(CONTENT_RESULT_LIMIT) : page;
        contentListView.getItems().setAll(effective.items());
        if (contentCountLabel != null) contentCountLabel.setText("(" + effective.items().size() + " / " + effective.total() + ")");
        setSectionVisible(contentSection, !effective.items().isEmpty());
    }

    private void openSelectedContentHit() {
        if (contentListView == null) return;
        ContentSearchResultItem selected = contentListView.getSelectionModel().getSelectedItem();
        if (selected != null) workspaceManager.showContentHitInReader(selected.bookId(), selected.artifactId(), selected.matchOffset());
    }

    private void cancelContentSearch() {
        Future<?> task = contentSearchTask;
        contentSearchTask = null;
        if (task != null && !task.isDone()) task.cancel(true);
    }

    private SearchScope currentSearchScope() {
        if (searchModeChoice == null || searchModeChoice.getValue() == null) return SearchScope.BOTH;
        return searchModeChoice.getValue();
    }

    private void clearMetadataResultsOnly() {
        resetBookPaging();
        navigationPanelController.clearAuthorSearchResults();
        setSectionVisible(authorsSection, false); authorsListView.getItems().clear();
        setSectionVisible(seriesSection, false); seriesListView.getItems().clear();
        setSectionVisible(genresSection, false); genresListView.getItems().clear();
        setSectionVisible(annotationsSection, false); annotationsListView.getItems().clear();
        setSectionVisible(booksSection, false); booksTableView.getItems().clear();
        refreshMasterSelection();
        appState.getBookDetails().setCurrentBook(null);
        statusLabel.setText(i18n.text("ui.search.status.searching"));
    }

    private SearchFormInput currentSearchForm(String freeText) {
        return new SearchFormInput(
                freeText,
                text(titleFilter),
                text(authorFilter),
                text(seriesFilter),
                text(genreFilter),
                text(keywordFilter),
                text(annotationFilter),
                text(fileFilter),
                text(languageFilter),
                text(ratingFromFilter),
                text(ratingToFilter),
                text(yearFromFilter),
                text(yearToFilter),
                addedFromPicker == null ? null : addedFromPicker.getValue(),
                addedToPicker == null ? null : addedToPicker.getValue(),
                localOnlyCheck != null && localOnlyCheck.isSelected());
    }

    private void setAdvancedResults(PageResult<BookDto> page, AnnotationManagerPage annotations) {
        setSectionVisible(authorsSection, false);
        setSectionVisible(seriesSection, false);
        setSectionVisible(genresSection, false);
        updateAnnotationResults(annotations);
        List<BookDto> books = page == null ? List.of() : page.content();
        boolean hasBooks = books != null && !books.isEmpty();
        setSectionVisible(booksSection, hasBooks);
        booksTableView.getItems().setAll(books);
        booksTableView.getSelectionModel().clearSelection();
        refreshMasterSelection();
        appState.getBookDetails().setCurrentBook(null);
        updateBookPagingUi(page);
        long annotationTotal = annotations == null ? 0L : annotations.total();
        String baseStatus = hasBooks
                ? i18n.format("ui.search.status.advanced_loaded", books.size(), page.totalElements())
                : i18n.text("ui.search.status.books_not_found");
        statusLabel.setText(annotationTotal > 0
                ? baseStatus + " · " + i18n.format("ui.search.annotations.found", annotationTotal)
                : baseStatus);
    }

    private AnnotationManagerPage searchAnnotations(String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isBlank()) return new AnnotationManagerPage(List.of(), 0L, 0, ANNOTATION_RESULT_LIMIT);
        return annotationManagerService.query(
                new AnnotationManagerFilter(normalized, null, null, null, null, null, null),
                0, ANNOTATION_RESULT_LIMIT);
    }

    private static String annotationQueryText(SearchFormInput form) {
        if (form == null) return "";
        if (form.annotation() != null && !form.annotation().isBlank()) return form.annotation();
        return form.freeText() == null ? "" : form.freeText();
    }

    private String text(TextField field) {
        return field == null || field.getText() == null ? "" : field.getText().trim();
    }

    private void updateResults(GlobalSearchResult results, PageResult<BookDto> bookPage, AnnotationManagerPage annotations) {
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
        List<BookDto> books = bookPage == null ? List.of() : bookPage.content();

        setSectionVisible(seriesSection, !series.isEmpty());
        seriesListView.getItems().setAll(series);
        seriesCountLabel.setText("(" + series.size() + ")");

        setSectionVisible(genresSection, !genres.isEmpty());
        genresListView.getItems().setAll(genres);
        genresCountLabel.setText("(" + genres.size() + ")");

        updateAnnotationResults(annotations);

        setSectionVisible(booksSection, !books.isEmpty());
        booksTableView.getItems().setAll(books);
        booksTableView.getSelectionModel().clearSelection();
        booksTableView.refresh();
        refreshMasterSelection();
        updateBookPagingUi(bookPage);
        appState.getBookDetails().setCurrentBook(null);

        long bookTotal = bookPage == null ? 0 : bookPage.totalElements();
        long annotationTotal = annotations == null ? 0L : annotations.total();
        long total = results.authors().size() + series.size() + genres.size() + bookTotal + annotationTotal;
        String baseStatus = total > 0
                ? i18n.format("ui.search.status.results_summary", lastQuery, books.size(), bookTotal, results.authors().size())
                : i18n.text("ui.search.status.nothing_found");
        statusLabel.setText(annotationTotal > 0
                ? baseStatus + " · " + i18n.format("ui.search.annotations.found", annotationTotal)
                : baseStatus);
    }

    private void updateAnnotationResults(AnnotationManagerPage page) {
        AnnotationManagerPage effective = page == null
                ? new AnnotationManagerPage(List.of(), 0L, 0, ANNOTATION_RESULT_LIMIT)
                : page;
        annotationsListView.getItems().setAll(effective.items());
        annotationsCountLabel.setText("(" + effective.items().size() + " / " + effective.total() + ")");
        setSectionVisible(annotationsSection, !effective.items().isEmpty());
    }

    private void openSelectedAnnotation() {
        AnnotationManagerItem selected = annotationsListView.getSelectionModel().getSelectedItem();
        if (selected != null) workspaceManager.showAnnotationInReader(selected.bookId(), selected.id());
    }

    private static String annotationSummary(AnnotationManagerItem item) {
        if (item == null) return "";
        String text = item.note().isBlank() ? item.quote() : item.note();
        text = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (text.isBlank()) text = item.chapterTitle();
        if (text == null || text.isBlank()) text = item.type().name();
        return text.length() > 140 ? text.substring(0, 137) + "…" : text;
    }

    private static String annotationDetails(AnnotationManagerItem item) {
        if (item == null) return "";
        StringBuilder details = new StringBuilder();
        if (!item.chapterTitle().isBlank()) details.append(item.chapterTitle());
        if (!item.quote().isBlank()) {
            if (!details.isEmpty()) details.append("\n");
            details.append(item.quote());
        }
        if (!item.note().isBlank() && !item.note().equals(item.quote())) {
            if (!details.isEmpty()) details.append("\n");
            details.append(item.note());
        }
        if (!item.tags().isEmpty()) {
            if (!details.isEmpty()) details.append("\n");
            details.append(String.join(", ", item.tags()));
        }
        return details.toString();
    }

    @FXML
    private void onLoadMoreBooks() {
        if (loadingMoreBooks || activeBookRequest == null || booksTableView.getItems().size() >= activeBookTotal) {
            return;
        }

        final UiAsyncRequestToken requestToken = UiAsyncRequestGuard.snapshot(searchGeneration, appState);
        final int offset = booksTableView.getItems().size();
        final SearchRequest requestSnapshot = activeBookRequest;
        loadingMoreBooks = true;
        updateLoadMoreButton();
        statusLabel.setText(i18n.text("ui.search.status.loading_next"));

        executor.submit(() -> searchService.searchPage(requestSnapshot, BOOK_PAGE_SIZE, offset, activeBookTotal)).thenAccept(page ->
                UiExecutor.runOnUiThread(() -> {
                    if (!UiAsyncRequestGuard.isCurrent(requestToken, searchGeneration, appState)) return;
                    loadingMoreBooks = false;
                    if (page != null && !page.content().isEmpty()) {
                        booksTableView.getItems().addAll(page.content());
                        booksTableView.refresh();
                        refreshMasterSelection();
                    }
                    updateBookPagingUi(page == null
                            ? PageResult.of(List.copyOf(booksTableView.getItems()), activeBookTotal, 0, BOOK_PAGE_SIZE)
                            : page);
                    long loaded = booksTableView.getItems().size();
                    statusLabel.setText(i18n.format("ui.search.status.books_loaded", loaded, activeBookTotal));
                })).exceptionally(ex -> {
            log.error("Loading next search page failed", ex);
            UiExecutor.runOnUiThread(() -> {
                if (!UiAsyncRequestGuard.isCurrent(requestToken, searchGeneration, appState)) return;
                loadingMoreBooks = false;
                updateLoadMoreButton();
                statusLabel.setText(i18n.format("ui.search.status.next_page_error", ThrowableMessages.rootMessage(ex, i18n.text("common.error.unknown"))));
            });
            return null;
        });
    }

    private void updateBookPagingUi(PageResult<BookDto> page) {
        if (page != null) {
            activeBookTotal = page.totalElements();
        }
        long loaded = booksTableView.getItems().size();
        booksCountLabel.setText("(" + loaded + " / " + activeBookTotal + ")");
        updateLoadMoreButton();
    }

    private void updateLoadMoreButton() {
        if (loadMoreBooksButton == null) return;
        boolean visible = activeBookRequest != null && booksTableView.getItems().size() < activeBookTotal;
        loadMoreBooksButton.setVisible(visible);
        loadMoreBooksButton.setManaged(visible);
        loadMoreBooksButton.setDisable(loadingMoreBooks);
        loadMoreBooksButton.setText(loadingMoreBooks ? i18n.text("ui.search.load_more.loading") : i18n.text("ui.search.load_more.action"));
    }

    private void resetBookPaging() {
        activeBookRequest = null;
        activeBookTotal = 0;
        loadingMoreBooks = false;
        updateLoadMoreButton();
    }


    private void setSectionVisible(VBox section, boolean visible) {
        section.setVisible(visible);
        section.setManaged(visible);
    }

    public void clearResults() {
        searchGeneration.incrementAndGet();
        cancelContentSearch();
        activeSmartCollectionId = null;
        activeSmartCollectionName = null;
        updateActiveScopeIndicator();
        resetBookPaging();
        navigationPanelController.clearAuthorSearchResults();
        setSectionVisible(authorsSection, false);
        authorsListView.getItems().clear();
        setSectionVisible(seriesSection, false);
        seriesListView.getItems().clear();
        setSectionVisible(genresSection, false);
        genresListView.getItems().clear();
        setSectionVisible(annotationsSection, false);
        annotationsListView.getItems().clear();
        annotationsCountLabel.setText("(0)");
        setSectionVisible(contentSection, false);
        if (contentListView != null) contentListView.getItems().clear();
        if (contentCountLabel != null) contentCountLabel.setText("(0)");
        setSectionVisible(booksSection, false);
        booksTableView.getItems().clear();
        refreshMasterSelection();
        appState.getBookDetails().setCurrentBook(null);
        statusLabel.setText(i18n.text("ui.search.status.enter_query"));
    }

    /** Re-run the current query after a storage/download change without leaving Search Workspace. */
    public void refreshStorageState() {
        // Re-run the visible refinement; applyActiveSmartCollectionScope keeps the
        // pinned scope intact when one is active.
        performSearch(searchField == null ? lastQuery : searchField.getText());
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
        resetBookPaging();
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
            setSectionVisible(annotationsSection, false);
            annotationsListView.getItems().clear();
            statusLabel.setText(i18n.format("ui.search.status.found_books", results.size()));
            appState.getBookDetails().setCurrentBook(null);
        } else {
            setSectionVisible(booksSection, false);
            booksTableView.getItems().clear();
            appState.getBookDetails().setCurrentBook(null);
            statusLabel.setText(i18n.text("ui.search.status.books_not_found"));
        }
    }

    // ==================== ЗБЕРЕЖЕНІ ПОШУКИ ====================

    @FXML
    private void onSaveSearch() {
        String query = SearchQueryFactory.savedQuery(currentSearchForm(searchField.getText()));
        if (query == null || query.isBlank()) {
            dialogService.showWarning(i18n.text("common.warning"), i18n.text("ui.search.save.requires_query"));
            return;
        }

        String name = dialogService.showTextInput(
                i18n.text("ui.search.save.title"),
                i18n.text("ui.search.save.header"),
                i18n.text("common.name.label"),
                query.length() > 30 ? query.substring(0, 30) + "..." : query
        ).orElse(null);

        if (name == null || name.isBlank()) {
            return;
        }

        try {
            saveSearchUseCase.execute(name, query, null);
            refreshPinnedScopes();
            dialogService.showInfo(i18n.text("common.success"), i18n.format("ui.search.save.success", name));
        } catch (Exception e) {
            log.error("Помилка збереження пошуку", e);
            dialogService.showError(i18n.text("common.error"), i18n.format("ui.search.save.error", e.getMessage()));
        }
    }

    @FXML
    private void onOpenSavedSearches() {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/view/saved-searches.fxml"));
            fxmlLoaderFactory.configureControllerFactory(loader);
            Parent root = loader.load();

            SavedSearchesController controller = loader.getController();
            controller.setOnSearchSelected(saved -> {
                if (saved.isSmartCollection()) {
                    performSmartCollection(saved.getId(), saved.getName());
                } else {
                    setSearchTextWithoutDebounce(saved.getQuery());
                    performSearch(saved.getQuery());
                }
            });

            Stage stage = new Stage();
            stage.setTitle(i18n.text("ui.search.saved.title"));
            stage.setScene(new Scene(root, 450, 500));
            stage.initModality(Modality.WINDOW_MODAL);
            stage.initOwner(searchField.getScene().getWindow());
            stage.setOnHidden(event -> refreshPinnedScopes());
            stage.show();

        } catch (Exception e) {
            log.error("Помилка відкриття збережених пошуків", e);
            dialogService.showError(i18n.text("common.error"), i18n.format("ui.search.saved.open_error", e.getMessage()));
        }
    }

    private void refreshPinnedScopes() {
        if (pinnedScopesPane == null) return;
        try {
            List<PinnedSmartCollectionDto> pinned = loadSavedSearchesUseCase.executePinnedSmartCollections();
            pinnedScopesPane.getChildren().clear();
            for (PinnedSmartCollectionDto saved : pinned) {
                Button chip = new Button("★ " + saved.name());
                chip.getStyleClass().add("scope-chip");
                chip.setAccessibleText(i18n.format("ui.search.scope.open", saved.name()));
                chip.setOnAction(event -> performSmartCollection(saved.id(), saved.name()));
                pinnedScopesPane.getChildren().add(chip);
            }
            pinnedScopesPane.setManaged(!pinned.isEmpty());
            pinnedScopesPane.setVisible(!pinned.isEmpty());
        } catch (RuntimeException error) {
            log.warn("Не вдалося завантажити pinned Smart Collections", error);
            pinnedScopesPane.getChildren().clear();
            pinnedScopesPane.setManaged(false);
            pinnedScopesPane.setVisible(false);
        }
    }

    private SearchRequest applyActiveSmartCollectionScope(SearchRequest request) {
        if (request == null || activeSmartCollectionId == null || activeSmartCollectionId.isBlank()) return request;
        SearchRequest scope = buildSmartCollectionRequestUseCase.execute(activeSmartCollectionId, request.limit(), request.offset());
        return SearchRequest.builder()
                .text(request.text())
                .authorId(request.authorId())
                .genreId(request.genreId())
                .language(request.language())
                .ratingFrom(request.ratingFrom())
                .ratingTo(request.ratingTo())
                .yearFrom(request.yearFrom())
                .yearTo(request.yearTo())
                .addedFrom(request.addedFrom())
                .addedTo(request.addedTo())
                .localOnly(request.localOnly())
                .filterSpec(request.filterSpec())
                .smartCollectionSpec(scope.smartCollectionSpec())
                .customFieldFilters(request.customFieldFilters())
                .limit(request.limit())
                .offset(request.offset())
                .mode(request.mode())
                .trackTotalHits(request.trackTotalHits())
                .build();
    }

    private void updateActiveScopeIndicator() {
        if (activeScopeLabel != null) {
            boolean active = activeSmartCollectionId != null && !activeSmartCollectionId.isBlank();
            activeScopeLabel.setText(active ? i18n.format("ui.search.scope.active", activeSmartCollectionName) : "");
            activeScopeLabel.setVisible(active);
            activeScopeLabel.setManaged(active);
        }
        if (clearScopeButton != null) {
            boolean active = activeSmartCollectionId != null && !activeSmartCollectionId.isBlank();
            clearScopeButton.setVisible(active);
            clearScopeButton.setManaged(active);
        }
    }

    @FXML
    public void clearSmartCollectionScope() {
        activeSmartCollectionId = null;
        activeSmartCollectionName = null;
        updateActiveScopeIndicator();
        // Clearing the scope must not silently discard the user's refinement. Re-run the
        // same query/advanced/global filters against the whole active library when present.
        String query = searchField == null || searchField.getText() == null ? "" : searchField.getText();
        SearchFormInput form = currentSearchForm(query);
        if (!query.isBlank() || SearchQueryFactory.hasAdvancedFilters(form) || filterStateService.current().isActive()) {
            performSearch(query);
        } else {
            clearResults();
        }
        if (searchField != null) searchField.requestFocus();
    }

    private void performSmartCollection(String savedSearchId, String savedSearchName) {
        if (savedSearchId == null || savedSearchId.isBlank()) return;
        debounce.stop();
        clearResults();
        activeSmartCollectionId = savedSearchId;
        activeSmartCollectionName = savedSearchName == null ? "" : savedSearchName;
        updateActiveScopeIndicator();
        this.lastQuery = activeSmartCollectionName;
        setSearchTextWithoutDebounce("");
        UiAsyncRequestToken requestToken = UiAsyncRequestGuard.next(searchGeneration, appState);
        SearchRequest request = buildSmartCollectionRequestUseCase.execute(savedSearchId, BOOK_PAGE_SIZE, 0);
        statusLabel.setText(i18n.format("ui.smart_collection.loading", activeSmartCollectionName));
        executor.submit(() -> searchService.searchPage(request)).thenAccept(page ->
                UiExecutor.runOnUiThread(() -> {
                    if (!UiAsyncRequestGuard.isCurrent(requestToken, searchGeneration, appState)) return;
                    activeBookRequest = request;
                    setAdvancedResults(page, null);
                    statusLabel.setText(i18n.format("ui.smart_collection.loaded", activeSmartCollectionName,
                            page == null ? 0 : page.totalElements()));
                })).exceptionally(ex -> {
            log.error("Smart collection search failed", ex);
            UiExecutor.runOnUiThread(() -> {
                if (UiAsyncRequestGuard.isCurrent(requestToken, searchGeneration, appState)) {
                    statusLabel.setText(i18n.format("ui.smart_collection.load_error", ex.getMessage()));
                }
            });
            return null;
        });
    }

    @FXML
    public void onSearch() {
        performSearch(searchField.getText());
    }

    @FXML
    public void onClear() {
        setSearchTextWithoutDebounce("");
        clearAdvancedFields();
        if (activeSmartCollectionId != null) {
            performSearch("");
        } else {
            clearResults();
        }
        searchField.requestFocus();
    }

    @FXML
    public void onClearAdvancedFields() {
        clearAdvancedFields();
        if ((searchField.getText() != null && !searchField.getText().isBlank()) || activeSmartCollectionId != null) {
            performSearch(searchField.getText());
        } else {
            clearResults();
        }
    }

    private void clearAdvancedFields() {
        for (TextField f : List.of(titleFilter, authorFilter, seriesFilter, genreFilter, keywordFilter,
                annotationFilter, fileFilter, languageFilter, ratingFromFilter, ratingToFilter, yearFromFilter, yearToFilter)) {
            if (f != null) f.clear();
        }
        if (addedFromPicker != null) addedFromPicker.setValue(null);
        if (addedToPicker != null) addedToPicker.setValue(null);
        if (localOnlyCheck != null) localOnlyCheck.setSelected(false);
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

    enum SearchScope {
        METADATA("ui.search.mode.metadata"),
        CONTENTS("ui.search.mode.contents"),
        BOTH("ui.search.mode.both");

        private final String i18nKey;
        SearchScope(String i18nKey) { this.i18nKey = i18nKey; }
        boolean includesMetadata() { return this != CONTENTS; }
        boolean includesContents() { return this != METADATA; }
    }

    private record SearchUiPage(
            GlobalSearchResult overview,
            PageResult<BookDto> books,
            AnnotationManagerPage annotations,
            SearchRequest request) {
        private SearchUiPage {
            overview = overview == null ? GlobalSearchResult.empty() : overview;
            books = books == null ? PageResult.empty() : books;
            annotations = annotations == null ? new AnnotationManagerPage(List.of(), 0L, 0, ANNOTATION_RESULT_LIMIT) : annotations;
        }
    }

    private record AdvancedSearchUiResult(
            PageResult<BookDto> books,
            List<AuthorDto> authors,
            AnnotationManagerPage annotations,
            SearchRequest request) {
        private AdvancedSearchUiResult {
            books = books == null ? PageResult.empty() : books;
            authors = authors == null ? List.of() : List.copyOf(authors);
            annotations = annotations == null ? new AnnotationManagerPage(List.of(), 0L, 0, ANNOTATION_RESULT_LIMIT) : annotations;
        }
    }
    @Override
    public void dispose() {
        UiAsyncRequestGuard.invalidate(searchGeneration);
        cancelContentSearch();
        debounce.stop();
        subscriptions.close();
    }

}
