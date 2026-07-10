package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.initializer.DatabaseInitializer;
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
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;

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
    private final CollectionPresenter collectionPresenter;
    private final DialogService dialogService;
    private final BookTableService bookTableService;
    private final FileChooserService fileChooserService;
    private final CollectionManager collectionManager;
    private final DatabaseInitializer databaseInitializer;
    private final LibraryReloadService libraryReloadService; // додано

    @FXML private TreeView<LibraryNode> authorsTree;
    @FXML private ListView<String> seriesListView;
    @FXML private TreeView<LibraryNode> genresTree;
    @FXML private ListView<Group> groupsListView;
    @FXML private ListView<Collection> collectionsListView;
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
        log.info("=== MainController.initialize() START ===");

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

        bookTableView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> {
                    if (newVal != null) {
                        mainViewModel.setSelectedBook(newVal);
                    }
                }
        );

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

        collectionsListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Collection item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.getName());
                }
            }
        });

        mainViewModel.initWithoutBooks();

        // При старті завантажуємо навігацію та вибираємо першого автора
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

        navigationPresenter.loadSeries(seriesListView.getItems());
        navigationPresenter.loadGenres(genresTree, mainViewModel::loadBooksByGenre);
        navigationPresenter.loadGroups(groupsListView.getItems());
        collectionPresenter.loadCollections(collectionsListView.getItems());

        groupsListView.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, group) -> {
                    if (group != null) {
                        mainViewModel.loadBooksByGroup(group.getId().asLong());
                    }
                });

        collectionsListView.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, collection) -> {
                    if (collection != null) {
                        dialogService.showInfo("Вибір колекції", "Обрано колекцію: " + collection.getName(),
                                "Фільтрація за колекцією буде реалізована пізніше.");
                    }
                });

        mainViewModel.updateCollectionStats();

        bookCountLabel.textProperty().bind(
                javafx.beans.binding.Bindings.size(mainViewModel.booksProperty()).asString()
        );

        if (!initialLoadDone) {
            mainViewModel.refreshBooks();
        }

        log.info("=== MainController.initialize() END ===");
    }

    private void refreshNavigationAndLoadFirstAuthor() {
        log.info("Оновлення навігації після зміни колекції...");
        bookSelectionService.clearSelection();
        mainViewModel.setSelectedBook(null);

        navigationPresenter.refreshAuthors(authorsTree, mainViewModel::loadBooksByAuthor)
                .thenRun(() -> {
                    Platform.runLater(() -> {
                        TreeItem<LibraryNode> root = authorsTree.getRoot();
                        if (root != null && !root.getChildren().isEmpty()) {
                            TreeItem<LibraryNode> firstItem = root.getChildren().get(0);
                            if (firstItem.getValue() instanceof AuthorNode) {
                                AuthorId id = ((AuthorNode) firstItem.getValue()).author().getId();
                                log.info("Завантажуємо книги для першого автора: {}", id.asString());
                                mainViewModel.loadBooksByAuthor(id);
                                authorsTree.getSelectionModel().select(firstItem);
                            } else {
                                mainViewModel.loadAllBooks();
                            }
                        } else {
                            mainViewModel.loadAllBooks();
                        }
                    });
                })
                .exceptionally(ex -> {
                    log.error("Помилка завантаження авторів", ex);
                    Platform.runLater(() -> mainViewModel.loadAllBooks());
                    return null;
                });

        navigationPresenter.loadSeries(seriesListView.getItems());
        navigationPresenter.loadGenres(genresTree, mainViewModel::loadBooksByGenre);
        navigationPresenter.loadGroups(groupsListView.getItems());
        mainViewModel.updateCollectionStats();
    }

    // ========== КОЛБЕК ПІСЛЯ ІМПОРТУ ==========
    private void onImportComplete() {
        log.info("Імпорт завершено, запускаємо повне перезавантаження...");
        new Thread(() -> {
            try {
                Thread.sleep(1500); // збільшено затримку для повного коміту
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            Platform.runLater(() -> {
                // 1. Оновлюємо словники
                mainViewModel.refreshDictionaries();

                // 2. Примусово оновлюємо дерево авторів (перезавантажуємо)
                navigationPresenter.refreshAuthors(authorsTree, mainViewModel::loadBooksByAuthor)
                        .thenRun(() -> {
                            // 3. Вибираємо першого автора
                            Platform.runLater(() -> {
                                TreeItem<LibraryNode> root = authorsTree.getRoot();
                                if (root != null && !root.getChildren().isEmpty()) {
                                    TreeItem<LibraryNode> firstItem = root.getChildren().get(0);
                                    if (firstItem.getValue() instanceof AuthorNode) {
                                        AuthorId id = ((AuthorNode) firstItem.getValue()).author().getId();
                                        log.info("✅ Вибрано першого автора після імпорту: {}", id.asString());
                                        authorsTree.getSelectionModel().select(firstItem);
                                        mainViewModel.loadBooksByAuthor(id);
                                    } else {
                                        mainViewModel.loadAllBooks();
                                    }
                                } else {
                                    log.warn("Дерево авторів порожнє після імпорту, завантажуємо всі книги");
                                    mainViewModel.loadAllBooks();
                                }
                            });
                        })
                        .thenRun(() -> {
                            // 4. Оновлюємо статистику та статус
                            Platform.runLater(() -> {
                                mainViewModel.updateCollectionStats();
                                statusBarPresenter.setStatus("Імпорт завершено. Бібліотеку повністю оновлено.");
                            });
                        })
                        .exceptionally(ex -> {
                            Platform.runLater(() -> {
                                log.error("Помилка оновлення після імпорту", ex);
                                statusBarPresenter.setStatus("Помилка: " + ex.getMessage());
                            });
                            return null;
                        });
            });
        }).start();
    }

    // ========== ОБРОБНИКИ ПОДІЙ ==========
    @FXML public void handleRefresh() {
        refreshPresenter.refreshAll(
                authorsTree,
                seriesListView.getItems(),
                genresTree,
                groupsListView.getItems()
        ).thenRun(() -> {
            Platform.runLater(() -> {
                if (authorsTree.getRoot() != null && !authorsTree.getRoot().getChildren().isEmpty()) {
                    authorsTree.getSelectionModel().selectFirst();
                }
            });
        });
        mainViewModel.updateCollectionStats();
    }

    @FXML public void handleImportFb2() {
        bookImportPresenter.importFb2();
    }

    @FXML public void handleImportInpx() {
        bookImportPresenter.importInpx();
    }

    @FXML public void handleImportDirectory() {
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

    @FXML public void handleNewCollection() {
        collectionPresenter.showCreateCollectionDialog(
                collectionsListView.getItems(),
                (Stage) bookTableView.getScene().getWindow()
        );
        collectionPresenter.loadCollections(collectionsListView.getItems());
        mainViewModel.updateCollectionStats();
    }

    @FXML public void handleRenameCollection() {
        Collection selected = collectionsListView.getSelectionModel().getSelectedItem();
        collectionPresenter.showRenameCollectionDialog(selected, collectionsListView.getItems());
    }

    @FXML public void handleDeleteCollection() {
        Collection selected = collectionsListView.getSelectionModel().getSelectedItem();
        collectionPresenter.showDeleteCollectionDialog(selected, collectionsListView.getItems());
    }

    @FXML public void handleSelectCollection() {
        Collection selected = collectionsListView.getSelectionModel().getSelectedItem();
        if (selected != null) {
            try {
                collectionManager.switchToCollection(selected);
                databaseInitializer.initializeCurrentCollection();
                refreshNavigationAndLoadFirstAuthor();
                mainViewModel.updateCollectionStats();
                statusBarPresenter.setStatus("Вибрано колекцію: " + selected.getName());
                Stage stage = (Stage) bookTableView.getScene().getWindow();
                if (stage != null) {
                    stage.setTitle("MyHomeLib Enterprise – " + selected.getName());
                }
            } catch (Exception e) {
                log.error("Помилка переключення колекції", e);
                dialogService.showError("Помилка", "Не вдалося переключити колекцію: " + e.getMessage());
            }
        }
    }

    @FXML public void handleOpenCollection() {
        libraryPresenter.openCollection((Stage) bookTableView.getScene().getWindow());
    }

    @FXML public void handleExport() {
        libraryPresenter.exportLibrary((Stage) bookTableView.getScene().getWindow());
    }

    @FXML public void handleShowColumns() {
        settingsPresenter.showColumnsDialog();
    }

    @FXML public void handleEditMetadata() {
        dialogService.showInfo("Інформація", "Редагування метаданих", "Функція поки що не реалізована");
    }

    @FXML public void handleDeleteBook() {
        dialogService.showInfo("Інформація", "Видалення книги", "Функція поки що не реалізована");
    }

    @FXML public void handleAbout() {
        dialogService.showInfo("Про програму", "MyHomeLib Enterprise",
                "Версія 1.0.0-SNAPSHOT\nJava 21, Spring Boot 3.5, JavaFX 21");
    }

    @FXML public void handleExit() {
        Platform.exit();
    }
}