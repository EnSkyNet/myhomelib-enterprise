package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.navigation.LibraryNode;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.ui.components.BookInfoPanel;
import com.myhomelibcorp.ui.presentation.BookDetailsPresenter;
import com.myhomelibcorp.ui.presenter.*;
import com.myhomelibcorp.ui.service.*;
import com.myhomelibcorp.ui.viewmodel.MainViewModel;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class MainController {

    private final MainViewModel mainViewModel;
    private final BookSelectionService bookSelectionService;
    private final BookDetailsPresenter bookDetailsPresenter;
    private final CoverPresenter coverPresenter;
    private final BookImportPresenter bookImportPresenter;
    private final BookSearchPresenter bookSearchPresenter;
    private final StatusBarPresenter statusBarPresenter;
    private final ProgressPresenter progressPresenter;
    private final LibraryNavigationPresenter navigationPresenter;
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
    @FXML private Label statusLabel;
    @FXML private ProgressBar progressBar;
    @FXML private VBox detailsPane;

    private BookInfoPanel bookInfoPanel;
    private boolean initialLoadDone = false;

    @FXML
    public void initialize() {
        log.info("Initializing MainController");

        statusBarPresenter.bind(statusLabel);
        progressPresenter.bind(progressBar);

        bookInfoPanel = new BookInfoPanel();
        ScrollPane scrollPane = new ScrollPane(bookInfoPanel);
        scrollPane.setFitToWidth(true);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        detailsPane.getChildren().setAll(scrollPane);

        bookInfoPanel.bookProperty().bind(bookSelectionService.selectedBookProperty());
        bookInfoPanel.setOnAuthorClicked(mainViewModel::searchBooks);
        bookInfoPanel.setOnSeriesClicked(series -> mainViewModel.loadBooksBySeries(series));
        bookInfoPanel.setOnAnnotationClicked(book -> {});

        coverPresenter.bind(bookInfoPanel.getCoverImageView());

        bookTableService.setupBookTable(bookTableView);
        bookTableView.setItems(mainViewModel.booksProperty());

        // ЄДИНИЙ СЛУХАЧ – через ViewModel
        mainViewModel.selectedBookProperty().addListener((obs, oldBook, newBook) -> {
            if (newBook != null) {
                bookSelectionService.selectBook(newBook);
                coverPresenter.showCover(newBook);
            } else {
                coverPresenter.clearCover();
            }
        });

        bookSearchPresenter.bind(mainViewModel.booksProperty(), () -> {
            if (!mainViewModel.booksProperty().isEmpty()) {
                bookTableView.getSelectionModel().selectFirst();
            }
        });
        searchField.textProperty().bindBidirectional(bookSearchPresenter.queryProperty());

        // ---------- ІНІЦІАЛІЗАЦІЯ ----------
        mainViewModel.initWithoutBooks();

        // Завантажуємо авторів
        navigationPresenter.loadAuthors(authorsTree, mainViewModel::loadBooksByAuthor)
                .thenRun(() -> {
                    Platform.runLater(() -> {
                        TreeItem<LibraryNode> root = authorsTree.getRoot();
                        if (root != null && !root.getChildren().isEmpty()) {
                            TreeItem<LibraryNode> firstItem = root.getChildren().get(0);
                            LibraryNode firstNode = firstItem.getValue();
                            if (firstNode instanceof com.myhomelibcorp.domain.model.navigation.AuthorNode) {
                                AuthorId firstAuthorId = ((com.myhomelibcorp.domain.model.navigation.AuthorNode) firstNode).author().getId();
                                log.info("Перший автор: {}, завантажуємо його книги", firstAuthorId.asString());
                                mainViewModel.loadBooksByAuthor(firstAuthorId);
                                authorsTree.getSelectionModel().select(firstItem);
                                initialLoadDone = true;
                            }
                        } else {
                            log.warn("Авторів не знайдено, завантажуємо всі книги");
                            mainViewModel.refreshBooks();
                            initialLoadDone = true;
                        }
                    });
                });

        // Завантажуємо інші розділи
        navigationPresenter.loadSeries(seriesListView.getItems());
        navigationPresenter.loadGenres(genresTree, mainViewModel::loadBooksByGenre);
        navigationPresenter.loadGroups(groupsListView.getItems());

        // Вибір групи – використовуємо asLong()
        groupsListView.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, group) -> {
                    if (group != null) {
                        mainViewModel.loadBooksByGroup(group.getId().asLong());
                    }
                });

        // Слухач для вибору книги
        mainViewModel.selectedBookProperty().addListener((obs, old, newBook) -> {
            if (newBook != null) {
                bookSelectionService.selectBook(newBook);
                coverPresenter.showCover(newBook);
            } else {
                coverPresenter.clearCover();
            }
        });

        bookCountLabel.textProperty().bind(
                javafx.beans.binding.Bindings.size(mainViewModel.booksProperty()).asString()
        );

        if (!initialLoadDone) {
            mainViewModel.refreshBooks();
        }
    }

    // ==================== ОБРОБНИКИ ====================
    @FXML public void handleRefresh() {
        mainViewModel.refreshBooks();
        navigationPresenter.loadAuthors(authorsTree, mainViewModel::loadBooksByAuthor)
                .thenRun(() -> Platform.runLater(() -> {
                    if (authorsTree.getRoot() != null && !authorsTree.getRoot().getChildren().isEmpty()) {
                        authorsTree.getSelectionModel().selectFirst();
                    }
                }));
        navigationPresenter.loadSeries(seriesListView.getItems());
        navigationPresenter.loadGenres(genresTree, mainViewModel::loadBooksByGenre);
        navigationPresenter.loadGroups(groupsListView.getItems());
        statusBarPresenter.setStatus("Оновлено");
    }

    @FXML public void handleImportFb2() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Виберіть FB2 файл");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("FB2 файли", "*.fb2", "*.fbd"));
        File file = fileChooser.showOpenDialog(null);
        if (file != null) {
            bookImportPresenter.importFile(file.toPath(), this::onImportComplete);
        }
    }

    @FXML public void handleImportInpx() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Виберіть INPX файл");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("INPX файли", "*.inpx", "*.inp"));
        File file = fileChooser.showOpenDialog(null);
        if (file != null) {
            bookImportPresenter.importFile(file.toPath(), this::onImportComplete);
        }
    }

    @FXML
    public void handleImportDirectory() {
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Виберіть каталог з книгами");
        File dir = directoryChooser.showDialog(null);
        if (dir != null) {
            bookImportPresenter.importDirectory(dir.toPath(), this::onImportComplete);
        }
    }

    @FXML public void handleRebuildIndex() {
        if (dialogService.showConfirmation("Перебудова індексу",
                "Це може зайняти деякий час",
                "Перебудувати Lucene індекс для пошуку?")) {
            mainViewModel.rebuildIndex();
            statusBarPresenter.setStatus("Перебудова індексу розпочата");
        }
    }

    @FXML public void handleAddGroup() {
        Optional<String> result = dialogService.showTextInput("Додати групу",
                "Введіть назву нової групи", "Назва:", "");
        result.ifPresent(name -> {
            if (!name.isBlank()) {
                try {
                    mainViewModel.createGroup(name);
                    navigationPresenter.loadGroups(groupsListView.getItems());
                    statusBarPresenter.setStatus("Групу '" + name + "' створено");
                } catch (Exception e) {
                    dialogService.showError("Помилка", e.getMessage());
                }
            }
        });
    }

    @FXML public void handleEditGroup() {
        Group selected = groupsListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            dialogService.showError("Помилка", "Не вибрано жодної групи");
            return;
        }
        if (!selected.isAllowDelete()) {
            dialogService.showError("Помилка", "Цю групу не можна перейменовувати (системна)");
            return;
        }
        Optional<String> result = dialogService.showTextInput("Редагування групи",
                "Введіть нову назву групи", "Назва:", selected.getName());
        result.ifPresent(newName -> {
            if (!newName.isBlank() && !newName.equals(selected.getName())) {
                try {
                    mainViewModel.renameGroup(selected.getId().asLong(), newName);
                    navigationPresenter.loadGroups(groupsListView.getItems());
                    statusBarPresenter.setStatus("Групу перейменовано на '" + newName + "'");
                } catch (Exception e) {
                    dialogService.showError("Помилка", e.getMessage());
                }
            }
        });
    }

    @FXML public void handleDeleteGroup() {
        Group selected = groupsListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            dialogService.showError("Помилка", "Не вибрано жодної групи");
            return;
        }
        if (!selected.isAllowDelete()) {
            dialogService.showError("Помилка", "Цю групу не можна видалити (системна)");
            return;
        }
        if (dialogService.showConfirmation("Підтвердження",
                "Видалити групу '" + selected.getName() + "'?",
                "Книги не будуть видалені, але зв'язок буде втрачено.")) {
            try {
                mainViewModel.deleteGroup(selected.getId().asLong());
                navigationPresenter.loadGroups(groupsListView.getItems());
                statusBarPresenter.setStatus("Групу видалено");
            } catch (Exception e) {
                dialogService.showError("Помилка", e.getMessage());
            }
        }
    }

    @FXML public void handleAbout() {
        dialogService.showInfo("Про програму", "MyHomeLib Enterprise",
                "Версія 1.0.0-SNAPSHOT\nJava 21, Spring Boot 3.5, JavaFX 21");
    }

    @FXML public void handleExit() {
        Platform.exit();
    }

    @FXML public void handleOpenCollection() { /* TODO */ }
    @FXML public void handleNewCollection() { /* TODO */ }
    @FXML public void handleEditMetadata() { /* TODO */ }
    @FXML public void handleDeleteBook() { /* TODO */ }
    @FXML public void handleShowColumns() { /* TODO */ }
    @FXML public void handleExport() { /* TODO */ }

    private void onImportComplete() {
        log.info("Імпорт завершено, оновлення...");
        mainViewModel.restoreContextAndRefresh();
        navigationPresenter.loadAuthors(authorsTree, mainViewModel::loadBooksByAuthor)
                .thenRun(() -> Platform.runLater(() -> {
                    if (authorsTree.getRoot() != null && !authorsTree.getRoot().getChildren().isEmpty()) {
                        authorsTree.getSelectionModel().selectFirst();
                    }
                }));
        navigationPresenter.loadSeries(seriesListView.getItems());
        navigationPresenter.loadGenres(genresTree, mainViewModel::loadBooksByGenre);
        navigationPresenter.loadGroups(groupsListView.getItems());
        statusBarPresenter.setStatus("Імпорт завершено. Таблицю оновлено.");
    }
}