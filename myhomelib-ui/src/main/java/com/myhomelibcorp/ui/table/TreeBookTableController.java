package com.myhomelibcorp.ui.table;

import com.myhomelibcorp.application.usecase.book.MarkAsReadBatchUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.controller.ExportController;
import com.myhomelibcorp.ui.service.NavigationService;
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

        selectColumn.setCellFactory(param -> new TreeTableCell<BookViewModel, Boolean>() {
            private final CheckBox checkBox = new CheckBox();

            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || getTreeTableRow() == null) {
                    setGraphic(null);
                    return;
                }

                TreeItem<BookViewModel> treeItem = getTreeTableRow().getTreeItem();
                if (treeItem == null || treeItem.getValue() == null) {
                    setGraphic(null);
                    return;
                }

                BookViewModel book = treeItem.getValue();
                checkBox.setSelected(item != null && item);
                checkBox.setDisable(false);

                checkBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
                    book.setSelected(newVal);
                    log.debug("📚 Книгу '{}' {}", book.getTitle(), newVal ? "✅ ВИБРАНО" : "❌ ЗНЯТО ВИБІР");
                });

                setAlignment(Pos.CENTER);
                setGraphic(checkBox);
            }
        });

        treeTableView.getColumns().add(0, selectColumn);
        treeTableView.setEditable(true);
        log.debug("Колонку з чекбоксами додано");
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
                        book -> blankToDefault(book.getAuthorsText(), "Невідомий Автор"),
                        java.util.LinkedHashMap::new,
                        Collectors.groupingBy(
                                book -> blankToDefault(book.getSeries(), "Без серії"),
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
                            .filter(entry -> !"Без серії".equals(entry.getKey()))
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

                    authorEntry.getValue().getOrDefault("Без серії", List.of()).stream()
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
        List<BookViewModel> selected = new ArrayList<>();
        TreeItem<BookViewModel> root = treeTableView.getRoot();

        log.debug("🔍 Пошук вибраних книг у дереві...");

        if (root != null) {
            collectSelectedBooks(root, selected);
        }

        log.info("📊 Знайдено {} вибраних книг", selected.size());

        if (!selected.isEmpty()) {
            log.info("📋 Список вибраних книг:");
            for (BookViewModel book : selected) {
                log.info("   - {} (ID: {})", book.getTitle(), book.getId());
            }
        }

        return selected;
    }

    private void collectSelectedBooks(TreeItem<BookViewModel> item, List<BookViewModel> selected) {
        BookViewModel book = item.getValue();
        if (book != null && book.getId() != null && !book.getId().isBlank() && book.isSelected()) {
            selected.add(book);
            log.debug("✅ Додано вибрану книгу: {}", book.getTitle());
        }
        for (TreeItem<BookViewModel> child : item.getChildren()) {
            collectSelectedBooks(child, selected);
        }
    }

    private void selectAllBooks(boolean selected) {
        TreeItem<BookViewModel> root = treeTableView.getRoot();
        if (root != null) {
            selectAllRecursive(root, selected);
        }
        log.info("{} всі книги в дереві", selected ? "✅ Вибрано" : "❌ Знято вибір з");
        treeTableView.refresh();
    }

    private void selectAllRecursive(TreeItem<BookViewModel> item, boolean selected) {
        BookViewModel book = item.getValue();
        if (book != null && book.getId() != null && !book.getId().isBlank()) {
            book.setSelected(selected);
        }
        for (TreeItem<BookViewModel> child : item.getChildren()) {
            selectAllRecursive(child, selected);
        }
    }

    // ===== FXML МЕТОДИ =====

    @FXML
    public void selectAll() {
        selectAllBooks(true);
        showAlert("Інформація", "✅ Всі книги вибрано (" + getSelectedBooks().size() + " книг)");
    }

    @FXML
    public void deselectAll() {
        selectAllBooks(false);
        showAlert("Інформація", "❌ Вибір знято з усіх книг");
    }

    @FXML
    public void exportSelected() {
        List<BookViewModel> selected = getSelectedBooks();
        if (selected.isEmpty()) {
            showAlert("Немає вибраних книг", "Виберіть книги за допомогою чекбоксів.");
            return;
        }
        exportController.showExportDialog(treeTableView.getScene().getWindow(), selected);
    }

    @FXML
    public void markSelectedAsRead() {
        List<BookViewModel> selected = getSelectedBooks();
        if (selected.isEmpty()) {
            showAlert("Немає вибраних книг", "Виберіть книги за допомогою чекбоксів.");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Позначити прочитаними");
        confirm.setHeaderText("Позначити " + selected.size() + " книг як прочитані?");
        confirm.setContentText("Прогрес буде встановлено на 100%.");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        try {
            List<BookId> ids = selected.stream().map(BookViewModel::getId).map(BookId::fromString).toList();
            markAsReadBatchUseCase.execute(ids);
            selected.forEach(book -> { book.setProgress(100); book.setSelected(false); });
            treeTableView.refresh();
            showAlert("Готово", selected.size() + " книг позначено як прочитані.");
        } catch (Exception e) {
            log.error("Не вдалося позначити книги як прочитані", e);
            Alert error = new Alert(Alert.AlertType.ERROR);
            error.setTitle("Помилка"); error.setHeaderText(null); error.setContentText(e.getMessage()); error.showAndWait();
        }
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

}
