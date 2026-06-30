package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.CoverExtractor;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.navigation.GenreNode;
import com.myhomelibcorp.domain.model.navigation.LibraryNode;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.ui.components.BookInfoPanel;
import com.myhomelibcorp.ui.presentation.BookDetailsPresenter;
import com.myhomelibcorp.ui.service.BackgroundExecutor;
import com.myhomelibcorp.ui.service.BookTableService;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.viewmodel.MainViewModel;
import com.myhomelibcorp.ui.viewmodel.NavigationViewModel;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class MainController {

    private final MainViewModel mainViewModel;
    private final NavigationViewModel navigationViewModel;
    private final BookDetailsPresenter bookDetailsPresenter;
    private final CoverExtractor coverExtractor;
    private final BackgroundExecutor backgroundExecutor;
    private final DialogService dialogService;
    private final BookTableService bookTableService;

    @FXML private TreeView<LibraryNode> authorsTree;
    @FXML private ListView<String> seriesListView;
    @FXML private TreeView<LibraryNode> genresTree;
    @FXML private ListView<Group> groupsListView;
    @FXML private ListView<String> downloadsListView;

    @FXML private TableView<BookDto> bookTableView;
    @FXML private Label bookCountLabel;

    @FXML private TextField searchField;
    @FXML private ProgressIndicator searchIndicator;

    @FXML private Label statusLabel;
    @FXML private ProgressBar progressBar;

    @FXML private VBox detailsPane;

    private BookInfoPanel bookInfoPanel;

    private ContextMenu bookContextMenu;
    private MenuItem addToGroupMenuItem;
    private MenuItem removeFromGroupMenuItem;

    @FXML
    public void initialize() {
        log.info("🔵 MainController.initialize() started");

        // === Біндінг статусу та прогресу ===
        if (statusLabel != null) {
            statusLabel.textProperty().bind(mainViewModel.statusTextProperty());
        }
        if (progressBar != null) {
            progressBar.progressProperty().bind(mainViewModel.importProgressProperty());
            progressBar.visibleProperty().bind(mainViewModel.importInProgressProperty());
        }

        // === Лічильник книг ===
        if (bookCountLabel != null) {
            bookCountLabel.textProperty().bind(
                    javafx.beans.binding.Bindings.size(mainViewModel.booksProperty()).asString()
            );
        }

        // === BookInfoPanel зі скролом ===
        bookInfoPanel = new BookInfoPanel();

        ScrollPane scrollPane = new ScrollPane(bookInfoPanel);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(false); // ГОЛОВНЕ ВИПРАВЛЕННЯ
        //scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        //scrollPane.getStyleClass().add("edge-to-edge");
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        if (detailsPane != null) {
            detailsPane.getChildren().setAll(scrollPane);
            VBox.setVgrow(detailsPane, Priority.ALWAYS);
        }

        if (bookInfoPanel != null) {
            bookInfoPanel.bookProperty().bind(mainViewModel.selectedBookProperty());
            bookInfoPanel.setOnAuthorClicked(mainViewModel::searchBooks);
            bookInfoPanel.setOnSeriesClicked(series -> {
                if (series != null && !series.isBlank()) {
                    mainViewModel.loadBooksBySeries(series);
                }
            });
            bookInfoPanel.setOnGenreClicked(genres -> {});
            bookInfoPanel.setOnAnnotationClicked(book -> {});
        }

        // === Таблиця книг ===
        bookTableService.setupBookTable(bookTableView);
        if (bookTableView != null) {
            bookTableView.setItems(mainViewModel.booksProperty());

            bookTableView.getSelectionModel().selectedItemProperty().addListener(
                    (obs, oldVal, newVal) -> {
                        if (newVal != null) {
                            mainViewModel.setSelectedBook(newVal);
                        }
                    }
            );
        }

        // === Слухач для автоматичного завантаження обкладинки ===
        mainViewModel.selectedBookProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                loadCoverAsync(newVal);
            } else {
                bookInfoPanel.clear();
            }
        });

        // === Пошук ===
        if (searchField != null) {
            searchField.textProperty().bindBidirectional(mainViewModel.searchQueryProperty());
        }

        // === Навігація ===
        setupNavigation();

        // === Контекстне меню ===
        setupBookContextMenu();

        // === Ініціалізація даних ===
        mainViewModel.initWithoutBooks();
        navigationViewModel.refreshAll();

        // === CollectionRoot ===
        detectAndSetRoot();

        log.info("🔵 MainController.initialize() finished");
    }

    // ==================== ЗАВАНТАЖЕННЯ ОБКЛАДИНКИ ====================

    private void loadCoverAsync(BookDto book) {
        bookInfoPanel.setCover(null);
        backgroundExecutor.submit(() -> {
            try {
                return coverExtractor.extractCover(book);
            } catch (Exception e) {
                log.error("Помилка витягування обкладинки для {}", book.getTitle(), e);
                return null;
            }
        }).thenAccept(image -> Platform.runLater(() -> bookInfoPanel.setCover(image)));
    }

    // ==================== COLLECTION ROOT ====================

    private void detectAndSetRoot() {
        if (!mainViewModel.booksProperty().isEmpty()) {
            BookDto first = mainViewModel.booksProperty().get(0);
            if (first != null && first.getFolder() != null) {
                try {
                    Path folderPath = Paths.get(first.getFolder());
                    if (folderPath.isAbsolute()) {
                        mainViewModel.setCurrentCollectionRoot("");
                    } else {
                        Path root = folderPath.getParent();
                        if (root != null) {
                            mainViewModel.setCurrentCollectionRoot(root.toString());
                        }
                    }
                } catch (Exception e) {
                    log.warn("Не вдалося визначити collectionRoot", e);
                }
            }
        }
    }

    // ==================== НАВІГАЦІЯ ====================

    private void setupNavigation() {
        // Автори
        if (authorsTree != null) {
            authorsTree.rootProperty().bind(navigationViewModel.authorsRootProperty());
            authorsTree.setShowRoot(false);
            authorsTree.setCellFactory(tv -> new TreeCell<>() {
                @Override
                protected void updateItem(LibraryNode item, boolean empty) {
                    super.updateItem(item, empty);
                    setText((empty || item == null) ? null : item.toString());
                }
            });

            // Слухач для автоматичного вибору першого автора
            navigationViewModel.authorsRootProperty().addListener((obs, oldRoot, newRoot) -> {
                if (newRoot != null && !newRoot.getChildren().isEmpty()) {
                    TreeItem<LibraryNode> firstItem = newRoot.getChildren().get(0);
                    authorsTree.getSelectionModel().select(firstItem);
                }
            });

            authorsTree.getSelectionModel().selectedItemProperty().addListener(
                    (obs, oldVal, newVal) -> {
                        if (newVal != null && newVal.getValue() != null) {
                            LibraryNode node = newVal.getValue();
                            if (node instanceof com.myhomelibcorp.domain.model.navigation.AuthorNode authorNode) {
                                AuthorId authorId = authorNode.author().getId();
                                navigationViewModel.selectAuthor(authorId);
                                mainViewModel.loadBooksByAuthor(authorId);
                            }
                        }
                    }
            );
        }

        // Серії
        if (seriesListView != null) {
            seriesListView.setItems(navigationViewModel.seriesNamesProperty());
            seriesListView.getSelectionModel().selectedItemProperty().addListener(
                    (obs, oldVal, newVal) -> {
                        if (newVal != null && !newVal.isBlank()) {
                            mainViewModel.loadBooksBySeries(newVal);
                        }
                    }
            );
        }

        // Жанри
        if (genresTree != null) {
            genresTree.rootProperty().bind(navigationViewModel.genresRootProperty());
            genresTree.setShowRoot(false);
            genresTree.setCellFactory(tv -> new TreeCell<>() {
                @Override
                protected void updateItem(LibraryNode item, boolean empty) {
                    super.updateItem(item, empty);
                    setText((empty || item == null) ? null : item.toString());
                }
            });
            genresTree.getSelectionModel().selectedItemProperty().addListener(
                    (obs, oldVal, newVal) -> {
                        if (newVal != null && newVal.getValue() != null) {
                            LibraryNode node = newVal.getValue();
                            if (node instanceof GenreNode genreNode) {
                                Genre genre = genreNode.genre();
                                mainViewModel.loadBooksByGenre(genre.getId().asString());
                            }
                        }
                    }
            );
        }

        // Групи
        if (groupsListView != null) {
            groupsListView.setItems(navigationViewModel.groupsProperty());
            groupsListView.setCellFactory(lv -> new ListCell<>() {
                @Override
                protected void updateItem(Group item, boolean empty) {
                    super.updateItem(item, empty);
                    setText((empty || item == null) ? null : item.getName());
                }
            });
            groupsListView.getSelectionModel().selectedItemProperty().addListener(
                    (obs, oldVal, newVal) -> {
                        if (newVal != null) {
                            mainViewModel.loadBooksByGroup(newVal.getId());
                        }
                    }
            );
        }
    }

    // ==================== КОНТЕКСТНЕ МЕНЮ ====================

    private void setupBookContextMenu() {
        if (bookTableView == null) return;
        bookContextMenu = new ContextMenu();
        addToGroupMenuItem = new MenuItem("Додати до групи");
        removeFromGroupMenuItem = new MenuItem("Видалити з групи");

        addToGroupMenuItem.setOnAction(e -> handleAddBookToGroup());
        removeFromGroupMenuItem.setOnAction(e -> handleRemoveBookFromGroup());

        bookContextMenu.getItems().addAll(addToGroupMenuItem, removeFromGroupMenuItem);
        bookTableView.setContextMenu(bookContextMenu);

        bookTableView.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, newVal) -> {
                    boolean hasSelection = newVal != null;
                    addToGroupMenuItem.setDisable(!hasSelection);
                    removeFromGroupMenuItem.setDisable(!hasSelection);
                }
        );
    }

    // ==================== ОБРОБНИКИ КНОПОК ====================

    @FXML
    public void handleRefresh() {
        navigationViewModel.refreshAll();
        mainViewModel.refreshBooks();
    }

    @FXML
    public void handleImportFb2() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Виберіть FB2 файл");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("FB2 файли", "*.fb2", "*.fbd")
        );
        File file = fileChooser.showOpenDialog(null);
        if (file != null) {
            mainViewModel.importFile(file.toPath(), this::onImportComplete);
        }
    }

    @FXML
    public void handleImportInpx() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Виберіть INPX файл");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("INPX файли", "*.inpx", "*.inp")
        );
        File file = fileChooser.showOpenDialog(null);
        if (file != null) {
            mainViewModel.importFile(file.toPath(), this::onImportComplete);
        }
    }

    @FXML
    public void handleImportDirectory() {
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Виберіть каталог з книгами");
        File dir = directoryChooser.showDialog(null);
        if (dir != null && dir.isDirectory()) {
            mainViewModel.importDirectory(dir.toPath(), this::onImportComplete);
        }
    }

    @FXML
    public void handleAbout() {
        dialogService.showInfo(
                "Про програму",
                "MyHomeLib Enterprise",
                "Версія 1.0.0-SNAPSHOT\n\nJava-версія MyHomeLib\nJava 21, Spring Boot 3.5, JavaFX 21"
        );
    }

    @FXML
    public void handleExit() {
        Platform.exit();
    }

    @FXML
    public void handleRebuildIndex() {
        if (dialogService.showConfirmation(
                "Перебудова індексу",
                "Це може зайняти деякий час",
                "Перебудувати Lucene індекс для пошуку?"
        )) {
            mainViewModel.rebuildIndex();
        }
    }

    // ==================== ГРУПИ ====================

    @FXML
    public void handleAddGroup() {
        Optional<String> result = dialogService.showTextInput(
                "Додати групу",
                "Введіть назву нової групи",
                "Назва:",
                ""
        );
        result.ifPresent(name -> {
            if (!name.isBlank()) {
                try {
                    mainViewModel.createGroup(name);
                    navigationViewModel.loadGroups();
                    mainViewModel.setStatusText("Групу '" + name + "' створено");
                } catch (Exception e) {
                    dialogService.showError("Помилка створення групи", e.getMessage());
                }
            }
        });
    }

    @FXML
    public void handleEditGroup() {
        Group selected = groupsListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            dialogService.showError("Помилка", "Не вибрано жодної групи");
            return;
        }
        if (!selected.isAllowDelete()) {
            dialogService.showError("Помилка", "Цю групу не можна перейменовувати (системна група)");
            return;
        }
        Optional<String> result = dialogService.showTextInput(
                "Редагування групи",
                "Введіть нову назву групи",
                "Назва:",
                selected.getName()
        );
        result.ifPresent(newName -> {
            if (!newName.isBlank() && !newName.equals(selected.getName())) {
                try {
                    mainViewModel.renameGroup(selected.getId(), newName);
                    navigationViewModel.loadGroups();
                    mainViewModel.setStatusText("Групу перейменовано на '" + newName + "'");
                } catch (Exception e) {
                    dialogService.showError("Помилка перейменування", e.getMessage());
                }
            }
        });
    }

    @FXML
    public void handleDeleteGroup() {
        Group selected = groupsListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            dialogService.showError("Помилка", "Не вибрано жодної групи");
            return;
        }
        if (!selected.isAllowDelete()) {
            dialogService.showError("Помилка", "Цю групу не можна видалити (системна група)");
            return;
        }
        if (dialogService.showConfirmation(
                "Підтвердження",
                "Видалити групу '" + selected.getName() + "'?",
                "Книги, що належать до групи, не будуть видалені, але зв'язок буде втрачено."
        )) {
            try {
                mainViewModel.deleteGroup(selected.getId());
                navigationViewModel.loadGroups();
                mainViewModel.setStatusText("Групу '" + selected.getName() + "' видалено");
            } catch (Exception e) {
                dialogService.showError("Помилка видалення", e.getMessage());
            }
        }
    }

    private void handleAddBookToGroup() {
        BookDto selectedBook = bookTableView.getSelectionModel().getSelectedItem();
        if (selectedBook == null) {
            dialogService.showError("Помилка", "Не вибрано жодної книги");
            return;
        }
        var groups = navigationViewModel.groupsProperty();
        if (groups.isEmpty()) {
            dialogService.showWarning("Помилка", "Немає жодної групи", "Створіть групу спочатку.");
            return;
        }

        dialogService.showGroupChoiceDialog(groups, selectedBook.getTitle())
                .ifPresent(group -> {
                    try {
                        mainViewModel.addBookToGroup(group.getId(), selectedBook.getId());
                        mainViewModel.setStatusText("Книгу додано до групи '" + group.getName() + "'");
                    } catch (Exception e) {
                        dialogService.showError("Помилка додавання", e.getMessage());
                    }
                });
    }

    private void handleRemoveBookFromGroup() {
        BookDto selectedBook = bookTableView.getSelectionModel().getSelectedItem();
        if (selectedBook == null) {
            dialogService.showError("Помилка", "Не вибрано жодної книги");
            return;
        }
        Group selectedGroup = groupsListView.getSelectionModel().getSelectedItem();
        if (selectedGroup == null) {
            dialogService.showError("Помилка", "Не вибрано групу для видалення книги");
            return;
        }
        if (dialogService.showConfirmation(
                "Підтвердження",
                "Видалити книгу '" + selectedBook.getTitle() + "' з групи '" + selectedGroup.getName() + "'?",
                "Книга не буде видалена з бібліотеки, лише зв'язок буде втрачено."
        )) {
            try {
                mainViewModel.removeBookFromGroup(selectedGroup.getId(), selectedBook.getId());
                mainViewModel.loadBooksByGroup(selectedGroup.getId());
                mainViewModel.setStatusText("Книгу видалено з групи '" + selectedGroup.getName() + "'");
            } catch (Exception e) {
                dialogService.showError("Помилка видалення з групи", e.getMessage());
            }
        }
    }

    private void onImportComplete() {
        log.info("✅ onImportComplete() викликано");
        navigationViewModel.refreshAll();
        mainViewModel.setStatusText("Імпорт завершено. Оновлюємо таблицю...");
        mainViewModel.restoreContextAndRefresh();
    }

    // ==================== ЗАГОТОВКИ ====================

    @FXML
    public void handleOpenCollection() {
        // TODO
    }

    @FXML
    public void handleNewCollection() {
        // TODO
    }

    @FXML
    public void handleEditMetadata() {
        // TODO
    }

    @FXML
    public void handleDeleteBook() {
        // TODO
    }

    @FXML
    public void handleShowColumns() {
        // TODO
    }

    @FXML
    public void handleExport() {
        // TODO
    }
}