package com.myhomelibcorp.ui.table;

import com.myhomelibcorp.ui.service.DialogLocalization;
import com.myhomelibcorp.ui.util.UiExceptionSupport;

import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.application.usecase.book.MarkAsReadBatchUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.controller.ExportController;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.service.BookSelectionService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.TreeItemPropertyValueFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class TreeBookTableController {

    private final ApplicationState appState;
    private final NavigationService navigationService;
    private final ExportController exportController;
    private final MarkAsReadBatchUseCase markAsReadBatchUseCase;
    private final BookSelectionService bookSelectionService;

    @FXML private TreeTableView<BookViewModel> treeTableView;
    @FXML private TreeTableColumn<BookViewModel, String> titleColumn;
    @FXML private TreeTableColumn<BookViewModel, String> authorColumn;
    @FXML private TreeTableColumn<BookViewModel, String> seriesColumn;
    @FXML private TreeTableColumn<BookViewModel, String> genresColumn;
    @FXML private TreeTableColumn<BookViewModel, String> fileSizeColumn;
    @FXML private TreeTableColumn<BookViewModel, String> rateColumn;
    @FXML private TreeTableColumn<BookViewModel, String> progressColumn;
    @FXML private TreeTableColumn<BookViewModel, String> dateColumn;

    private TreeTableColumn<BookViewModel, Boolean> selectColumn;

    @FXML
    public void initialize() {
        log.info("TreeBookTableController.initialize()");

        createSelectColumn();
        setupColumns();
        setupSelectionListener();
        setupDoubleClickHandler();
        loadBooks();

        log.info("TreeBookTableController ініціалізовано");
    }

    /**
     * Створює колонку з чекбоксами
     */
    private void createSelectColumn() {
        log.debug("Створення колонки з чекбоксами");

        selectColumn = new TreeTableColumn<>("☑");
        selectColumn.setPrefWidth(70);
        selectColumn.setMaxWidth(100);
        selectColumn.setMinWidth(70);
        selectColumn.setResizable(false);
        selectColumn.setEditable(true);
        selectColumn.setStyle("-fx-alignment: CENTER; -fx-padding: 0;");

        selectColumn.setCellValueFactory(param -> {
            TreeItem<BookViewModel> treeItem = param.getValue();
            if (treeItem == null || treeItem.getValue() == null) {
                return new SimpleBooleanProperty(false);
            }
            return treeItem.getValue().selectedProperty();
        });

        selectColumn.setCellFactory(param -> createSelectionCell());

        treeTableView.getColumns().add(0, selectColumn);
        treeTableView.setEditable(true);
        log.debug("Колонку з чекбоксами додано");
    }

    private TreeTableCell<BookViewModel, Boolean> createSelectionCell() {
        return new TreeTableCell<BookViewModel, Boolean>() {
            private final CheckBox checkBox = new CheckBox();
            private BookViewModel boundBook;
            private final javafx.beans.value.ChangeListener<Boolean> rowListener =
                    (obs, oldValue, newValue) -> checkBox.setSelected(Boolean.TRUE.equals(newValue));

            {
                checkBox.setOnAction(event -> {
                    if (boundBook != null && !boundBook.isGroupHeader()) {
                        boundBook.setSelected(checkBox.isSelected());
                    }
                });
            }

            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                if (boundBook != null) {
                    boundBook.selectedProperty().removeListener(rowListener);
                    boundBook = null;
                }
                TreeTableRow<BookViewModel> tableRow = getTableRow();
                TreeItem<BookViewModel> treeItem = empty || tableRow == null
                        ? null : tableRow.getTreeItem();
                BookViewModel book = treeItem == null ? null : treeItem.getValue();
                if (book == null || book.isGroupHeader() || book.getId() == null || book.getId().isBlank()) {
                    setGraphic(null);
                    return;
                }
                boundBook = book;
                checkBox.setSelected(book.isSelected());
                book.selectedProperty().addListener(rowListener);
                setAlignment(Pos.CENTER);
                setGraphic(checkBox);
            }
        };
    }

    /**
     * Налаштовує інші колонки
     */
    private void setupColumns() {
        log.debug("Налаштування колонок");

        titleColumn.setCellValueFactory(new TreeItemPropertyValueFactory<>("title"));
        titleColumn.setPrefWidth(250);

        authorColumn.setCellValueFactory(cellData ->
                cellData.getValue().getValue().authorsTextProperty());
        authorColumn.setPrefWidth(150);

        seriesColumn.setCellValueFactory(cellData ->
                cellData.getValue().getValue().seriesProperty());
        seriesColumn.setPrefWidth(120);

        genresColumn.setCellValueFactory(cellData ->
                cellData.getValue().getValue().genresTextProperty());
        genresColumn.setPrefWidth(100);

        fileSizeColumn.setCellValueFactory(cellData ->
                cellData.getValue().getValue().fileSizeFormattedProperty());
        fileSizeColumn.setPrefWidth(90);

        rateColumn.setCellValueFactory(cellData ->
                cellData.getValue().getValue().rateStarsProperty());
        rateColumn.setPrefWidth(80);

        progressColumn.setCellValueFactory(cellData ->
                cellData.getValue().getValue().progressFormattedProperty());
        progressColumn.setPrefWidth(80);

        dateColumn.setCellValueFactory(cellData ->
                cellData.getValue().getValue().createdAtFormattedProperty());
        dateColumn.setPrefWidth(100);

        treeTableView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
    }

    /**
     * Налаштовує слухач вибору
     */
    private void setupSelectionListener() {
        treeTableView.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null && selected.getValue() != null) {
                appState.getBookTable().setSelectedBook(selected.getValue());
            }
        });
    }

    /**
     * Налаштовує подвійний клік для відкриття книги
     */
    private void setupDoubleClickHandler() {
        treeTableView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                TreeItem<BookViewModel> selected = treeTableView.getSelectionModel().getSelectedItem();
                if (selected != null && selected.getValue() != null) {
                    navigationService.navigateToBook(BookId.fromString(selected.getValue().getId()));
                }
            }
        });
    }

    /**
     * Завантажує книги та будує дерево
     */
    public void loadBooks() {
        // Tree mode is an alternate visualization of the already server-paged main table.
        // Never run a second 10k/all-books query merely to switch view mode.
        List<BookViewModel> page = List.copyOf(appState.getBookTable().getBooks());
        log.info("📚 Побудова дерева для поточної сторінки: {} книг", page.size());

        if (page.isEmpty()) {
            treeTableView.setRoot(new TreeItem<>(null));
            treeTableView.setShowRoot(false);
            return;
        }

        Map<String, Map<String, List<BookViewModel>>> grouped = page.stream()
                .filter(book -> book != null && book.getId() != null && !book.getId().isBlank())
                .collect(Collectors.groupingBy(
                        book -> blankToDefault(book.getAuthorsText(), UiText.text("ui.java.tree_book_table_controller.0109ddee82")),
                        java.util.LinkedHashMap::new,
                        Collectors.groupingBy(
                                book -> blankToDefault(book.getSeries(), UiText.text("ui.java.tree_book_table_controller.7a66622005")),
                                java.util.LinkedHashMap::new,
                                Collectors.toList())));

        TreeItem<BookViewModel> root = new TreeItem<>(null);
        root.setExpanded(true);

        grouped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .forEach(authorEntry -> {
                    TreeItem<BookViewModel> authorItem = new TreeItem<>(header(authorEntry.getKey()));
                    authorItem.setExpanded(true);

                    authorEntry.getValue().entrySet().stream()
                            .filter(entry -> !UiText.text("ui.java.tree_book_table_controller.7a66622005").equals(entry.getKey()))
                            .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                            .forEach(entry -> {
                                TreeItem<BookViewModel> seriesItem = new TreeItem<>(header(entry.getKey()));
                                seriesItem.setExpanded(true);
                                entry.getValue().stream()
                                        .sorted(Comparator
                                                .comparingInt((BookViewModel b) -> b.getSequenceNumber() <= 0 ? Integer.MAX_VALUE : b.getSequenceNumber())
                                                .thenComparing(BookViewModel::getTitle, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                                        .forEach(book -> seriesItem.getChildren().add(new TreeItem<>(book)));
                                authorItem.getChildren().add(seriesItem);
                            });

                    authorEntry.getValue().getOrDefault(UiText.text("ui.java.tree_book_table_controller.7a66622005"), List.of()).stream()
                            .sorted(Comparator.comparing(BookViewModel::getTitle, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                            .forEach(book -> authorItem.getChildren().add(new TreeItem<>(book)));
                    root.getChildren().add(authorItem);
                });

        treeTableView.setRoot(root);
        treeTableView.setShowRoot(false);
    }

    private static BookViewModel header(String title) {
        BookViewModel vm = new BookViewModel();
        vm.setTitle(title);
        vm.setGroupHeader(true);
        return vm;
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public void refresh() {
        loadBooks();
    }

    public void clear() {
        treeTableView.setRoot(null);
    }

    /**
     * Повертає вибрані книги
     */
    public List<BookViewModel> getSelectedBooks() {
        java.util.Set<String> selectedIds = bookSelectionService.snapshot().stream()
                .map(BookId::asString).collect(java.util.stream.Collectors.toSet());
        if (selectedIds.isEmpty()) return List.of();
        List<BookViewModel> visible = new ArrayList<>();
        collectSelectedBooks(treeTableView.getRoot(), selectedIds, visible);
        return visible;
    }

    private void collectSelectedBooks(TreeItem<BookViewModel> item, java.util.Set<String> selectedIds,
                                      List<BookViewModel> selected) {
        if (item == null) return;
        BookViewModel book = item.getValue();
        if (book != null && !book.isGroupHeader() && book.getId() != null && selectedIds.contains(book.getId())) {
            selected.add(book);
        }
        for (TreeItem<BookViewModel> child : item.getChildren()) collectSelectedBooks(child, selectedIds, selected);
    }

    private void selectAllBooks(boolean selected) {
        List<BookViewModel> books = new ArrayList<>();
        collectConcreteBooks(treeTableView.getRoot(), books);
        bookSelectionService.setSelected(books, selected);
        log.info("{} всі книги в дереві", selected ? "✅ Вибрано" : "❌ Знято вибір з");
        treeTableView.refresh();
    }

    private void collectConcreteBooks(TreeItem<BookViewModel> item, List<BookViewModel> books) {
        if (item == null) return;
        BookViewModel book = item.getValue();
        if (book != null && !book.isGroupHeader() && book.getId() != null && !book.getId().isBlank()) books.add(book);
        for (TreeItem<BookViewModel> child : item.getChildren()) collectConcreteBooks(child, books);
    }

    // ===== FXML МЕТОДИ =====

    @FXML
    public void selectAll() {
        selectAllBooks(true);
        showAlert(UiText.text("ui.java.tree_book_table_controller.455cfc2d2f"), UiText.text("ui.java.tree_book_table_controller.67bae0ef3c") + getSelectedBooks().size() + UiText.text("ui.java.tree_book_table_controller.de7370facb"));
    }

    @FXML
    public void deselectAll() {
        selectAllBooks(false);
        showAlert(UiText.text("ui.java.tree_book_table_controller.455cfc2d2f"), UiText.text("ui.java.tree_book_table_controller.e8a2f775f3"));
    }

    @FXML
    public void exportSelected() {
        exportController.handleExport(treeTableView.getScene() == null ? null : treeTableView.getScene().getWindow(), this::refresh);
    }

    @FXML
    public void markSelectedAsRead() {
        List<BookId> ids = bookSelectionService.snapshot();
        if (ids.isEmpty()) {
            showAlert(UiText.text("ui.java.tree_book_table_controller.ac1e0c3aad"), UiText.text("ui.java.tree_book_table_controller.37febfe5aa"));
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        DialogLocalization.prepare(confirm);
        confirm.setTitle(UiText.text("ui.java.tree_book_table_controller.fe2c585c28"));
        confirm.setHeaderText(UiText.text("ui.java.tree_book_table_controller.3ea918ac24") + ids.size() + UiText.text("ui.java.tree_book_table_controller.bd36c446a8"));
        confirm.setContentText(UiText.text("ui.java.tree_book_table_controller.58e7a89a74"));
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        try {
            markAsReadBatchUseCase.execute(ids);
            getSelectedBooks().forEach(book -> book.setProgress(100));
            bookSelectionService.clear();
            treeTableView.refresh();
            showAlert(UiText.text("ui.java.tree_book_table_controller.398c7d4f7b"), ids.size() + UiText.text("ui.java.tree_book_table_controller.d6cb37f8ba"));
        } catch (Exception e) {
            log.error("Не вдалося позначити книги як прочитані", e);
            Alert error = new Alert(Alert.AlertType.ERROR);
            DialogLocalization.prepare(error);
            error.setTitle(UiText.text("common.error")); error.setHeaderText(null); error.setContentText(UiExceptionSupport.message(e)); error.showAndWait();
        }
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        DialogLocalization.prepare(alert);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

}
