package com.myhomelibcorp.ui.presentation.controller;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.importer.api.ImporterApplicationService;
import com.myhomelibcorp.application.port.out.AuthorRepository;
import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.navigation.LibraryNode;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.ui.presentation.BookDetailsPresenter;
import com.myhomelibcorp.ui.service.BackgroundExecutor;
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
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;
import java.util.stream.Collectors;

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

    // Навігація – використовуємо LibraryNode
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
        log.info("MainController ініціалізовано");

        bookDetailsPresenter.bind(
                detailTitle, detailAuthors, detailSeries, detailGenres,
                detailLanguage, detailRate, detailProgress,
                detailFile, detailSize, detailAnnotation
        );

        loadAuthors();
        setupLists();

        bookTableView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> bookDetailsPresenter.showBookDetails(newVal)
        );

        searchManager.bindLiveSearch(
                searchField,
                bookTableView,
                statusLabel,
                searchIndicator
        );

        refreshBookTable();
    }

    // ==================== ЗАВАНТАЖЕННЯ ДАНИХ ====================

    private void loadAuthors() {
        backgroundExecutor.submit(() -> {
            List<Author> authors = authorRepository.findAll();
            Platform.runLater(() -> {
                TreeItem<LibraryNode> root = new TreeItem<>(null);
                root.setExpanded(true);

                authors.stream()
                        .sorted(Comparator.comparing(Author::getLastName))
                        .forEach(author -> {
                            TreeItem<LibraryNode> item = new TreeItem<>(
                                    new com.myhomelibcorp.domain.model.navigation.AuthorNode(author)
                            );
                            root.getChildren().add(item);
                        });

                authorsTree.setRoot(root);
                authorsTree.setShowRoot(false);

                // CellFactory для відображення різних типів вузлів
                authorsTree.setCellFactory(tv -> new TreeCell<>() {
                    @Override
                    protected void updateItem(LibraryNode item, boolean empty) {
                        super.updateItem(item, empty);
                        if (empty || item == null) {
                            setText(null);
                        } else {
                            setText(item.toString());
                        }
                    }
                });

                // Слухач вибору
                authorsTree.getSelectionModel().selectedItemProperty().addListener(
                        (obs, oldVal, newVal) -> {
                            if (newVal != null && newVal.getValue() != null) {
                                LibraryNode node = newVal.getValue();
                                if (node instanceof com.myhomelibcorp.domain.model.navigation.AuthorNode authorNode) {
                                    Author author = authorNode.author();
                                    if (author != null) {
                                        currentAuthorId = author.getId();
                                        filterBooksByAuthor(currentAuthorId);
                                    }
                                }
                            } else {
                                currentAuthorId = null;
                                refreshBookTable();
                            }
                        }
                );

                log.info("Завантажено {} авторів", authors.size());
            });
            return null;
        });
    }

    private void setupLists() {
        seriesListView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> {
                    if (newVal != null) {
                        filterBooksBySeries(newVal);
                    }
                }
        );

        genresListView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> {
                    if (newVal != null) {
                        filterBooksByGenre(newVal);
                    }
                }
        );

        groupsListView.getItems().addAll("Favorites", "To Read", "Мої улюблені");
        downloadsListView.getItems().addAll("Завантаження 1", "Завантаження 2");
    }

    private void refreshBookTable() {
        backgroundExecutor.submit(() -> {
            List<Book> books = bookQueryRepository.findAll(10000, 0);
            books.sort(Comparator.comparing(Book::getSeries, Comparator.nullsLast(String::compareTo))
                    .thenComparing(Book::getSequenceNumber, Comparator.nullsLast(Integer::compareTo)));
            List<BookDto> dtos = books.stream()
                    .map(this::toDto)
                    .collect(Collectors.toList());

            Platform.runLater(() -> {
                bookTableView.getItems().setAll(dtos);
                bookCountLabel.setText(dtos.size() + " книг");
                if (!dtos.isEmpty()) {
                    bookTableView.getSelectionModel().selectFirst();
                } else {
                    bookDetailsPresenter.clearDetails();
                }
                statusLabel.setText("Показано всі книги");
            });
            return null;
        });
    }

    // ==================== ФІЛЬТРАЦІЯ ====================

    private void filterBooksByAuthor(AuthorId authorId) {
        backgroundExecutor.submit(() -> {
            List<Book> books = bookQueryRepository.findByAuthorId(authorId, 10000, 0);
            books.sort(Comparator.comparing(Book::getSeries, Comparator.nullsLast(String::compareTo))
                    .thenComparing(Book::getSequenceNumber, Comparator.nullsLast(Integer::compareTo)));
            List<BookDto> dtos = books.stream()
                    .map(this::toDto)
                    .collect(Collectors.toList());

            Platform.runLater(() -> {
                bookTableView.getItems().setAll(dtos);
                bookCountLabel.setText(dtos.size() + " книг");
                if (!dtos.isEmpty()) {
                    bookTableView.getSelectionModel().selectFirst();
                } else {
                    bookDetailsPresenter.clearDetails();
                }
                String authorName = authorRepository.findById(authorId)
                        .map(Author::getFullName)
                        .orElse("Невідомий автор");
                statusLabel.setText("Книги автора: " + authorName);
            });
            return null;
        });
    }

    private void filterBooksBySeries(String series) {
        backgroundExecutor.submit(() -> {
            List<Book> allBooks = bookQueryRepository.findAll(10000, 0);
            List<BookDto> filtered = allBooks.stream()
                    .filter(b -> series.equals(b.getSeries()))
                    .sorted(Comparator.comparing(Book::getSequenceNumber, Comparator.nullsLast(Integer::compareTo)))
                    .map(this::toDto)
                    .collect(Collectors.toList());

            Platform.runLater(() -> {
                bookTableView.getItems().setAll(filtered);
                bookCountLabel.setText(filtered.size() + " книг");
                if (!filtered.isEmpty()) {
                    bookTableView.getSelectionModel().selectFirst();
                } else {
                    bookDetailsPresenter.clearDetails();
                }
                statusLabel.setText("Показано серію: " + series);
            });
            return null;
        });
    }

    private void filterBooksByGenre(String genre) {
        backgroundExecutor.submit(() -> {
            List<Book> allBooks = bookQueryRepository.findAll(10000, 0);
            List<BookDto> filtered = allBooks.stream()
                    .filter(b -> b.genresText() != null && b.genresText().contains(genre))
                    .sorted(Comparator.comparing(Book::getSeries, Comparator.nullsLast(String::compareTo)))
                    .map(this::toDto)
                    .collect(Collectors.toList());

            Platform.runLater(() -> {
                bookTableView.getItems().setAll(filtered);
                bookCountLabel.setText(filtered.size() + " книг");
                if (!filtered.isEmpty()) {
                    bookTableView.getSelectionModel().selectFirst();
                } else {
                    bookDetailsPresenter.clearDetails();
                }
                statusLabel.setText("Показано жанр: " + genre);
            });
            return null;
        });
    }

    // ==================== ДОПОМІЖНІ МЕТОДИ ====================

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

    // ==================== ОБРОБНИКИ МЕНЮ ====================

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
        log.info("Імпорт FB2");
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
        log.info("Імпорт INPX");
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
        log.info("Імпорт каталогу");
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
        refreshBookTable();
        loadAuthors();
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
        alert.setContentText("Версія 1.0.0-SNAPSHOT\n\n" +
                "Java-версія MyHomeLib\n" +
                "Архітектура: Hexagonal + CQRS + MVVM\n" +
                "Java 21, Spring Boot 3.4, JavaFX 21");
        alert.showAndWait();
    }

    // ==================== ІМПОРТ ====================

    private void importFile(Path filePath) {
        progressBar.setVisible(true);
        statusLabel.setText("Імпорт: " + filePath.getFileName());

        backgroundExecutor.submit(() -> {
            int count = importerService.importInpx(filePath);
            Platform.runLater(() -> {
                progressBar.setVisible(false);
                statusLabel.setText("Імпорт завершено. Додано " + count + " книг");
                refreshBookTable();
                loadAuthors();
            });
            return count;
        }).exceptionally(ex -> {
            Platform.runLater(() -> {
                progressBar.setVisible(false);
                statusLabel.setText("Помилка імпорту: " + ex.getMessage());
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
        progressBar.setVisible(true);
        statusLabel.setText("Імпорт каталогу: " + dirPath.getFileName());

        backgroundExecutor.submit(() -> {
            AtomicBoolean cancelFlag = new AtomicBoolean(false);
            DoubleConsumer progressConsumer = progress ->
                    Platform.runLater(() -> progressBar.setProgress(progress));
            int count = importerService.importDirectory(dirPath, progressConsumer, cancelFlag);
            Platform.runLater(() -> {
                progressBar.setVisible(false);
                statusLabel.setText("Імпорт каталогу завершено. Додано " + count + " книг");
                refreshBookTable();
                loadAuthors();
            });
            return count;
        }).exceptionally(ex -> {
            Platform.runLater(() -> {
                progressBar.setVisible(false);
                statusLabel.setText("Помилка імпорту каталогу: " + ex.getMessage());
                log.error("Помилка імпорту каталогу", ex);
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Помилка імпорту");
                alert.setContentText(ex.getMessage());
                alert.showAndWait();
            });
            return null;
        });
    }

    /**
     * Завершення роботи – викликається з MyHomeLibApp.stop()
     */
    public void shutdown() {
        log.info("MainController завершує роботу");
    }
}