package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.importer.api.ImporterApplicationService;
import com.myhomelibcorp.application.port.out.AuthorRepository;
import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.domain.model.navigation.LibraryNode;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.ui.presentation.BookDetailsPresenter;
import com.myhomelibcorp.ui.service.BackgroundExecutor;
import com.myhomelibcorp.ui.service.NavigationManager;
import com.myhomelibcorp.ui.service.SearchManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;

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
    private final NavigationManager navigationManager;

    // Навігація
    @FXML private TreeView<LibraryNode> authorsTree;
    @FXML private ListView<String> seriesListView;
    @FXML private ListView<String> genresListView;
    @FXML private ListView<String> groupsListView;
    @FXML private ListView<String> downloadsListView;

    // Таблиця книг
    @FXML private TableView<BookDto> bookTableView;
    @FXML private Label bookCountLabel;

    // Деталі книги
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

    // Пошук
    @FXML private TextField searchField;
    @FXML private ProgressIndicator searchIndicator;

    // Статус
    @FXML private Label statusLabel;
    @FXML private ProgressBar progressBar;

    private AuthorId currentAuthorId;

    @FXML
    public void initialize() {
        log.info("🔵 MainController.initialize() START");

        try {
            // 1. Прив'язка BookDetailsPresenter
            log.info("  📌 Binding BookDetailsPresenter...");
            bookDetailsPresenter.bind(
                    detailTitle, detailAuthors, detailSeries, detailGenres,
                    detailLanguage, detailRate, detailProgress,
                    detailFile, detailFolder, detailSize, detailAnnotation
            );
            log.info("  ✅ BookDetailsPresenter bound");

            // 2. Налаштування таблиці книг
            log.info("  📌 Setting up BookTable...");
            if (bookTableView == null) {
                log.error("❌ bookTableView is NULL! Check FXML fx:id");
            } else {
                booksTableController.setupBookTable(bookTableView, bookCountLabel);
                log.info("  ✅ BookTable setup done");
            }

            // 3. Налаштування пошуку
            log.info("  📌 Setting up Search...");
            if (searchField == null) {
                log.error("❌ searchField is NULL! Check FXML fx:id");
            } else {
                searchController.setupSearch(searchField, searchIndicator, bookTableView, statusLabel);
                log.info("  ✅ Search setup done");
            }

            // 4. Завантаження авторів
            log.info("  📌 Loading authors...");
            navigationManager.loadAuthors(
                    authorsTree,
                    this::onAuthorSelected,
                    this::onAuthorsLoaded
            );
            log.info("  ✅ Authors loading started (async)");

            // 5. Налаштування списків
            log.info("  📌 Setting up lists...");
            setupLists();
            log.info("  ✅ Lists setup done");

            // 6. Оновлення таблиці книг
            log.info("  📌 Refreshing book table...");
            booksTableController.refresh();
            log.info("  ✅ Book table refresh started (async)");

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
            log.info("✅ Вибрано першого автора");
        } else {
            log.warn("⚠️ Немає авторів для вибору");
        }
    }

    private void onAuthorSelected(AuthorId authorId) {
        log.info("👤 Вибрано автора з ID: {}", authorId != null ? authorId.asString() : "null");
        if (authorId == null) {
            log.warn("⚠️ authorId = null, пропускаємо");
            return;
        }
        currentAuthorId = authorId;
        booksTableController.loadBooksByAuthor(authorId);
        String authorName = authorRepository.findById(authorId)
                .map(a -> a.getFullName())
                .orElse("Невідомий автор");
        statusLabel.setText("Книги автора: " + authorName);
        log.info("✅ Завантажено книги для автора: {}", authorName);
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

    // ---------- ОБРОБНИКИ МЕНЮ ----------
    @FXML
    public void handleOpenCollection() {
        log.info("Відкриття колекції");
        statusLabel.setText("Відкриття колекції...");
        // TODO: реалізація
    }

    @FXML
    public void handleNewCollection() {
        log.info("Створення нової колекції");
        statusLabel.setText("Створення колекції...");
        // TODO: реалізація
    }

    @FXML
    public void handleAddGroup() {
        log.info("Додати групу");
        // TODO: реалізація
    }

    @FXML
    public void handleEditGroup() {
        log.info("Редагувати групу");
        // TODO: реалізація
    }

    @FXML
    public void handleDeleteGroup() {
        log.info("Видалити групу");
        // TODO: реалізація
    }

    @FXML
    public void handleEditMetadata() {
        log.info("Редагувати метадані");
        // TODO: реалізація
    }

    @FXML
    public void handleDeleteBook() {
        log.info("Видалити книгу");
        // TODO: реалізація
    }

    @FXML
    public void handleShowColumns() {
        log.info("Показати колонки");
        // TODO: реалізація
    }

    @FXML
    public void handleExport() {
        log.info("Експорт");
        // TODO: реалізація
    }

    @FXML
    public void handleImportFb2() {
        log.info("📂 Імпорт FB2");
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Виберіть FB2 файл");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("FB2 файли", "*.fb2", "*.fbd")
        );
        File file = fileChooser.showOpenDialog(null);
        if (file != null) {
            importFile(file.toPath());
        }
    }

    @FXML
    public void handleImportInpx() {
        log.info("📂 Імпорт INPX");
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Виберіть INPX файл");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("INPX файли", "*.inpx", "*.inp")
        );
        File file = fileChooser.showOpenDialog(null);
        if (file != null) {
            importFile(file.toPath());
        }
    }

    @FXML
    public void handleImportDirectory() {
        log.info("📁 Імпорт каталогу");
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Виберіть каталог з книгами");
        File dir = directoryChooser.showDialog(null);
        if (dir != null && dir.isDirectory()) {
            importDirectory(dir.toPath());
        }
    }

    @FXML
    public void handleRefresh() {
        log.info("🔄 Оновлення");
        statusLabel.setText("Оновлення...");
        booksTableController.refresh();
        navigationManager.loadAuthors(authorsTree, this::onAuthorSelected, this::onAuthorsLoaded);
        statusLabel.setText("Таблицю оновлено");
    }

    @FXML
    public void handleExit() {
        Platform.exit();
    }

    @FXML
    public void handleAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Про програму");
        alert.setHeaderText("MyHomeLib Enterprise");
        alert.setContentText("Версія 1.0.0-SNAPSHOT\n\nJava-версія MyHomeLib\nJava 21, Spring Boot 3.4, JavaFX 21");
        alert.showAndWait();
    }

    // ---------- МЕТОДИ ІМПОРТУ ----------

    private void importFile(Path filePath) {
        log.info("📥 Імпорт файлу: {}", filePath);
        progressBar.setVisible(true);
        statusLabel.setText("Імпорт: " + filePath.getFileName());

        backgroundExecutor.submit(() -> {
            int count = importerService.importInpx(filePath);
            Platform.runLater(() -> {
                progressBar.setVisible(false);
                statusLabel.setText("✅ Імпорт завершено. Додано " + count + " книг");
                booksTableController.refresh();
                navigationManager.loadAuthors(authorsTree, this::onAuthorSelected, this::onAuthorsLoaded);
            });
            return count;
        }).exceptionally(ex -> {
            Platform.runLater(() -> {
                progressBar.setVisible(false);
                statusLabel.setText("❌ Помилка імпорту: " + ex.getMessage());
                log.error("Помилка імпорту", ex);
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Помилка імпорту");
                alert.setContentText(ex.getMessage());
                alert.showAndWait();
            });
            return null;
        });
    }

    private void importDirectory(Path dirPath) {
        log.info("📁 Імпорт каталогу: {}", dirPath);
        progressBar.setVisible(true);
        statusLabel.setText("Імпорт каталогу: " + dirPath.getFileName());

        backgroundExecutor.submit(() -> {
            AtomicBoolean cancelFlag = new AtomicBoolean(false);
            DoubleConsumer progressConsumer = progress ->
                    Platform.runLater(() -> progressBar.setProgress(progress));
            int count = importerService.importDirectory(dirPath, progressConsumer, cancelFlag);
            Platform.runLater(() -> {
                progressBar.setVisible(false);
                statusLabel.setText("✅ Імпорт каталогу завершено. Додано " + count + " книг");
                booksTableController.refresh();
                navigationManager.loadAuthors(authorsTree, this::onAuthorSelected, this::onAuthorsLoaded);
            });
            return count;
        }).exceptionally(ex -> {
            Platform.runLater(() -> {
                progressBar.setVisible(false);
                statusLabel.setText("❌ Помилка імпорту каталогу: " + ex.getMessage());
                log.error("Помилка імпорту каталогу", ex);
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Помилка імпорту");
                alert.setContentText(ex.getMessage());
                alert.showAndWait();
            });
            return null;
        });
    }

    public void shutdown() {
        log.info("🛑 MainController завершує роботу");
    }
}