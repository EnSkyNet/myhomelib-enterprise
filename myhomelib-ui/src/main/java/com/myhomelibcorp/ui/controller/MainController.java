package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.ui.components.BookInfoPanel;
import com.myhomelibcorp.ui.model.navigation.AuthorNode;
import com.myhomelibcorp.ui.model.navigation.LibraryNode;
import com.myhomelibcorp.ui.presentation.BookDetailsPresenter;
import com.myhomelibcorp.ui.presenter.*;
import com.myhomelibcorp.ui.service.*;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import com.myhomelibcorp.ui.viewmodel.MainViewModel;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
    private final GroupPresenter groupPresenter;
    private final RefreshPresenter refreshPresenter;
    private final LibraryPresenter libraryPresenter;
    private final SettingsPresenter settingsPresenter;
    private final DialogService dialogService;
    private final BookTableService bookTableService;
    private final FileChooserService fileChooserService;

    @FXML private TreeView<LibraryNode> authorsTree;
    @FXML private ListView<String> seriesListView;
    @FXML private TreeView<LibraryNode> genresTree;
    @FXML private ListView<Group> groupsListView;
    @FXML private ListView<String> downloadsListView;
    @FXML private TableView<BookViewModel> bookTableView;
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

        // ========== СЛУХАЧ ДЛЯ ВИБОРУ В ТАБЛИЦІ ==========
        bookTableView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> {
                    if (newVal != null) {
                        mainViewModel.setSelectedBook(newVal);
                    }
                }
        );

        // ========== СЛУХАЧ ДЛЯ ЗМІНИ ВИБРАНОЇ КНИГИ В VIEWMODEL ==========
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
                            if (firstNode instanceof AuthorNode) {
                                AuthorId firstAuthorId = ((AuthorNode) firstNode).author().getId();
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

        // Вибір групи
        groupsListView.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, group) -> {
                    if (group != null) {
                        mainViewModel.loadBooksByGroup(group.getId().asLong());
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
        refreshPresenter.refreshAll(
                authorsTree,
                seriesListView.getItems(),
                genresTree,
                groupsListView.getItems(),
                () -> {
                    // Після оновлення вибираємо першого автора
                    Platform.runLater(() -> {
                        if (authorsTree.getRoot() != null && !authorsTree.getRoot().getChildren().isEmpty()) {
                            authorsTree.getSelectionModel().selectFirst();
                        }
                    });
                }
        );
    }

    @FXML public void handleImportFb2() {
        bookImportPresenter.importFb2();
    }

    @FXML public void handleImportInpx() {
        bookImportPresenter.importInpx();
    }

    @FXML public void handleImportDirectory() {
        bookImportPresenter.importDirectory();
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
        groupPresenter.showAddGroupDialog(groupsListView, () -> {
            navigationPresenter.loadGroups(groupsListView.getItems());
        });
    }

    @FXML public void handleEditGroup() {
        groupPresenter.showEditGroupDialog(groupsListView, () -> {
            navigationPresenter.loadGroups(groupsListView.getItems());
        });
    }

    @FXML public void handleDeleteGroup() {
        groupPresenter.showDeleteGroupDialog(groupsListView, () -> {
            navigationPresenter.loadGroups(groupsListView.getItems());
        });
    }

    @FXML public void handleOpenCollection() {
        libraryPresenter.openCollection((Stage) bookTableView.getScene().getWindow());
    }

    @FXML public void handleNewCollection() {
        libraryPresenter.createNewCollection((Stage) bookTableView.getScene().getWindow());
    }

    @FXML public void handleExport() {
        libraryPresenter.exportLibrary((Stage) bookTableView.getScene().getWindow());
    }

    @FXML public void handleShowColumns() {
        settingsPresenter.showColumnsDialog();
    }

    @FXML public void handleEditMetadata() {
        // TODO: реалізувати редагування метаданих
        dialogService.showInfo("Інформація", "Редагування метаданих", "Функція поки що не реалізована");
    }

    @FXML public void handleDeleteBook() {
        // TODO: реалізувати видалення книги
        dialogService.showInfo("Інформація", "Видалення книги", "Функція поки що не реалізована");
    }

    @FXML public void handleAbout() {
        dialogService.showInfo("Про програму", "MyHomeLib Enterprise",
                "Версія 1.0.0-SNAPSHOT\nJava 21, Spring Boot 3.5, JavaFX 21");
    }

    @FXML public void handleExit() {
        Platform.exit();
    }

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