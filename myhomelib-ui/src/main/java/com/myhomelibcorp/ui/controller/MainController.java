package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.importer.api.ImporterApplicationService;
import com.myhomelibcorp.application.port.out.AuthorRepository;
import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.application.port.out.GenreService; // ← порт
import com.myhomelibcorp.domain.model.navigation.LibraryNode;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.ui.presentation.BookDetailsPresenter;
import com.myhomelibcorp.ui.service.BackgroundExecutor;
import com.myhomelibcorp.ui.service.NavigationManager;
import com.myhomelibcorp.ui.service.SearchManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class MainController {

    private final ImporterApplicationService importerService;
    private final BookQueryRepository bookQueryRepository;
    private final AuthorRepository authorRepository;
    private final SearchManager searchManager;
    private final BackgroundExecutor backgroundExecutor;
    private final BookDetailsPresenter bookDetailsPresenter;
    private final BooksTableController booksTableController;
    private final SearchController searchController;
    private final ImportController importController;
    private final NavigationManager navigationManager;
    private final GenreService genreService; // ← порт

    @FXML private TreeView<LibraryNode> authorsTree;
    @FXML private ListView<String> seriesListView;
    @FXML private ListView<String> genresListView;
    @FXML private ListView<String> groupsListView;
    @FXML private ListView<String> downloadsListView;

    @FXML private TableView<BookDto> bookTableView;
    @FXML private Label bookCountLabel;

    @FXML private Label detailTitle;
    @FXML private Label detailAuthors;
    @FXML private Label detailSeries;
    @FXML private Label detailGenres;
    @FXML private Label detailLanguage;
    @FXML private Label detailRate;
    @FXML private Label detailProgress;
    @FXML private Label detailFile;
    @FXML private Label detailFolder;
    @FXML private Label detailSize;
    @FXML private TextArea detailAnnotation;

    @FXML private TextField searchField;
    @FXML private ProgressIndicator searchIndicator;

    @FXML private Label statusLabel;
    @FXML private ProgressBar progressBar;

    private AuthorId currentAuthorId;

    @FXML
    public void initialize() {
        log.info("🔵 MainController.initialize() START");

        try {
            bookDetailsPresenter.bind(
                    detailTitle, detailAuthors, detailSeries, detailGenres,
                    detailLanguage, detailRate, detailProgress,
                    detailFile, detailFolder, detailSize, detailAnnotation
            );

            booksTableController.setupBookTable(bookTableView, bookCountLabel);
            searchController.setupSearch(searchField, searchIndicator, bookTableView, statusLabel);
            importController.setupImport(progressBar, statusLabel, this::onImportComplete);

            navigationManager.loadAuthors(
                    authorsTree,
                    this::onAuthorSelected,
                    this::onAuthorsLoaded
            );

            setupLists();
            booksTableController.refresh();

            log.info("🟢 MainController.initialize() END SUCCESS");
        } catch (Exception e) {
            log.error("🔴 Помилка в initialize()", e);
            throw new RuntimeException("Помилка ініціалізації MainController", e);
        }
    }

    private void onAuthorsLoaded() {
        log.info("📚 Автори завантажені, вибираємо першого...");
        TreeItem<LibraryNode> root = authorsTree.getRoot();
        if (root != null && !root.getChildren().isEmpty()) {
            TreeItem<LibraryNode> first = root.getChildren().get(0);
            authorsTree.getSelectionModel().select(first);
        }
    }

    private void onAuthorSelected(AuthorId authorId) {
        if (authorId == null) return;
        currentAuthorId = authorId;
        booksTableController.loadBooksByAuthor(authorId);
        String authorName = authorRepository.findById(authorId)
                .map(a -> a.getFullName())
                .orElse("Невідомий автор");
        statusLabel.setText("Книги автора: " + authorName);
    }

    private void setupLists() {
        seriesListView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> {
                    if (newVal != null) {
                        booksTableController.filterBooksBySeries(newVal);
                        statusLabel.setText("Показано серію: " + newVal);
                    }
                }
        );

        refreshGenreList();
        genresListView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> {
                    if (newVal != null) {
                        booksTableController.filterBooksByGenre(newVal);
                        statusLabel.setText("Показано жанр: " + newVal);
                    }
                }
        );

        groupsListView.getItems().addAll("Favorites", "To Read", "Мої улюблені");
        downloadsListView.getItems().addAll("Завантаження 1", "Завантаження 2");
    }

    private void refreshGenreList() {
        genresListView.getItems().setAll(genreService.getAllGenreNames());
    }

    private void onImportComplete() {
        refreshGenreList();
        booksTableController.refresh();
        navigationManager.loadAuthors(authorsTree, this::onAuthorSelected, this::onAuthorsLoaded);
    }

    // ---------- ОБРОБНИКИ МЕНЮ ----------
    @FXML public void handleOpenCollection() { /* ... */ }
    @FXML public void handleNewCollection() { /* ... */ }
    @FXML public void handleAddGroup() { /* ... */ }
    @FXML public void handleEditGroup() { /* ... */ }
    @FXML public void handleDeleteGroup() { /* ... */ }
    @FXML public void handleEditMetadata() { /* ... */ }
    @FXML public void handleDeleteBook() { /* ... */ }
    @FXML public void handleShowColumns() { /* ... */ }
    @FXML public void handleExport() { /* ... */ }

    @FXML public void handleImportFb2() {
        importController.importFb2();
    }

    @FXML public void handleImportInpx() {
        importController.importInpx();
    }

    @FXML public void handleImportDirectory() {
        importController.importDirectory();
    }

    @FXML
    public void handleRefresh() {
        log.info("🔄 Оновлення");
        booksTableController.refresh();
        navigationManager.loadAuthors(authorsTree, this::onAuthorSelected, this::onAuthorsLoaded);
    }

    @FXML public void handleExit() { Platform.exit(); }

    @FXML
    public void handleAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Про програму");
        alert.setHeaderText("MyHomeLib Enterprise");
        alert.setContentText("Версія 1.0.0-SNAPSHOT\n\nJava-версія MyHomeLib\nJava 21, Spring Boot 3.4, JavaFX 21");
        alert.showAndWait();
    }

    public void shutdown() {
        log.info("🛑 MainController завершує роботу");
        if (backgroundExecutor != null) {
            backgroundExecutor.shutdown();
        }
    }
}
