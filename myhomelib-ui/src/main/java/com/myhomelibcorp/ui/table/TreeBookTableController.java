package com.myhomelibcorp.ui.table;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.Pagination;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.mapper.BookViewModelMapper;
import com.myhomelibcorp.ui.model.TreeNode;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableValue;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.TreeItemPropertyValueFactory;
import javafx.util.Callback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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

    @FXML private TreeTableView<TreeNode> treeTableView;
    @FXML private TreeTableColumn<TreeNode, String> titleColumn;
    @FXML private TreeTableColumn<TreeNode, String> authorColumn;
    @FXML private TreeTableColumn<TreeNode, String> seriesColumn;
    @FXML private TreeTableColumn<TreeNode, String> genresColumn;
    @FXML private TreeTableColumn<TreeNode, String> rateColumn;
    @FXML private TreeTableColumn<TreeNode, String> progressColumn;
    @FXML private TreeTableColumn<TreeNode, String> dateColumn;

    private TreeTableColumn<TreeNode, Boolean> selectColumn;

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

        selectColumn.setCellValueFactory(new Callback<TreeTableColumn.CellDataFeatures<TreeNode, Boolean>, ObservableValue<Boolean>>() {
            @Override
            public ObservableValue<Boolean> call(TreeTableColumn.CellDataFeatures<TreeNode, Boolean> param) {
                TreeItem<TreeNode> treeItem = param.getValue();
                if (treeItem == null) {
                    return new SimpleBooleanProperty(false);
                }
                TreeNode node = treeItem.getValue();
                if (node != null && node.getType() == TreeNode.NodeType.BOOK && node.getBook() != null) {
                    return node.getBook().selectedProperty();
                }
                return new SimpleBooleanProperty(false);
            }
        });

        selectColumn.setCellFactory(new Callback<TreeTableColumn<TreeNode, Boolean>, TreeTableCell<TreeNode, Boolean>>() {
            @Override
            public TreeTableCell<TreeNode, Boolean> call(TreeTableColumn<TreeNode, Boolean> param) {
                return new TreeTableCell<TreeNode, Boolean>() {
                    private final CheckBox checkBox = new CheckBox();
                    private ChangeListener<Boolean> checkBoxListener;
                    private BookViewModel currentBook;

                    @Override
                    protected void updateItem(Boolean item, boolean empty) {
                        super.updateItem(item, empty);

                        // Видаляємо старий слухач
                        if (checkBoxListener != null && currentBook != null) {
                            checkBox.selectedProperty().removeListener(checkBoxListener);
                            checkBoxListener = null;
                            currentBook = null;
                        }

                        if (empty) {
                            setGraphic(null);
                            return;
                        }

                        TreeItem<TreeNode> treeItem = getTreeTableRow() != null ? getTreeTableRow().getTreeItem() : null;
                        if (treeItem == null) {
                            setGraphic(null);
                            return;
                        }

                        TreeNode node = treeItem.getValue();
                        if (node == null || node.getType() != TreeNode.NodeType.BOOK || node.getBook() == null) {
                            setGraphic(null);
                            return;
                        }

                        currentBook = node.getBook();

                        // Встановлюємо стан чекбокса
                        boolean isSelected = item != null && item;
                        checkBox.setSelected(isSelected);
                        checkBox.setDisable(false);

                        // Створюємо новий слухач
                        checkBoxListener = (obs, oldVal, newVal) -> {
                            if (currentBook != null) {
                                currentBook.setSelected(newVal);
                                log.debug("📚 Книгу '{}' {}", currentBook.getTitle(), newVal ? "✅ ВИБРАНО" : "❌ ЗНЯТО ВИБІР");
                            }
                        };

                        checkBox.selectedProperty().addListener(checkBoxListener);

                        setAlignment(Pos.CENTER);
                        setGraphic(checkBox);
                    }
                };
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

        titleColumn.setCellValueFactory(new TreeItemPropertyValueFactory<>("name"));
        titleColumn.setPrefWidth(250);

        authorColumn.setCellValueFactory(cellData -> {
            TreeNode node = cellData.getValue().getValue();
            if (node != null && node.getType() == TreeNode.NodeType.BOOK && node.getBook() != null) {
                return node.getBook().authorsTextProperty();
            }
            return null;
        });
        authorColumn.setPrefWidth(150);

        seriesColumn.setCellValueFactory(cellData -> {
            TreeNode node = cellData.getValue().getValue();
            if (node != null && node.getType() == TreeNode.NodeType.BOOK && node.getBook() != null) {
                return node.getBook().seriesProperty();
            }
            return null;
        });
        seriesColumn.setPrefWidth(120);

        genresColumn.setCellValueFactory(cellData -> {
            TreeNode node = cellData.getValue().getValue();
            if (node != null && node.getType() == TreeNode.NodeType.BOOK && node.getBook() != null) {
                return node.getBook().genresTextProperty();
            }
            return null;
        });
        genresColumn.setPrefWidth(100);

        rateColumn.setCellValueFactory(cellData -> {
            TreeNode node = cellData.getValue().getValue();
            if (node != null && node.getType() == TreeNode.NodeType.BOOK && node.getBook() != null) {
                return node.getBook().rateStarsProperty();
            }
            return null;
        });
        rateColumn.setPrefWidth(80);

        progressColumn.setCellValueFactory(cellData -> {
            TreeNode node = cellData.getValue().getValue();
            if (node != null && node.getType() == TreeNode.NodeType.BOOK && node.getBook() != null) {
                return node.getBook().progressFormattedProperty();
            }
            return null;
        });
        progressColumn.setPrefWidth(80);

        dateColumn.setCellValueFactory(cellData -> {
            TreeNode node = cellData.getValue().getValue();
            if (node != null && node.getType() == TreeNode.NodeType.BOOK && node.getBook() != null) {
                return node.getBook().createdAtFormattedProperty();
            }
            return null;
        });
        dateColumn.setPrefWidth(100);

        treeTableView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
    }

    /**
     * Налаштовує слухач вибору
     */
    private void setupSelectionListener() {
        treeTableView.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null && selected.getValue() != null) {
                TreeNode node = selected.getValue();
                if (node.getType() == TreeNode.NodeType.BOOK && node.getBook() != null) {
                    appState.getBookTable().setSelectedBook(node.getBook());
                }
            }
        });
    }

    /**
     * Налаштовує подвійний клік для відкриття книги
     */
    private void setupDoubleClickHandler() {
        treeTableView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                TreeItem<TreeNode> selected = treeTableView.getSelectionModel().getSelectedItem();
                if (selected != null && selected.getValue() != null) {
                    TreeNode node = selected.getValue();
                    if (node.getType() == TreeNode.NodeType.BOOK && node.getBook() != null) {
                        navigationService.navigateToBook(BookId.fromString(node.getBook().getId()));
                    }
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

            List<Book> books = bookQueryRepository.find(query);
            log.info("📚 Завантажено {} книг для побудови дерева", books.size());

            if (books.isEmpty()) {
                TreeItem<TreeNode> emptyRoot = new TreeItem<>(new TreeNode(TreeNode.NodeType.ROOT, "Немає книг"));
                treeTableView.setRoot(emptyRoot);
                treeTableView.setShowRoot(false);
                return;
            }

            Map<String, Map<String, List<Book>>> grouped = books.stream()
                    .collect(Collectors.groupingBy(
                            book -> book.getAuthors().isEmpty() ? "Невідомий Автор" :
                                    book.getAuthors().get(0).getFullName(),
                            Collectors.groupingBy(
                                    book -> book.getSeries() != null && !book.getSeries().isBlank() ?
                                            book.getSeries() : "Без серії"
                            )
                    ));

            TreeItem<TreeNode> root = new TreeItem<>(new TreeNode(TreeNode.NodeType.ROOT, "Книги"));
            root.setExpanded(true);

            grouped.forEach((authorName, seriesMap) -> {
                TreeNode authorNode = new TreeNode(TreeNode.NodeType.AUTHOR, authorName);
                TreeItem<TreeNode> authorItem = new TreeItem<>(authorNode);
                authorItem.setExpanded(true);

                seriesMap.forEach((seriesName, bookList) -> {
                    TreeNode seriesNode = new TreeNode(TreeNode.NodeType.SERIES, seriesName);
                    TreeItem<TreeNode> seriesItem = new TreeItem<>(seriesNode);
                    seriesItem.setExpanded(true);

                    bookList.stream()
                            .sorted((b1, b2) -> {
                                int n1 = b1.getSequenceNumber() != null ? b1.getSequenceNumber() : 0;
                                int n2 = b2.getSequenceNumber() != null ? b2.getSequenceNumber() : 0;
                                return Integer.compare(n1, n2);
                            })
                            .forEach(book -> {
                                BookDto dto = bookMapper.toDto(book);
                                BookViewModel vm = viewModelMapper.toViewModel(dto);
                                // ВАЖЛИВО: скидаємо вибір при завантаженні
                                vm.setSelected(false);
                                TreeNode bookNode = new TreeNode(vm);
                                TreeItem<TreeNode> bookItem = new TreeItem<>(bookNode);
                                seriesItem.getChildren().add(bookItem);
                            });

                    authorItem.getChildren().add(seriesItem);
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
     * Повертає вибрані книги - з покращеним логуванням
     */
    public List<BookViewModel> getSelectedBooks() {
        List<BookViewModel> selected = new ArrayList<>();
        TreeItem<TreeNode> root = treeTableView.getRoot();

        log.debug("🔍 Пошук вибраних книг у дереві...");

        if (root != null) {
            collectSelectedBooks(root, selected);
        }

        log.info("📊 Знайдено {} вибраних книг", selected.size());

        // Логуємо назви вибраних книг для перевірки
        if (!selected.isEmpty()) {
            log.info("📋 Список вибраних книг:");
            for (BookViewModel book : selected) {
                log.info("   - {} (ID: {})", book.getTitle(), book.getId());
            }
        }

        return selected;
    }

    private void collectSelectedBooks(TreeItem<TreeNode> item, List<BookViewModel> selected) {
        TreeNode node = item.getValue();
        if (node != null) {
            if (node.getType() == TreeNode.NodeType.BOOK && node.getBook() != null) {
                BookViewModel book = node.getBook();
                if (book.isSelected()) {
                    selected.add(book);
                    log.debug("✅ Додано вибрану книгу: {}", book.getTitle());
                }
            }
        }
        for (TreeItem<TreeNode> child : item.getChildren()) {
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
        TreeItem<TreeNode> root = treeTableView.getRoot();
        if (root != null) {
            selectAllRecursive(root, selected);
        }
        log.info("{} всі книги в дереві", selected ? "✅ Вибрано" : "❌ Знято вибір з");
        treeTableView.refresh();
    }

    private void selectAllRecursive(TreeItem<TreeNode> item, boolean selected) {
        TreeNode node = item.getValue();
        if (node != null && node.getType() == TreeNode.NodeType.BOOK && node.getBook() != null) {
            node.getBook().setSelected(selected);
        }
        for (TreeItem<TreeNode> child : item.getChildren()) {
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

        log.info("📤 Експорт: отримано {} вибраних книг", selected.size());

        if (selected.isEmpty()) {
            log.warn("⚠️ Спроба експорту без вибраних книг");
            showAlert("Немає вибраних книг",
                    "Будь ласка, виберіть книги за допомогою чекбоксів.\n" +
                            "У режимі дерева вибирайте книги на рівні книг (не авторів або серій).\n\n" +
                            "💡 Порада: натисніть 'Вибрати всі' для вибору всіх книг.");
            return;
        }

        // Показуємо список вибраних книг
        StringBuilder bookList = new StringBuilder();
        for (int i = 0; i < Math.min(selected.size(), 10); i++) {
            bookList.append("  • ").append(selected.get(i).getTitle()).append("\n");
        }
        if (selected.size() > 10) {
            bookList.append("  ... та ще ").append(selected.size() - 10).append(" книг");
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Підтвердження експорту");
        confirm.setHeaderText("Експорт " + selected.size() + " вибраних книг");
        confirm.setContentText("Вибрано книги:\n" + bookList.toString());

        ButtonType exportButton = new ButtonType("Експортувати");
        ButtonType cancelButton = new ButtonType("Скасувати", ButtonBar.ButtonData.CANCEL_CLOSE);
        confirm.getButtonTypes().setAll(exportButton, cancelButton);

        if (confirm.showAndWait().orElse(cancelButton) == exportButton) {
            log.info("📤 Початок експорту {} книг", selected.size());
            showAlert("Експорт", "📤 Експорт " + selected.size() + " вибраних книг розпочато!");
            // Тут викликається реальний експорт
        }
    }

    @FXML
    public void markSelectedAsRead() {
        List<BookViewModel> selected = getSelectedBooks();
        if (selected.isEmpty()) {
            showAlert("Немає вибраних книг",
                    "Будь ласка, виберіть книги за допомогою чекбоксів.");
            return;
        }
        showAlert("Позначення прочитаними", "📖 Позначення " + selected.size() + " книг як прочитаних");
        log.info("📖 Позначення {} книг як прочитаних", selected.size());
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
        TreeItem<TreeNode> root = treeTableView.getRoot();
        if (root != null) {
            debugSelectionRecursive(root, 0);
        }
        log.info("==========================");
    }

    private void debugSelectionRecursive(TreeItem<TreeNode> item, int depth) {
        TreeNode node = item.getValue();
        if (node != null) {
            String indent = "  ".repeat(depth);
            if (node.getType() == TreeNode.NodeType.BOOK && node.getBook() != null) {
                log.info("{}📚 {} : selected={}",
                        indent, node.getBook().getTitle(), node.getBook().isSelected());
            } else {
                log.info("{}📁 {} : type={}", indent, node.getName(), node.getType());
            }
        }
        for (TreeItem<TreeNode> child : item.getChildren()) {
            debugSelectionRecursive(child, depth + 1);
        }
    }
}