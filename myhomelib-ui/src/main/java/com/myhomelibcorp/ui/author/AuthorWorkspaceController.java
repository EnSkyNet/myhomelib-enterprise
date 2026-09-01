package com.myhomelibcorp.ui.author;

import com.myhomelibcorp.application.catalog.CatalogUpdateService;
import com.myhomelibcorp.application.dto.AuthorBookStatistics;
import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.dto.BookListItem;
import com.myhomelibcorp.application.settings.UiPreferenceService;
import com.myhomelibcorp.application.query.common.SortBy;
import com.myhomelibcorp.application.query.common.SortDirection;
import com.myhomelibcorp.application.usecase.author.LoadAuthorBookStatisticsUseCase;
import com.myhomelibcorp.application.usecase.author.LoadAuthorByIdUseCase;
import com.myhomelibcorp.application.usecase.author.UpdateAuthorDescriptionUseCase;
import com.myhomelibcorp.application.usecase.book.LoadBooksByAuthorUseCase;
import com.myhomelibcorp.application.usecase.book.ResolveBookLocalAvailabilityUseCase;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.controller.ExportController;
import com.myhomelibcorp.ui.mapper.BookViewModelMapper;
import com.myhomelibcorp.ui.service.BookDownloadCoordinator;
import com.myhomelibcorp.ui.service.BookSelectionService;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.table.TableProfileService;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.animation.PauseTransition;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@RequiredArgsConstructor
@Slf4j
public class AuthorWorkspaceController {

    private static final String PROFILE_KEY = "author-workspace";
    private static final String SORT_KEY = "ui.author.sort";
    private static final String SORT_DIRECTION_KEY = "ui.author.sortDirection";

    private final LoadAuthorByIdUseCase loadAuthorByIdUseCase;
    private final UpdateAuthorDescriptionUseCase updateAuthorDescriptionUseCase;
    private final LoadBooksByAuthorUseCase loadBooksByAuthorUseCase;
    private final ResolveBookLocalAvailabilityUseCase resolveBookLocalAvailabilityUseCase;
    private final LoadAuthorBookStatisticsUseCase loadAuthorBookStatisticsUseCase;
    private final CatalogUpdateService catalogUpdateService;
    private final NavigationService navigationService;
    private final BookDownloadCoordinator bookDownloadCoordinator;
    private final BookSelectionService bookSelectionService;
    private final ExportController exportController;
    private final ApplicationState appState;
    private final BookViewModelMapper bookViewModelMapper;
    private final UiBackgroundExecutor executor;
    private final TableProfileService tableProfileService;
    private final UiPreferenceService preferences;

    @FXML private Label authorNameLabel;
    @FXML private Button followAuthorButton;
    @FXML private Label booksCountLabel;
    @FXML private Label seriesCountLabel;
    @FXML private Label genresCountLabel;
    @FXML private TextArea bioLabel;
    @FXML private TableView<BookViewModel> booksTableView;
    @FXML private TableColumn<BookViewModel, String> titleColumn;
    @FXML private TableColumn<BookViewModel, String> seriesColumn;
    @FXML private TableColumn<BookViewModel, String> genresColumn;
    @FXML private TableColumn<BookViewModel, Number> seqNumberColumn;
    @FXML private TableColumn<BookViewModel, Number> yearColumn;
    @FXML private TableColumn<BookViewModel, String> formatColumn;
    @FXML private TableColumn<BookViewModel, String> fileSizeColumn;
    @FXML private TableColumn<BookViewModel, String> rateColumn;
    @FXML private TableColumn<BookViewModel, String> progressColumn;
    @FXML private TextField filterTextField;
    @FXML private ComboBox<String> localFilterComboBox;
    @FXML private Button collapseAllButton;
    @FXML private Button expandAllButton;
    @FXML private Label currentBookLabel;
    @FXML private Label batchSelectionLabel;
    @FXML private Label loadingLabel;

