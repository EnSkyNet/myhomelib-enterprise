package com.myhomelibcorp.ui.table;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.Pagination;
import com.myhomelibcorp.application.usecase.book.MarkAsReadBatchUseCase;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.mapper.BookViewModelMapper;
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

    private final BookQueryRepository bookQueryRepository;
    private final BookViewModelMapper viewModelMapper;
    private final BookMapper bookMapper;
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
        log.info("📚 Завантаження ієрархічного дерева книг");

        try {
            BookQuery query = BookQuery.builder()
                    .pagination(Pagination.of(10000, 0))
                    .build();

            List<Book> books = bookQueryRepository.findPage(query).content();
            log.info("📚 Завантажено {} книг для побудови дерева", books.size());

            if (books.isEmpty()) {
                TreeItem<BookViewModel> emptyRoot = new TreeItem<>(null);
                emptyRoot.setValue(null);
                treeTableView.setRoot(emptyRoot);
                treeTableView.setShowRoot(false);
                return;
            }

            // Групуємо книги за автором та серією
            Map<String, Map<String, List<Book>>> grouped = books.stream()
                    .collect(Collectors.groupingBy(
                            book -> book.getAuthors().isEmpty() ? "Невідомий Автор" :
                                    book.getAuthors().get(0).getFullName(),
                            Collectors.groupingBy(
                                    book -> book.getSeries() != null && !book.getSeries().isBlank() ?
                                            book.getSeries() : "Без серії"
                            )
                    ));

            TreeItem<BookViewModel> root = new TreeItem<>(null);
            root.setValue(null);
            root.setExpanded(true);

            grouped.forEach((authorName, seriesMap) -> {
                TreeItem<BookViewModel> authorItem = new TreeItem<>(null);
                BookViewModel authorVm = new BookViewModel();
                authorVm.setTitle(authorName);
                authorItem.setValue(authorVm);
                authorItem.setExpanded(true);

                seriesMap.entrySet().stream()
                        .filter(entry -> !"Без серії".equals(entry.getKey()))
                        .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                        .forEach(entry -> {
                            TreeItem<BookViewModel> seriesItem = new TreeItem<>(null);
                            BookViewModel seriesVm = new BookViewModel();
                            seriesVm.setTitle(entry.getKey());
                            seriesItem.setValue(seriesVm);
                            seriesItem.setExpanded(true);

                            entry.getValue().stream()
                                    .sorted(Comparator
                                            .comparing((Book b) -> b.getSequenceNumber() == null ? Integer.MAX_VALUE : b.getSequenceNumber())
                                            .thenComparing(Book::getTitle, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                                    .forEach(book -> {
                                        BookDto dto = bookMapper.toDto(book);
                                        BookViewModel vm = viewModelMapper.toViewModel(dto);
                                        vm.setSelected(false);
                                        seriesItem.getChildren().add(new TreeItem<>(vm));
                                    });

                            authorItem.getChildren().add(seriesItem);
                        });

                // Як у flibrary: книги без серії лежать безпосередньо під автором.
                seriesMap.getOrDefault("Без серії", List.of()).stream()
                        .sorted(Comparator.comparing(Book::getTitle, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                        .forEach(book -> {
                            BookDto dto = bookMapper.toDto(book);
                            BookViewModel vm = viewModelMapper.toViewModel(dto);
                            vm.setSelected(false);
                            authorItem.getChildren().add(new TreeItem<>(vm));
                        });

                root.getChildren().add(authorItem);
            });

            treeTableView.setRoot(root);
            treeTableView.setShowRoot(false);

            log.info("📚 Дерево побудовано: {} авторів", grouped.size());

        } catch (Exception e) {
            log.error("❌ Помилка завантаження дерева", e);
        }
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

    public void selectAllBooks() {
        selectAllBooks(true);
    }

    public void deselectAllBooks() {
        selectAllBooks(false);
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

    /**
     * Діагностичний метод для перевірки стану вибору
     */
    public void debugSelectionState() {
        log.info("=== ДІАГНОСТИКА ВИБОРУ ===");
        TreeItem<BookViewModel> root = treeTableView.getRoot();
        if (root != null) {
            debugSelectionRecursive(root, 0);
        }
        log.info("==========================");
    }

    private void debugSelectionRecursive(TreeItem<BookViewModel> item, int depth) {
        BookViewModel book = item.getValue();
        String indent = "  ".repeat(depth);
        if (book != null) {
            log.info("{}📚 {} : selected={}", indent, book.getTitle(), book.isSelected());
        }
        for (TreeItem<BookViewModel> child : item.getChildren()) {
            debugSelectionRecursive(child, depth + 1);
        }
    }
}