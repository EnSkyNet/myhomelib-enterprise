package com.myhomelibcorp.ui.table;

import com.myhomelibcorp.application.filter.BookFilterSpec;
import com.myhomelibcorp.application.filter.BookFilterStateService;
import com.myhomelibcorp.application.filter.BookQuickFilterField;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.SortBy;
import com.myhomelibcorp.application.query.common.SortDirection;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.action.BookActionUiService;
import com.myhomelibcorp.ui.filter.BookFilterDialogService;
import com.myhomelibcorp.ui.navigation.NavigationPanelController;
import com.myhomelibcorp.ui.service.BookLoaderService;
import com.myhomelibcorp.ui.service.BookSelectionService;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookTableViewModel;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.animation.PauseTransition;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import javafx.util.StringConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookTableController {

    private final ApplicationState appState;
    private final NavigationService navigationService;
    private final BookLoaderService bookLoaderService;
    private final BookSelectionService bookSelectionService;
    private final BookFilterStateService filterStateService;
    private final BookFilterDialogService filterDialogService;
    private final TableProfileService tableProfileService;
    private final LocalizationService i18n;
    private final NavigationPanelController navigationPanelController;
    private final BookActionUiService bookActionUiService;

    @FXML private TableView<BookViewModel> bookTableView;
    @FXML private TableColumn<BookViewModel, String> titleColumn;
    @FXML private TableColumn<BookViewModel, String> authorColumn;
    @FXML private TableColumn<BookViewModel, String> seriesColumn;
    @FXML private TableColumn<BookViewModel, String> genresColumn;
    @FXML private TableColumn<BookViewModel, String> fileSizeColumn;
    @FXML private TableColumn<BookViewModel, String> rateColumn;
    @FXML private TableColumn<BookViewModel, String> progressColumn;
    @FXML private TableColumn<BookViewModel, String> dateColumn;

    @FXML private Label filterIndicatorLabel;
    @FXML private ComboBox<BookQuickFilterField> quickFilterColumnComboBox;
    @FXML private TextField quickFilterValueField;
    @FXML private Label pageInfoLabel;
    @FXML private Label currentBookLabel;
    @FXML private Label batchSelectionLabel;
    @FXML private Button prevPageButton;
    @FXML private Button nextPageButton;
    @FXML private ComboBox<Integer> pageSizeComboBox;

    private final LinkedHashMap<String, TableColumn<BookViewModel, ?>> profileColumns = new LinkedHashMap<>();
    private final PauseTransition profileSaveDelay = new PauseTransition(Duration.millis(400));
    private String profileKey = "default";
    private boolean profileConfigured;
    private boolean applyingProfile;
    private CheckBox masterSelectionCheckBox;
    private boolean updatingMasterSelection;

    @FXML
    public void initialize() {
        appState.setBookTableController(this);
        log.info("BookTableController зареєстровано в ApplicationState.");

        BookTableViewModel vm = appState.getBookTable();

        TableColumn<BookViewModel, Boolean> selectCol = new TableColumn<>();
        selectCol.setId("select");
        masterSelectionCheckBox = new CheckBox();
        masterSelectionCheckBox.setAllowIndeterminate(true);
        masterSelectionCheckBox.setTooltip(new Tooltip("Виділити всі видимі книги"));
        masterSelectionCheckBox.setOnAction(event -> {
            if (updatingMasterSelection) return;
            BookSelectionService.SelectionState before = bookSelectionService.state(bookTableView.getItems());
            // PARTIAL -> ALL; only ALL -> NONE. Never depend on JavaFX's indeterminate cycle.
            bookSelectionService.setSelected(bookTableView.getItems(), before != BookSelectionService.SelectionState.ALL);
            bookTableView.refresh();
            updateSelectionStatus();
        });
        selectCol.setGraphic(masterSelectionCheckBox);
        selectCol.setCellValueFactory(cellData -> cellData.getValue().selectedProperty());
        selectCol.setCellFactory(col -> new TableCell<>() {
            private final CheckBox checkBox = new CheckBox();
            private BookViewModel boundRow;
            private final javafx.beans.value.ChangeListener<Boolean> selectedListener =
                    (obs, oldValue, newValue) -> checkBox.setSelected(Boolean.TRUE.equals(newValue));
            {
                checkBox.setOnAction(event -> {
                    BookViewModel row = boundRow;
                    if (row != null && !row.isGroupHeader()) row.setSelected(checkBox.isSelected());
                });
            }
            @Override protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                if (boundRow != null) {
                    boundRow.selectedProperty().removeListener(selectedListener);
                    boundRow = null;
                }
                BookViewModel row = getTableRow() == null ? null : getTableRow().getItem();
                if (empty || row == null || row.isGroupHeader()) {
                    setGraphic(null);
                    return;
                }
                boundRow = row;
                checkBox.setSelected(row.isSelected());
                row.selectedProperty().addListener(selectedListener);
                setGraphic(checkBox);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            }
        });
        selectCol.setEditable(true);
        selectCol.setPrefWidth(40);
        selectCol.setResizable(false);
        selectCol.setSortable(false);
        selectCol.setStyle("-fx-alignment: CENTER;");
        bookTableView.getColumns().add(0, selectCol);
        bookTableView.setEditable(true);

        registerProfileColumn("title", titleColumn);
        registerProfileColumn("author", authorColumn);
        registerProfileColumn("series", seriesColumn);
        registerProfileColumn("genres", genresColumn);
        registerProfileColumn("fileSize", fileSizeColumn);
        registerProfileColumn("rating", rateColumn);
        registerProfileColumn("progress", progressColumn);
        registerProfileColumn("date", dateColumn);

        titleColumn.setCellValueFactory(cellData -> cellData.getValue().titleProperty());
        titleColumn.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); return; }
                BookViewModel row = getTableRow() != null ? getTableRow().getItem() : null;
                setText(row != null && !row.isGroupHeader() && row.getSeries() != null && !row.getSeries().isBlank()
                        ? "    " + item : item);
            }
        });
        authorColumn.setCellValueFactory(cellData -> cellData.getValue().authorsTextProperty());
        seriesColumn.setCellValueFactory(cellData -> cellData.getValue().seriesProperty());
        genresColumn.setCellValueFactory(cellData -> cellData.getValue().genresTextProperty());
        fileSizeColumn.setCellValueFactory(cellData -> cellData.getValue().fileSizeFormattedProperty());
        rateColumn.setCellValueFactory(cellData -> cellData.getValue().rateStarsProperty());
        progressColumn.setCellValueFactory(cellData -> cellData.getValue().progressFormattedProperty());
        dateColumn.setCellValueFactory(cellData -> cellData.getValue().createdAtFormattedProperty());

        // Unsupported SQL sort columns must not silently sort one loaded page in JavaFX.
        genresColumn.setSortable(false);
        fileSizeColumn.setSortable(false);
        progressColumn.setSortable(false);

        bookTableView.setRowFactory(tv -> new TableRow<>() {
            @Override protected void updateItem(BookViewModel item, boolean empty) {
                super.updateItem(item, empty);
                if (!empty && item != null && item.isGroupHeader()) {
                    setStyle("-fx-font-weight: bold; -fx-background-color: -fx-control-inner-background-alt;");
                    setMouseTransparent(true);
                    setContextMenu(null);
                } else {
                    setStyle("");
                    setMouseTransparent(false);
                    setContextMenu(empty || item == null ? null : bookActionUiService.createContextMenu(item));
                }
            }
        });
        bookTableView.setItems(vm.getBooks());
        installSelectionStatusTracking(vm);

        bookTableView.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null && selected.isGroupHeader()) return;
            vm.setSelectedBook(selected);
            if (selected != null) {
                appState.getBookDetails().setCurrentBook(
                        com.myhomelibcorp.application.dto.BookDto.builder()
                                .id(selected.getId()).title(selected.getTitle()).authorsText(selected.getAuthorsText())
                                .series(selected.getSeries()).genresText(selected.getGenresText()).language(selected.getLanguage())
                                .fileName(selected.getFileName()).folder(selected.getFolder()).archiveEntry(selected.getArchiveEntry())
                                .fileSize(selected.getFileSize()).annotation(selected.getAnnotation()).rate(selected.getRate())
                                .progress(selected.getProgress()).local(selected.isLocal()).collectionRoot(selected.getCollectionRoot())
                                .build());
            } else {
                appState.getBookDetails().setCurrentBook(null);
            }
            updateSelectionStatus();
        });

        bookTableView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                BookViewModel selected = bookTableView.getSelectionModel().getSelectedItem();
                if (selected != null && !selected.isGroupHeader() && selected.getId() != null) {
                    navigationService.navigateToBook(BookId.fromString(selected.getId()));
                }
            }
        });

        // A successful custom sort policy means JavaFX must not reorder only the current page.
        // Instead the selected supported column is translated into BookQuery ORDER BY and reloaded from SQL.
        bookTableView.setSortPolicy(table -> {
            if (applyingProfile) return true;
            if (table.getSortOrder().isEmpty()) {
                scheduleProfileSave();
                bookLoaderService.setSort(SortBy.TITLE, SortDirection.ASC);
                return true;
            }
            TableColumn<BookViewModel, ?> column = table.getSortOrder().getFirst();
            SortBy sortBy = sortBy(column);
            if (sortBy == null) return true;
            SortDirection direction = column.getSortType() == TableColumn.SortType.DESCENDING
                    ? SortDirection.DESC : SortDirection.ASC;
            scheduleProfileSave();
            bookLoaderService.setSort(sortBy, direction);
            return true;
        });

        bookTableView.setTableMenuButtonVisible(true);
        setupQuickFilter();
        setupPagination();
        setupProfilePersistence();
        updateFilterIndicator();
    }

    private void installSelectionStatusTracking(BookTableViewModel vm) {
        // BookSelectionService is the sole batch-selection source, so one count listener is enough.
        // Per-row listeners caused Select All on large pages to recalculate master state repeatedly.
        bookSelectionService.selectedCountProperty().addListener((obs, oldValue, newValue) -> updateSelectionStatus());
        vm.getBooks().addListener((ListChangeListener<BookViewModel>) change -> updateSelectionStatus());
        if (currentBookLabel != null) {
            currentBookLabel.setMaxWidth(280);
            currentBookLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        }
        updateSelectionStatus();
    }

    private void updateSelectionStatus() {
        if (bookTableView == null) return;
        BookViewModel current = bookTableView.getSelectionModel().getSelectedItem();
        String currentTitle = current == null || current.isGroupHeader() || current.getTitle() == null || current.getTitle().isBlank()
                ? "—" : current.getTitle();
        long batchCount = bookSelectionService.count();
        if (currentBookLabel != null) {
            currentBookLabel.setText(currentTitle);
            currentBookLabel.setTooltip("—".equals(currentTitle) ? null : new Tooltip(currentTitle));
        }
        if (batchSelectionLabel != null) batchSelectionLabel.setText("Пакетно вибрано: " + batchCount);
        updateMasterSelectionState();
    }

    private void updateMasterSelectionState() {
        if (masterSelectionCheckBox == null || bookTableView == null) return;
        BookSelectionService.SelectionState state = bookSelectionService.state(bookTableView.getItems());
        boolean hasBooks = bookTableView.getItems().stream().anyMatch(row -> row != null && !row.isGroupHeader());
        updatingMasterSelection = true;
        try {
            masterSelectionCheckBox.setDisable(!hasBooks);
            masterSelectionCheckBox.setIndeterminate(state == BookSelectionService.SelectionState.PARTIAL);
            masterSelectionCheckBox.setSelected(state == BookSelectionService.SelectionState.ALL);
            masterSelectionCheckBox.setTooltip(new Tooltip(state == BookSelectionService.SelectionState.ALL
                    ? "Зняти вибір з усіх видимих книг" : "Виділити всі видимі книги"));
        } finally {
            updatingMasterSelection = false;
        }
    }

    private void registerProfileColumn(String id, TableColumn<BookViewModel, ?> column) {
        column.setId(id);
        profileColumns.put(id, column);
    }

    private void setupQuickFilter() {
        quickFilterColumnComboBox.getItems().setAll(BookQuickFilterField.values());
        quickFilterColumnComboBox.setConverter(new StringConverter<>() {
            @Override public String toString(BookQuickFilterField value) { return quickFieldLabel(value); }
            @Override public BookQuickFilterField fromString(String value) {
                if (value == null || value.isBlank()) return BookQuickFilterField.ANY;
                for (BookQuickFilterField field : BookQuickFilterField.values()) {
                    if (quickFieldLabel(field).equalsIgnoreCase(value.trim()) || field.name().equalsIgnoreCase(value.trim())) return field;
                }
                return BookQuickFilterField.ANY;
            }
        });
        BookFilterSpec current = filterStateService.current();
        quickFilterColumnComboBox.setValue(current.quickField());
        quickFilterValueField.setText(current.quickValue() == null ? "" : current.quickValue());
        quickFilterValueField.setOnAction(e -> applyQuickFilter());
    }

    private void setupPagination() {
        BookTableViewModel vm = appState.getBookTable();
        pageSizeComboBox.getItems().addAll(10, 25, 50, 100, 200);
        pageSizeComboBox.setValue(vm.getPageSize());
        pageSizeComboBox.setOnAction(e -> {
            Integer size = pageSizeComboBox.getValue();
            if (size != null) bookLoaderService.setPageSize(size);
        });
        prevPageButton.setOnAction(e -> bookLoaderService.previousPage());
        nextPageButton.setOnAction(e -> bookLoaderService.nextPage());
        vm.currentPageProperty().addListener((obs, old, page) -> updatePaginationState(vm));
        vm.totalPagesProperty().addListener((obs, old, pages) -> updatePaginationState(vm));
        vm.totalElementsProperty().addListener((obs, old, count) -> updateFilterIndicator());
        updatePaginationState(vm);
    }

    private void setupProfilePersistence() {
        profileSaveDelay.setOnFinished(e -> {
            if (profileConfigured && !applyingProfile) tableProfileService.save(profileKey, bookTableView, profileColumns);
        });
        for (TableColumn<BookViewModel, ?> column : profileColumns.values()) {
            column.widthProperty().addListener((o, a, b) -> scheduleProfileSave());
            column.visibleProperty().addListener((o, a, b) -> scheduleProfileSave());
            column.sortTypeProperty().addListener((o, a, b) -> scheduleProfileSave());
        }
        bookTableView.getColumns().addListener((ListChangeListener<TableColumn<BookViewModel, ?>>) c -> scheduleProfileSave());
        bookTableView.getSortOrder().addListener((ListChangeListener<TableColumn<BookViewModel, ?>>) c -> scheduleProfileSave());
    }

    public void setProfileKey(String profileKey) {
        this.profileKey = profileKey == null || profileKey.isBlank() ? "default" : profileKey;
        applyingProfile = true;
        try {
            tableProfileService.apply(this.profileKey, bookTableView, profileColumns);
            profileConfigured = true;
        } finally {
            applyingProfile = false;
        }
    }

    private void scheduleProfileSave() {
        if (!profileConfigured || applyingProfile) return;
        profileSaveDelay.playFromStart();
    }

    public BookQuery applyPreferredSort(BookQuery query) {
        if (query == null || !profileConfigured || bookTableView.getSortOrder().isEmpty()) return query;
        TableColumn<BookViewModel, ?> column = bookTableView.getSortOrder().getFirst();
        SortBy sortBy = sortBy(column);
        if (sortBy == null) return query;
        SortDirection direction = column.getSortType() == TableColumn.SortType.DESCENDING ? SortDirection.DESC : SortDirection.ASC;
        return copyWithSort(query, sortBy, direction);
    }

    private BookQuery copyWithSort(BookQuery q, SortBy sortBy, SortDirection direction) {
        return BookQuery.builder()
                .authorId(q.authorId()).seriesId(q.seriesId()).genreId(q.genreId()).groupId(q.groupId())
                .text(q.text()).keyword(q.keyword()).language(q.language()).format(q.format()).year(q.year())
                .archive(q.archiveCollectionRoot(), q.archivePath()).filterSpec(q.filterSpec())
                .pagination(q.pagination()).sortBy(sortBy).direction(direction)
                .onlyRead(q.onlyRead()).onlyFavorites(q.onlyFavorites()).onlyRated(q.onlyRated()).onlyReviewed(q.onlyReviewed())
                .onlyInHistory(q.onlyInHistory()).withoutSeries(q.withoutSeries()).withCover(q.withCover()).build();
    }

    private SortBy sortBy(TableColumn<BookViewModel, ?> column) {
        if (column == titleColumn) return SortBy.TITLE;
        if (column == authorColumn) return SortBy.AUTHOR;
        if (column == seriesColumn) return SortBy.SERIES;
        if (column == rateColumn) return SortBy.RATING;
        if (column == dateColumn) return SortBy.DATE;
        return null;
    }

    @FXML public void openGlobalFilters() {
        filterDialogService.show(bookTableView.getScene() == null ? null : bookTableView.getScene().getWindow())
                .ifPresent(spec -> {
                    syncQuickControls(spec);
                    updateFilterIndicator();
                    navigationPanelController.refreshForFilterChange();
                    bookLoaderService.reloadLastQuery();
                });
    }

    @FXML public void applyQuickFilter() {
        BookQuickFilterField field = quickFilterColumnComboBox.getValue() == null
                ? BookQuickFilterField.ANY : quickFilterColumnComboBox.getValue();
        BookFilterSpec updated = filterStateService.current().withQuickFilter(field, quickFilterValueField.getText());
        filterStateService.save(updated);
        updateFilterIndicator();
        navigationPanelController.refreshForFilterChange();
        bookLoaderService.reloadLastQuery();
    }

    @FXML public void clearQuickFilter() {
        BookFilterSpec updated = filterStateService.current().withoutQuickFilter();
        filterStateService.save(updated);
        syncQuickControls(updated);
        updateFilterIndicator();
        navigationPanelController.refreshForFilterChange();
        bookLoaderService.reloadLastQuery();
    }

    @FXML public void resetTableProfile() {
        tableProfileService.reset(profileKey);
        applyingProfile = true;
        try {
            resetDefaultColumns();
            bookTableView.getSortOrder().clear();
        } finally {
            applyingProfile = false;
        }
        scheduleProfileSave();
        bookLoaderService.setSort(SortBy.TITLE, SortDirection.ASC);
    }

    private void resetDefaultColumns() {
        titleColumn.setVisible(true); authorColumn.setVisible(true); seriesColumn.setVisible(true); genresColumn.setVisible(true);
        fileSizeColumn.setVisible(true); rateColumn.setVisible(true); progressColumn.setVisible(true); dateColumn.setVisible(true);
        titleColumn.setPrefWidth(250); authorColumn.setPrefWidth(150); seriesColumn.setPrefWidth(100); genresColumn.setPrefWidth(100);
        fileSizeColumn.setPrefWidth(90); rateColumn.setPrefWidth(80); progressColumn.setPrefWidth(80); dateColumn.setPrefWidth(100);
        TableColumn<BookViewModel, ?> select = bookTableView.getColumns().stream().filter(c -> "select".equals(c.getId())).findFirst().orElse(null);
        List<TableColumn<BookViewModel, ?>> ordered = new ArrayList<>();
        if (select != null) ordered.add(select);
        ordered.addAll(profileColumns.values());
        bookTableView.getColumns().setAll(ordered);
    }

    private void syncQuickControls(BookFilterSpec spec) {
        quickFilterColumnComboBox.setValue(spec.quickField());
        quickFilterValueField.setText(spec.quickValue() == null ? "" : spec.quickValue());
    }

    private String quickFieldLabel(BookQuickFilterField field) {
        if (field == null) return "";
        return i18n.tr(switch (field) {
            case ANY -> "Усі поля";
            case TITLE -> "Назва";
            case AUTHOR -> "Автор";
            case SERIES -> "Серія";
            case GENRE -> "Жанр";
            case KEYWORD -> "Ключові слова";
            case PUBLISHER -> "Видавництво";
            case FILE -> "Файл";
        });
    }

    private void updateFilterIndicator() {
        if (filterIndicatorLabel == null) return;
        BookFilterSpec spec = filterStateService.current();
        long total = appState.getBookTable().getTotalElements();
        if (spec.isActive()) {
            filterIndicatorLabel.setText(i18n.tr("Фільтр активний") + " (" + spec.activeCriteriaCount() + ") · " + total);
            filterIndicatorLabel.setVisible(true);
            filterIndicatorLabel.setManaged(true);
        } else {
            filterIndicatorLabel.setText(i18n.tr("Фільтр вимкнено") + " · " + total);
            filterIndicatorLabel.setVisible(true);
            filterIndicatorLabel.setManaged(true);
        }
    }

    private void updatePaginationState(BookTableViewModel vm) {
        int currentPage = vm.getCurrentPage();
        int totalPages = vm.getTotalPages();
        prevPageButton.setDisable(currentPage <= 0);
        nextPageButton.setDisable(currentPage >= totalPages - 1);
        pageInfoLabel.setText(String.format(i18n.tr("Сторінка %d з %d"), currentPage + 1, Math.max(1, totalPages)));
    }

    @FXML
    public void showColumnChooser() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(i18n.tr("Колонки таблиці"));
        dialog.setHeaderText(i18n.tr("Виберіть колонки для відображення"));
        VBox box = new VBox(8); box.setPadding(new javafx.geometry.Insets(12));
        for (TableColumn<BookViewModel, ?> c : profileColumns.values()) {
            CheckBox cb = new CheckBox(c.getText()); cb.setSelected(c.isVisible());
            cb.selectedProperty().addListener((o,a,b) -> c.setVisible(b));
            box.getChildren().add(cb);
        }
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    public void refresh() { bookTableView.refresh(); }

    /**
     * Adds visual series headers without changing the SQL result order. This is critical for
     * page-stable server sorting: books are never re-sorted inside JavaFX after filtering/paging.
     */
    public void loadGroupedBooks(List<BookViewModel> books) {
        if (bookTableView == null) {
            log.error("Спроба завантажити книги в неініціалізовану таблицю!");
            return;
        }
        BookTableViewModel vm = appState.getBookTable();
        List<BookViewModel> groupedBooks = SeriesGrouping.groupPreservingOrder(books);
        vm.setBooks(groupedBooks);
        vm.setSelectedBook(null);
        if (bookTableView.getSelectionModel() != null) bookTableView.getSelectionModel().clearSelection();
        updateSelectionStatus();
        log.info("Таблиця оновлена: {} рядків, {} книг; SQL order preserved", groupedBooks.size(), books.size());
    }
    /** Refreshes row context menus after Stage 15 profile customization. */
    public void refreshRows() {
        if (bookTableView != null) bookTableView.refresh();
    }

}