    private final AtomicLong metadataGeneration = new AtomicLong();
    private final AtomicLong booksGeneration = new AtomicLong();
    private final List<BookViewModel> allBooks = new ArrayList<>();
    private final Set<String> collapsedSeries = new LinkedHashSet<>();
    private final LinkedHashMap<String, TableColumn<BookViewModel, ?>> profileColumns = new LinkedHashMap<>();
    private final PauseTransition profileSaveDelay = new PauseTransition(Duration.millis(400));
    private PauseTransition filterDebounce;
    private AuthorId currentAuthorId;
    private AuthorDto currentAuthor;
    private AuthorBookStatistics authorStatistics = AuthorBookStatistics.empty();
    private boolean currentAuthorFollowed;
    private SortBy currentSort = SortBy.SERIES;
    private SortDirection currentDirection = SortDirection.ASC;
    private CheckBox masterSelectionCheckBox;
    private boolean updatingMasterSelection;
    private boolean applyingProfile;
    private boolean downloadedOnly;

    @FXML
    public void initialize() {
        configureSelectionColumn();
        configureColumns();
        configureRowsAndKeyboard();
        configureFilters();
        configureProfilePersistence();
        bookSelectionService.selectedCountProperty().addListener((obs, oldValue, newValue) -> {
            booksTableView.refresh();
            updateSelectionStatus();
        });
        updateSelectionStatus();
    }

    private void configureSelectionColumn() {
        TableColumn<BookViewModel, Boolean> selectColumn = new TableColumn<>();
        selectColumn.setId("select");
        masterSelectionCheckBox = new CheckBox();
        masterSelectionCheckBox.setAllowIndeterminate(true);
        masterSelectionCheckBox.setTooltip(new Tooltip("Виділити всі видимі книги"));
        masterSelectionCheckBox.setOnAction(event -> {
            if (updatingMasterSelection) return;
            List<BookViewModel> visible = visibleConcreteBooks();
            BookSelectionService.SelectionState before = bookSelectionService.state(visible);
            // A click on PARTIAL must mean "select all". JavaFX otherwise cycles the
            // indeterminate checkbox through a state that can look like a no-op/clear.
            bookSelectionService.setSelected(visible, before != BookSelectionService.SelectionState.ALL);
            booksTableView.refresh();
            updateSelectionStatus();
        });
        selectColumn.setGraphic(masterSelectionCheckBox);
        selectColumn.setCellValueFactory(cell -> cell.getValue().selectedProperty());
        selectColumn.setCellFactory(column -> createSelectionCell());
        selectColumn.setEditable(true);
        selectColumn.setSortable(false);
        selectColumn.setResizable(false);
        selectColumn.setPrefWidth(42);
        booksTableView.getColumns().add(0, selectColumn);
        booksTableView.setEditable(true);
    }

