package com.myhomelibcorp.ui.presentation.controller;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.importer.api.ImporterApplicationService;
import com.myhomelibcorp.application.port.out.AuthorRepository;
import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.navigation.*;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class MainController {

    private final ImporterApplicationService importerService;
    private final BookQueryRepository bookQueryRepository;
    private final AuthorRepository authorRepository;

    // UI компоненти
    @FXML private TreeView<LibraryNode> authorsTree;
    @FXML private ListView<String> seriesListView;
    @FXML private ListView<String> genresListView;
    @FXML private TextField searchTabField;
    @FXML private ListView<BookDto> searchResultsList;
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
    @FXML private Label detailSize;
    @FXML private TextArea detailAnnotation;

    @FXML private TextField searchField;
    @FXML private Label statusLabel;
    @FXML private ProgressBar progressBar;
    @FXML private Button cancelButton;

    // Пагінація
    private static final int PAGE_SIZE = 100;
    private int currentPage = 0;
    private int totalBooks = 0;

    // Фільтри
    private AuthorId currentAuthorId;
    private String currentSeriesFilter;
    private String currentGenreFilter;

    // Для скасування
    private final AtomicBoolean importCancelled = new AtomicBoolean(false);
    private Task<?> currentImportTask;

    @FXML
    public void initialize() {
        log.info("MainController ініціалізовано");

        // Налаштування відображення вузлів дерева
        authorsTree.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(LibraryNode item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    String text = switch (item) {
                        case AuthorNode an -> an.author().getFullName();
                        case SeriesNode sn -> sn.series().getName();
                        case GenreNode gn -> gn.genre().getName();
                        case CollectionNode cn -> cn.collection().getName();
                        case GroupNode gn -> gn.group().getName();
                        case BookNode bn -> bn.book().getTitle();
                        default -> item.toString();
                    };
                    setText(text);
                }
            }
        });

        loadAuthorsAndSelectFirst();
        setupLists();
        setupTable();
        setupCancelButton();

        searchField.setOnAction(event -> handleSearch());
    }

    private void loadAuthorsAndSelectFirst() {
        try {
            List<Author> authors = authorRepository.findAll();
            TreeItem<LibraryNode> root = new TreeItem<>(new CollectionNode(null));
            root.setExpanded(true);

            authors.stream()
                    .sorted(Comparator.comparing(Author::getLastName))
                    .forEach(author -> {
                        TreeItem<LibraryNode> authorItem = new TreeItem<>(new AuthorNode(author));
                        root.getChildren().add(authorItem);
                    });

            authorsTree.setRoot(root);
            authorsTree.setShowRoot(false);

            // Обробка вибору
            authorsTree.getSelectionModel().selectedItemProperty().addListener(
                    (obs, oldVal, newVal) -> {
                        if (newVal != null) {
                            LibraryNode node = newVal.getValue();
                            switch (node) {
                                case AuthorNode authorNode -> {
                                    currentAuthorId = authorNode.author().getId();
                                    currentSeriesFilter = null;
                                    currentGenreFilter = null;
                                    loadBooksPage(0);
                                }
                                case CollectionNode ignored -> {
                                    currentAuthorId = null;
                                    currentSeriesFilter = null;
                                    currentGenreFilter = null;
                                    loadBooksPage(0);
                                }
                                default -> {}
                            }
                        }
                    }
            );

            // Автоматично вибираємо першого автора, якщо він є
            if (!root.getChildren().isEmpty()) {
                TreeItem<LibraryNode> firstAuthor = root.getChildren().get(0);
                authorsTree.getSelectionModel().select(firstAuthor);
                // Це викличе listener і завантажить книги першого автора
            } else {
                // Якщо авторів немає, завантажуємо всі книги
                loadBooksPage(0);
            }

            log.info("Завантажено {} авторів", authors.size());
        } catch (Exception e) {
            log.error("Помилка завантаження авторів", e);
            statusLabel.setText("Помилка завантаження авторів: " + e.getMessage());
            loadBooksPage(0);
        }
    }

    private void setupTable() {
        bookTableView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> showBookDetails(newVal));
    }

    private void setupCancelButton() {
        if (cancelButton != null) {
            cancelButton.setVisible(false);
            cancelButton.setOnAction(e -> cancelImport());
        }
    }

    private void setupLists() {
        seriesListView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> {
                    if (newVal != null) {
                        currentSeriesFilter = newVal;
                        currentAuthorId = null;
                        currentGenreFilter = null;
                        loadBooksPage(0);
                    }
                }
        );

        genresListView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> {
                    if (newVal != null) {
                        currentGenreFilter = newVal;
                        currentAuthorId = null;
                        currentSeriesFilter = null;
                        loadBooksPage(0);
                    }
                }
        );

        groupsListView.getItems().addAll("Favorites", "To Read", "Мої улюблені");
        downloadsListView.getItems().addAll("Завантаження 1", "Завантаження 2");
    }

    private void loadBooksPage(int page) {
        try {
            int offset = page * PAGE_SIZE;
            List<Book> books;

            if (currentAuthorId != null) {
                books = bookQueryRepository.findByAuthorId(currentAuthorId, PAGE_SIZE, offset);
            } else if (currentSeriesFilter != null) {
                books = bookQueryRepository.findAll(10000, 0).stream()
                        .filter(b -> currentSeriesFilter.equals(b.getSeries()))
                        .skip(offset)
                        .limit(PAGE_SIZE)
                        .collect(Collectors.toList());
            } else if (currentGenreFilter != null) {
                books = bookQueryRepository.findAll(10000, 0).stream()
                        .filter(b -> b.genresText() != null && b.genresText().contains(currentGenreFilter))
                        .skip(offset)
                        .limit(PAGE_SIZE)
                        .collect(Collectors.toList());
            } else {
                books = bookQueryRepository.findAll(PAGE_SIZE, offset);
            }

            // Сортування за серією, потім за номером у серії
            books.sort(Comparator.comparing(Book::getSeries, Comparator.nullsLast(String::compareTo))
                    .thenComparing(Book::getSequenceNumber, Comparator.nullsLast(Integer::compareTo)));

            totalBooks = bookQueryRepository.getTotalCount();

            List<BookDto> dtos = books.stream()
                    .map(this::toDto)
                    .collect(Collectors.toList());

            bookTableView.getItems().setAll(dtos);
            bookCountLabel.setText(dtos.size() + " / " + totalBooks + " книг");
            currentPage = page;

            if (!dtos.isEmpty()) {
                bookTableView.getSelectionModel().selectFirst();
            } else {
                clearDetails();
            }
        } catch (Exception e) {
            log.error("Помилка завантаження сторінки", e);
            statusLabel.setText("Помилка: " + e.getMessage());
        }
    }

    private BookDto toDto(Book book) {
        return BookDto.builder()
                .title(book.getTitle())
                .authorsText(book.authorsText())
                .series(book.getSeries())
                .genresText(book.genresText())
                .sequenceNumber(book.getSequenceNumber())
                .rate(book.getRate())
                .progress(book.getProgress())
                .language(book.getLanguage() != null ? book.getLanguage().toString() : "")
                .fileSize(book.getFileSize())
                .fileName(book.getFileName())
                .folder(book.getFolder())
                .updateDate(book.getUpdateDate())
                .annotation(book.getAnnotation())
                .build();
    }

    private void showBookDetails(BookDto book) {
        if (book == null) {
            clearDetails();
            return;
        }
        detailTitle.setText(book.getTitle() != null ? book.getTitle() : "Без назви");
        detailAuthors.setText("Автори: " + (book.getAuthorsText() != null ? book.getAuthorsText() : ""));
        detailSeries.setText("Серія: " + (book.getSeries() != null ? book.getSeries() : ""));
        detailGenres.setText("Жанри: " + (book.getGenresText() != null ? book.getGenresText() : ""));
        detailLanguage.setText("Мова: " + (book.getLanguage() != null ? book.getLanguage() : ""));
        detailRate.setText("Рейтинг: " + book.getRate());
        detailProgress.setText("Прогрес: " + book.getProgress() + "%");

        // Правильне відображення імені файлу
        String displayName = book.getFileName();
        if (book.getFolder() != null && !book.getFolder().isEmpty()) {
            displayName = book.getFolder() + "/" + displayName;
        }
        detailFile.setText("Файл: " + (displayName != null ? displayName : ""));

        detailSize.setText("Розмір: " + book.getFileSizeFormatted());
        detailAnnotation.setText(book.getAnnotation() != null ? book.getAnnotation() : "");
    }

    private void clearDetails() {
        detailTitle.setText("Назва");
        detailAuthors.setText("Автори");
        detailSeries.setText("Серія");
        detailGenres.setText("Жанри");
        detailLanguage.setText("Мова");
        detailRate.setText("Рейтинг: 0");
        detailProgress.setText("Прогрес: 0%");
        detailFile.setText("Файл: ");
        detailSize.setText("Розмір: ");
        detailAnnotation.setText("");
    }

    // --- Обробники дій ---

    @FXML
    public void handleSearchTab() {
        String query = searchTabField.getText();
        if (query == null || query.isBlank()) return;
        try {
            List<Book> books = bookQueryRepository.search(query, 100);
            List<BookDto> dtos = books.stream().map(this::toDto).collect(Collectors.toList());
            searchResultsList.getItems().setAll(dtos);
            statusLabel.setText("Результатів пошуку: " + dtos.size());
        } catch (Exception e) {
            log.error("Помилка пошуку", e);
            statusLabel.setText("Помилка пошуку: " + e.getMessage());
        }
    }

    @FXML
    public void handleSearch() {
        String query = searchField.getText();
        if (query == null || query.isBlank()) {
            currentAuthorId = null;
            currentSeriesFilter = null;
            currentGenreFilter = null;
            loadBooksPage(0);
            statusLabel.setText("Показано всі книги");
            return;
        }
        try {
            List<Book> books = bookQueryRepository.search(query, 100);
            List<BookDto> dtos = books.stream().map(this::toDto).collect(Collectors.toList());
            bookTableView.getItems().setAll(dtos);
            bookCountLabel.setText(dtos.size() + " книг");
            statusLabel.setText("Результатів пошуку: " + dtos.size());
            if (!dtos.isEmpty()) {
                bookTableView.getSelectionModel().selectFirst();
            } else {
                clearDetails();
            }
        } catch (Exception e) {
            log.error("Помилка пошуку", e);
            statusLabel.setText("Помилка пошуку: " + e.getMessage());
        }
    }

    @FXML
    public void handleImportFb2() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Виберіть FB2 файл");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("FB2 файли", "*.fb2", "*.fbd")
        );
        File file = fileChooser.showOpenDialog(null);
        if (file != null) importFile(file.toPath());
    }

    @FXML
    public void handleImportInpx() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Виберіть INPX файл");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("INPX файли", "*.inpx", "*.inp")
        );
        File file = fileChooser.showOpenDialog(null);
        if (file != null) importFile(file.toPath());
    }

    @FXML
    public void handleImportDirectory() {
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Виберіть каталог з книгами");
        File dir = directoryChooser.showDialog(null);
        if (dir != null && dir.isDirectory()) {
            importDirectory(dir.toPath());
        }
    }

    @FXML
    public void handleRefresh() {
        log.info("Оновлення");
        statusLabel.setText("Оновлення...");
        loadAuthorsAndSelectFirst();
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
        alert.setContentText("Версія 1.0.0-SNAPSHOT\n\nJava-версія MyHomeLib\nАрхітектура: Hexagonal + CQRS + MVVM\nJava 21, Spring Boot 3.4, JavaFX 21");
        alert.showAndWait();
    }

    // --- Імпорт з прогресом ---

    private void importFile(Path filePath) {
        progressBar.setVisible(true);
        statusLabel.setText("Імпорт: " + filePath.getFileName());

        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                return importerService.importInpx(filePath);
            }
        };

        task.setOnSucceeded(e -> {
            progressBar.setVisible(false);
            statusLabel.setText("Імпорт завершено. Додано " + task.getValue() + " книг");
            loadAuthorsAndSelectFirst();
        });

        task.setOnFailed(e -> {
            progressBar.setVisible(false);
            statusLabel.setText("Помилка імпорту: " + task.getException().getMessage());
            log.error("Помилка імпорту", task.getException());
            showErrorAlert("Помилка імпорту", task.getException().getMessage());
        });

        new Thread(task).start();
    }

    private void importDirectory(Path dirPath) {
        importCancelled.set(false);
        if (cancelButton != null) {
            cancelButton.setVisible(true);
            cancelButton.setDisable(false);
            cancelButton.setText("Скасувати");
        }

        progressBar.setVisible(true);
        progressBar.setProgress(0);
        statusLabel.setText("Імпорт каталогу: " + dirPath.getFileName());

        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                return importerService.importDirectory(
                        dirPath,
                        progress -> updateProgress((long) (progress * 100), 100),
                        importCancelled
                );
            }
        };

        currentImportTask = task;

        task.setOnSucceeded(e -> {
            progressBar.setVisible(false);
            if (cancelButton != null) cancelButton.setVisible(false);
            statusLabel.setText("Імпорт каталогу завершено. Додано " + task.getValue() + " книг");
            loadAuthorsAndSelectFirst();
        });

        task.setOnFailed(e -> {
            progressBar.setVisible(false);
            if (cancelButton != null) cancelButton.setVisible(false);
            Throwable ex = task.getException();
            if (ex != null && ex.getMessage() != null && ex.getMessage().contains("cancelled")) {
                statusLabel.setText("Імпорт скасовано");
            } else {
                statusLabel.setText("Помилка імпорту каталогу: " + ex.getMessage());
                log.error("Помилка імпорту каталогу", ex);
                showErrorAlert("Помилка імпорту", ex.getMessage());
            }
        });

        new Thread(task).start();
    }

    private void cancelImport() {
        if (currentImportTask != null && !currentImportTask.isDone()) {
            importCancelled.set(true);
            if (cancelButton != null) {
                cancelButton.setDisable(true);
                cancelButton.setText("Скасування...");
            }
            statusLabel.setText("Скасування імпорту...");
            log.info("Запит на скасування імпорту");
        }
    }

    private void showErrorAlert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(content);
        alert.showAndWait();
    }

    // --- Заглушки для меню ---
    @FXML public void handleOpenCollection() { log.info("Відкриття колекції"); statusLabel.setText("Відкриття колекції..."); }
    @FXML public void handleNewCollection() { log.info("Створення колекції"); statusLabel.setText("Створення колекції..."); }
    @FXML public void handleAddGroup() { log.info("Додати групу"); }
    @FXML public void handleEditGroup() { log.info("Редагувати групу"); }
    @FXML public void handleDeleteGroup() { log.info("Видалити групу"); }
    @FXML public void handleEditMetadata() { log.info("Редагувати метадані"); }
    @FXML public void handleDeleteBook() { log.info("Видалити книгу"); }
    @FXML public void handleShowColumns() { log.info("Показати колонки"); }
    @FXML public void handleExport() { log.info("Експорт"); }
}