    private TableCell<BookViewModel, Boolean> createSelectionCell() {
        return new TableCell<>() {
            private final CheckBox checkBox = new CheckBox();
            private BookViewModel boundRow;
            private final ChangeListener<Boolean> rowSelectionListener = (obs, oldValue, newValue) -> refreshCheckBox();

            {
                checkBox.setOnAction(event -> {
                    BookViewModel row = boundRow;
                    if (row == null) return;
                    if (row.isGroupHeader()) {
                        BookSelectionService.SelectionState before = seriesSelectionState(row.getSeries());
                        // PARTIAL -> ALL; only an already fully selected series is cleared.
                        setSeriesSelected(row.getSeries(), before != BookSelectionService.SelectionState.ALL);
                    } else {
                        row.setSelected(checkBox.isSelected());
                    }
                    booksTableView.refresh();
                    updateSelectionStatus();
                });
            }

            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                if (boundRow != null && !boundRow.isGroupHeader()) {
                    boundRow.selectedProperty().removeListener(rowSelectionListener);
                }
                boundRow = getTableRow() == null ? null : getTableRow().getItem();
                if (empty || boundRow == null) {
                    setGraphic(null);
                    return;
                }
                if (!boundRow.isGroupHeader()) {
                    boundRow.selectedProperty().addListener(rowSelectionListener);
                }
                refreshCheckBox();
                setGraphic(checkBox);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            }

            private void refreshCheckBox() {
                if (boundRow == null) return;
                if (boundRow.isGroupHeader()) {
                    BookSelectionService.SelectionState state = seriesSelectionState(boundRow.getSeries());
                    checkBox.setAllowIndeterminate(true);
                    checkBox.setIndeterminate(state == BookSelectionService.SelectionState.PARTIAL);
                    checkBox.setSelected(state == BookSelectionService.SelectionState.ALL);
                    checkBox.setTooltip(new Tooltip(state == BookSelectionService.SelectionState.ALL
                            ? "Зняти вибір з усієї серії" : "Виділити всю серію"));
                } else {
                    checkBox.setAllowIndeterminate(false);
                    checkBox.setIndeterminate(false);
                    checkBox.setSelected(boundRow.isSelected());
                    checkBox.setTooltip(null);
                }
            }
        };
    }

    private void configureColumns() {
        registerProfileColumn("title", titleColumn);
        registerProfileColumn("series", seriesColumn);
        registerProfileColumn("genres", genresColumn);
        registerProfileColumn("sequence", seqNumberColumn);
        registerProfileColumn("year", yearColumn);
        registerProfileColumn("local", formatColumn);
        registerProfileColumn("fileSize", fileSizeColumn);
        registerProfileColumn("rating", rateColumn);
        registerProfileColumn("progress", progressColumn);

        titleColumn.setCellValueFactory(cell -> cell.getValue().titleProperty());
        titleColumn.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                BookViewModel row = getTableRow() == null ? null : getTableRow().getItem();
                if (empty || row == null) { setText(null); return; }
                if (row.isGroupHeader()) {
                    boolean collapsed = collapsedSeries.contains(normalizeSeries(row.getSeries()));
                    setText((collapsed ? "▶ " : "▼ ") + "Серія: " + row.getSeries());
                } else {
                    setText(row.getSeries() != null && !row.getSeries().isBlank() ? "    " + item : item);
                }
            }
        });
        seriesColumn.setCellValueFactory(cell -> cell.getValue().seriesProperty());
        genresColumn.setCellValueFactory(cell -> cell.getValue().genresTextProperty());
        seqNumberColumn.setCellValueFactory(cell -> cell.getValue().sequenceNumberProperty());
        yearColumn.setCellValueFactory(cellData -> cellData.getValue().yearProperty());
        formatColumn.setCellValueFactory(cell -> cell.getValue().localStatusProperty());
        fileSizeColumn.setCellValueFactory(cell -> cell.getValue().fileSizeFormattedProperty());
        rateColumn.setCellValueFactory(cell -> cell.getValue().rateStarsProperty());
        progressColumn.setCellValueFactory(cell -> cell.getValue().progressFormattedProperty());
    }

    private void configureRowsAndKeyboard() {
        booksTableView.setRowFactory(tv -> {
            TableRow<BookViewModel> row = new TableRow<>() {
                @Override protected void updateItem(BookViewModel item, boolean empty) {
                    super.updateItem(item, empty);
                    getStyleClass().removeAll("author-series-row", "author-book-local-row");
                    if (!empty && item != null && item.isGroupHeader()) getStyleClass().add("author-series-row");
                    else if (!empty && item != null && item.isLocal()) getStyleClass().add("author-book-local-row");
                }
            };
            row.setOnMouseClicked(event -> {
                BookViewModel item = row.getItem();
                if (item == null) return;
                if (item.isGroupHeader() && event.getClickCount() == 1 && !isCheckBoxTarget(event.getTarget())) {
                    toggleSeries(item.getSeries());
                    event.consume();
                } else if (!item.isGroupHeader() && event.getClickCount() == 2) {
                    onOpenBook();
                }
            });
            return row;
        });
        booksTableView.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null && !selected.isGroupHeader()) appState.getBookDetails().setCurrentBook(bookViewModelMapper.toDto(selected));
            else appState.getBookDetails().setCurrentBook(null);
            updateSelectionStatus();
        });
        booksTableView.addEventFilter(KeyEvent.KEY_PRESSED, this::handleTableKeyPressed);
    }

    private boolean isCheckBoxTarget(Object target) {
        Node node = target instanceof Node n ? n : null;
        while (node != null) {
            if (node instanceof CheckBox) return true;
            node = node.getParent();
        }
        return false;
    }

    private void handleTableKeyPressed(KeyEvent event) {
        BookViewModel current = booksTableView.getSelectionModel().getSelectedItem();
        if (event.getCode() == KeyCode.SPACE && current != null && !current.isGroupHeader() && current.getId() != null) {
            current.setSelected(!current.isSelected());
            booksTableView.refresh();
            updateSelectionStatus();
            event.consume();
        } else if (event.isControlDown() && event.getCode() == KeyCode.A) {
            bookSelectionService.setSelected(visibleConcreteBooks(), true);
            booksTableView.refresh();
            updateSelectionStatus();
            event.consume();
        } else if (event.getCode() == KeyCode.LEFT) {
            String series = current == null ? null : normalizeSeries(current.getSeries());
            if (series != null) { collapsedSeries.add(series); rebuildVisibleRows(); event.consume(); }
        } else if (event.getCode() == KeyCode.RIGHT) {
            String series = current == null ? null : normalizeSeries(current.getSeries());
            if (series != null) { collapsedSeries.remove(series); rebuildVisibleRows(); event.consume(); }
        }
    }

    private void configureFilters() {
        localFilterComboBox.getItems().setAll("Усі", "Завантажені", "Не завантажені");
        localFilterComboBox.setValue("Усі");
        localFilterComboBox.setOnAction(event -> rebuildVisibleRows());

        filterDebounce = new PauseTransition(Duration.millis(300));
        filterDebounce.setOnFinished(event -> reloadBooks());
        filterTextField.textProperty().addListener((obs, old, value) -> filterDebounce.playFromStart());
    }

    private void configureProfilePersistence() {
        profileSaveDelay.setOnFinished(event -> {
            if (!applyingProfile) tableProfileService.save(PROFILE_KEY, booksTableView, profileColumns);
        });
        applyingProfile = true;
        try {
            tableProfileService.apply(PROFILE_KEY, booksTableView, profileColumns);
        } finally {
            applyingProfile = false;
        }
        for (TableColumn<BookViewModel, ?> column : profileColumns.values()) {
            column.widthProperty().addListener((o, a, b) -> scheduleProfileSave());
            column.visibleProperty().addListener((o, a, b) -> scheduleProfileSave());
        }
        booksTableView.getColumns().addListener((ListChangeListener<TableColumn<BookViewModel, ?>>) c -> scheduleProfileSave());
        restoreSort();
    }

    private void registerProfileColumn(String id, TableColumn<BookViewModel, ?> column) {
        column.setId(id);
        profileColumns.put(id, column);
    }

    private void scheduleProfileSave() {
        if (!applyingProfile) profileSaveDelay.playFromStart();
    }

    private void restoreSort() {
        try { currentSort = SortBy.valueOf(preferences.get(SORT_KEY, SortBy.SERIES.name())); }
        catch (RuntimeException ignored) { currentSort = SortBy.SERIES; }
        try { currentDirection = SortDirection.valueOf(preferences.get(SORT_DIRECTION_KEY, SortDirection.ASC.name())); }
        catch (RuntimeException ignored) { currentDirection = SortDirection.ASC; }
    }

    private void saveSort() {
        preferences.put(SORT_KEY, currentSort.name());
        preferences.put(SORT_DIRECTION_KEY, currentDirection.name());
    }

    public void setDownloadedOnly(boolean downloadedOnly) {
        this.downloadedOnly = downloadedOnly;
        if (localFilterComboBox != null) {
            localFilterComboBox.setValue(downloadedOnly ? "Завантажені" : "Усі");
            localFilterComboBox.setDisable(downloadedOnly);
        }
    }

    public void setAuthorId(AuthorId authorId) {
        if (authorId == null) throw new IllegalArgumentException("AuthorId не може бути null");
        currentAuthorId = authorId;
        collapsedSeries.clear();
        bookSelectionService.clear();
        if (filterTextField != null) filterTextField.clear();
        loadAuthorData(authorId);
    }

    private void loadAuthorData(AuthorId authorId) {
        long generation = metadataGeneration.incrementAndGet();
        setBusy(true);
        executor.submit(() -> {
            AuthorDto author = loadAuthorByIdUseCase.execute(authorId)
                    .orElseThrow(() -> new IllegalStateException("Автор не знайдений: " + authorId));
            AuthorBookStatistics statistics = loadAuthorBookStatisticsUseCase.execute(authorId);
            boolean followed = catalogUpdateService.isAuthorFollowed(authorId);
            return new AuthorWorkspaceMetadata(author, statistics, followed);
        }).thenAccept(data -> UiExecutor.runOnUiThread(() -> {
            if (generation != metadataGeneration.get() || !authorId.equals(currentAuthorId)) return;
            currentAuthor = data.author();
            authorStatistics = data.statistics();
            currentAuthorFollowed = data.followed();
            updateAuthorUI(data.author());
            updateFollowButton();
            reloadBooks();
        })).exceptionally(ex -> {
            log.error("Помилка завантаження автора {}", authorId, ex);
            UiExecutor.runOnUiThread(() -> {
                if (generation == metadataGeneration.get()) {
                    allBooks.clear();
                    booksTableView.getItems().clear();
                    authorStatistics = AuthorBookStatistics.empty();
                    updateStatisticsLabels(0);
                    setBusy(false);
                }
            });
            return null;
        });
    }

    private void reloadBooks() {
        AuthorId authorId = currentAuthorId;
        if (authorId == null) return;
        String filter = filterTextField == null ? "" : filterTextField.getText();
        SortBy sort = currentSort;
        SortDirection direction = currentDirection;
        long generation = booksGeneration.incrementAndGet();
        setBusy(true);

        executor.submit(() -> {
            List<BookListItem> items = loadBooksByAuthorUseCase.executeAll(authorId, filter, sort, direction);
            Set<String> physicallyLocal = new LinkedHashSet<>();
            for (BookListItem item : items) {
                if (resolveBookLocalAvailabilityUseCase.execute(item)) physicallyLocal.add(item.getId());
            }
            return new LoadedBooks(items, physicallyLocal);
        }).thenAccept(loaded -> UiExecutor.runOnUiThread(() -> {
            if (generation != booksGeneration.get() || !authorId.equals(currentAuthorId)) return;
            allBooks.clear();
            for (BookListItem item : loaded.items()) {
                BookViewModel row = bookViewModelMapper.toViewModel(item);
                row.setLocal(loaded.physicallyLocalIds().contains(item.getId()));
                allBooks.add(row);
            }
            booksTableView.getSelectionModel().clearSelection();
            appState.getBookDetails().setCurrentBook(null);
            rebuildVisibleRows();
            setBusy(false);
        })).exceptionally(ex -> {
            log.error("Помилка завантаження книг автора {}", authorId, ex);
            UiExecutor.runOnUiThread(() -> { if (generation == booksGeneration.get()) setBusy(false); });
            return null;
        });
    }

    private void rebuildVisibleRows() {
        if (booksTableView == null) return;
        List<BookViewModel> filtered = filteredByLocalState();
        List<BookViewModel> rows = new ArrayList<>(filtered.size() + 16);
        if (currentSort == SortBy.SERIES) {
            String previousSeriesKey = null;
            for (BookViewModel book : filtered) {
                String seriesKey = normalizeSeries(book.getSeries());
                if (seriesKey != null && !seriesKey.equals(previousSeriesKey)) {
                    String displaySeries = book.getSeries().trim();
                    BookViewModel header = new BookViewModel();
                    header.setSeries(displaySeries);
                    header.setTitle("Серія: " + displaySeries);
                    header.setGroupHeader(true);
                    rows.add(header);
                }
                if (seriesKey == null || !collapsedSeries.contains(seriesKey)) rows.add(book);
                previousSeriesKey = seriesKey;
            }
        } else {
            rows.addAll(filtered);
        }
        booksTableView.getItems().setAll(rows);
        booksTableView.refresh();
        updateStatisticsLabels(filtered.size());
        updateSelectionStatus();
    }

    private List<BookViewModel> filteredByLocalState() {
        String mode = downloadedOnly ? "Завантажені" : (localFilterComboBox == null ? "Усі" : localFilterComboBox.getValue());
        return allBooks.stream().filter(book -> {
            if ("Завантажені".equals(mode)) return book.isLocal();
            if ("Не завантажені".equals(mode)) return !book.isLocal();
            return true;
        }).toList();
    }

    private List<BookViewModel> visibleConcreteBooks() {
        return booksTableView.getItems().stream().filter(row -> row != null && !row.isGroupHeader()).toList();
    }

    private void updateStatisticsLabels(int available) {
        long total = authorStatistics.books();
        if (downloadedOnly) booksCountLabel.setText("Завантажено: " + available + " / " + total);
        else booksCountLabel.setText(available == total ? "Книг: " + available : "Книг: " + available + " / " + total);
        seriesCountLabel.setText("Серій: " + authorStatistics.series());
        genresCountLabel.setText("Жанрів: " + authorStatistics.genres());
    }

    private void updateSelectionStatus() {
        if (booksTableView == null) return;
        BookViewModel current = booksTableView.getSelectionModel().getSelectedItem();
        String currentTitle = current == null || current.isGroupHeader() || current.getTitle() == null || current.getTitle().isBlank()
                ? "—" : current.getTitle();
        if (currentBookLabel != null) currentBookLabel.setText("Поточна: " + currentTitle);
        if (batchSelectionLabel != null) batchSelectionLabel.setText("Пакетно вибрано: " + bookSelectionService.count());
        updateMasterSelectionState();
    }

    private void updateMasterSelectionState() {
        if (masterSelectionCheckBox == null) return;
        List<BookViewModel> visible = visibleConcreteBooks();
        BookSelectionService.SelectionState state = bookSelectionService.state(visible);
        updatingMasterSelection = true;
        try {
            masterSelectionCheckBox.setDisable(visible.isEmpty());
            masterSelectionCheckBox.setIndeterminate(state == BookSelectionService.SelectionState.PARTIAL);
            masterSelectionCheckBox.setSelected(state == BookSelectionService.SelectionState.ALL);
            masterSelectionCheckBox.setTooltip(new Tooltip(state == BookSelectionService.SelectionState.ALL
                    ? "Зняти вибір з усіх видимих книг" : "Виділити всі видимі книги"));
        } finally {
            updatingMasterSelection = false;
        }
    }

    private BookSelectionService.SelectionState seriesSelectionState(String series) {
        return bookSelectionService.state(booksInSeries(series));
    }

    private void setSeriesSelected(String series, boolean selected) {
        bookSelectionService.setSelected(booksInSeries(series), selected);
    }

    private List<BookViewModel> booksInSeries(String series) {
        String normalized = normalizeSeries(series);
        if (normalized == null) return List.of();
        return allBooks.stream().filter(book -> normalized.equals(normalizeSeries(book.getSeries()))).toList();
    }

    private void toggleSeries(String series) {
        String normalized = normalizeSeries(series);
        if (normalized == null) return;
        if (!collapsedSeries.add(normalized)) collapsedSeries.remove(normalized);
        rebuildVisibleRows();
    }

    private String normalizeSeries(String series) {
        if (series == null || series.isBlank()) return null;
        return series.trim().toLowerCase(Locale.ROOT);
    }

    private void setBusy(boolean busy) {
        if (loadingLabel != null) {
            loadingLabel.setVisible(busy);
            loadingLabel.setManaged(busy);
        }
        if (booksTableView != null) booksTableView.setDisable(busy);
    }

    private void updateAuthorUI(AuthorDto author) {
        authorNameLabel.setText(author.getFullName());
        bioLabel.setText(author.getAnnotation() == null ? "" : author.getAnnotation());
    }

    private void updateFollowButton() {
        if (followAuthorButton == null) return;
        followAuthorButton.setText(currentAuthorFollowed ? "Не стежити" : "Стежити за автором");
        followAuthorButton.setDisable(currentAuthorId == null);
    }

    @FXML
    private void onToggleAuthorFollowed() {
        AuthorId authorId = currentAuthorId;
        if (authorId == null || followAuthorButton == null) return;
        boolean target = !currentAuthorFollowed;
        followAuthorButton.setDisable(true);
        executor.submit(() -> {
            catalogUpdateService.setAuthorFollowed(authorId, target);
            return target;
        }).thenAccept(followed -> UiExecutor.runOnUiThread(() -> {
            if (!authorId.equals(currentAuthorId)) return;
            currentAuthorFollowed = followed;
            updateFollowButton();
        })).exceptionally(ex -> {
            log.error("Не вдалося змінити стеження за автором {}", authorId, ex);
            UiExecutor.runOnUiThread(this::updateFollowButton);
            return null;
        });
    }

    @FXML private void onSortBySeries() { applySort(SortBy.SERIES, SortDirection.ASC); }
    @FXML private void onSortByTitle() { applySort(SortBy.TITLE, SortDirection.ASC); }
    @FXML private void onSortByYear() { applySort(SortBy.YEAR, SortDirection.DESC); }
    @FXML private void onSortByRating() { applySort(SortBy.RATING, SortDirection.DESC); }

    private void applySort(SortBy sort, SortDirection direction) {
        currentSort = sort;
        currentDirection = direction;
        saveSort();
        reloadBooks();
    }

    @FXML private void onCollapseAll() {
        collapsedSeries.clear();
        allBooks.stream().map(BookViewModel::getSeries).map(this::normalizeSeries).filter(java.util.Objects::nonNull)
                .forEach(collapsedSeries::add);
        ensureSeriesGroupingAndRefresh();
    }

    @FXML private void onExpandAll() {
        collapsedSeries.clear();
        ensureSeriesGroupingAndRefresh();
    }

    private void ensureSeriesGroupingAndRefresh() {
        if (currentSort != SortBy.SERIES) {
            currentSort = SortBy.SERIES;
            currentDirection = SortDirection.ASC;
            saveSort();
            reloadBooks();
            return;
        }
        rebuildVisibleRows();
    }

    public void refreshData() {
        reloadBooks();
    }

    public void showColumnChooser() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Колонки Author Workspace");
        dialog.setHeaderText("Виберіть колонки для відображення");
        VBox box = new VBox(8);
        box.setPadding(new javafx.geometry.Insets(12));
        for (TableColumn<BookViewModel, ?> column : profileColumns.values()) {
            CheckBox checkBox = new CheckBox(column.getText());
            checkBox.setSelected(column.isVisible());
            checkBox.selectedProperty().addListener((obs, oldValue, visible) -> column.setVisible(visible));
            box.getChildren().add(checkBox);
        }
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    @FXML
    private void onOpenBook() {
        BookViewModel selected = booksTableView.getSelectionModel().getSelectedItem();
        if (selected != null && !selected.isGroupHeader() && selected.getId() != null) {
            navigationService.navigateToBook(BookId.fromString(selected.getId()));
        }
    }

    @FXML
    private void onDownloadBook() {
        List<BookId> ids = bookSelectionService.snapshot();
        if (ids.isEmpty()) {
            appState.getStatusBar().setStatusText("Відмітьте книги checkbox для пакетного завантаження");
            return;
        }
        javafx.stage.Window owner = booksTableView.getScene() == null ? null : booksTableView.getScene().getWindow();
        bookDownloadCoordinator.downloadBatch(ids, owner)
                .whenComplete((result, error) -> UiExecutor.runOnUiThread(() -> {
                    if (error == null) bookSelectionService.clear();
                    reloadBooks();
                }));
    }

    @FXML
    private void onExportBooks() {
        exportController.handleExport(booksTableView.getScene() == null ? null : booksTableView.getScene().getWindow());
    }

    @FXML
    private void onReadBook() {
        BookViewModel selected = booksTableView.getSelectionModel().getSelectedItem();
        if (selected != null && !selected.isGroupHeader() && selected.getId() != null) {
            BookDto book = bookViewModelMapper.toDto(selected);
            navigationService.readBook(book);
        }
    }

    @FXML
    private void onEditAuthorDescription() {
        if (currentAuthorId == null || currentAuthor == null) return;
        TextArea area = new TextArea(currentAuthor.getAnnotation() == null ? "" : currentAuthor.getAnnotation());
        area.setWrapText(true);
        area.setPrefRowCount(14);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Опис автора");
        dialog.setHeaderText(currentAuthor.getFullName());
        dialog.getDialogPane().setContent(area);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            updateAuthorDescriptionUseCase.execute(currentAuthorId, area.getText());
            currentAuthor.setAnnotation(area.getText());
            bioLabel.setText(area.getText());
        }
    }

    private record AuthorWorkspaceMetadata(AuthorDto author, AuthorBookStatistics statistics, boolean followed) { }
    private record LoadedBooks(List<BookListItem> items, Set<String> physicallyLocalIds) { }
}
