package com.myhomelibcorp.ui.presentation.controller;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.importer.api.ImporterApplicationService;
import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.domain.model.book.Book;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class MainController {

    private final ImporterApplicationService importerService;
    private final BookQueryRepository bookQueryRepository;

    // Навігація (вкладки)
    @FXML private TreeView<String> authorsTree;
    @FXML private ListView<String> seriesListView;
    @FXML private ListView<String> genresListView;
    @FXML private TextField searchTabField;
    @FXML private ListView<BookDto> searchResultsList;
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

    // Пошук (верхній)
    @FXML private TextField searchField;

    // Статус
    @FXML private Label statusLabel;
    @FXML private ProgressBar progressBar;

    // Список всіх книг (для фільтрації)
    private ObservableList<BookDto> allBooks = FXCollections.observableArrayList();

    @FXML
    public void initialize() {
        log.info("MainController ініціалізовано");

        setupAuthorsTree();
        setupLists();

        // Налаштування таблиці
        bookTableView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> showBookDetails(newVal));

        searchField.setOnAction(event -> handleSearch());

        // Завантажити книги при старті
        refreshBookTable();
    }

    private void setupAuthorsTree() {
        TreeItem<String> root = new TreeItem<>("Автори");
        root.setExpanded(true);
        // Тестові дані - потім з БД
        TreeItem<String> author1 = new TreeItem<>("Басов Микола");
        TreeItem<String> author2 = new TreeItem<>("Виланов Олександр");
        author1.getChildren().addAll(
                new TreeItem<>("Мир Вічного Поляна (1-9)"),
                new TreeItem<>("Мир Вічного Поляна (10-18)")
        );
        author2.getChildren().add(new TreeItem<>("Собери себя сам"));
        root.getChildren().addAll(author1, author2);
        authorsTree.setRoot(root);
        authorsTree.setShowRoot(false);

        authorsTree.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> {
                    if (newVal != null && newVal.isLeaf()) {
                        // Фільтрація за автором (поки що заглушка)
                        statusLabel.setText("Вибрано автора: " + newVal.getValue());
                    }
                }
        );
    }

    private void setupLists() {
        seriesListView.getItems().addAll("Мир Вічного Поляна", "CCC", "Грабитель", "Гремучий Коктейль");
        seriesListView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> {
                    if (newVal != null) {
                        filterBooksBySeries(newVal);
                    }
                }
        );

        genresListView.getItems().addAll("Наукова фантастика", "Детектив", "Історичний", "Фентезі");
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

    private void filterBooksBySeries(String series) {
        List<BookDto> filtered = allBooks.stream()
                .filter(b -> series.equals(b.getSeries()))
                .collect(Collectors.toList());
        bookTableView.getItems().setAll(filtered);
        bookCountLabel.setText(filtered.size() + " книг");
        if (!filtered.isEmpty()) {
            bookTableView.getSelectionModel().selectFirst();
        } else {
            clearDetails();
        }
        statusLabel.setText("Показано серію: " + series);
    }

    private void filterBooksByGenre(String genre) {
        List<BookDto> filtered = allBooks.stream()
                .filter(b -> b.getGenresText() != null && b.getGenresText().contains(genre))
                .collect(Collectors.toList());
        bookTableView.getItems().setAll(filtered);
        bookCountLabel.setText(filtered.size() + " книг");
        if (!filtered.isEmpty()) {
            bookTableView.getSelectionModel().selectFirst();
        } else {
            clearDetails();
        }
        statusLabel.setText("Показано жанр: " + genre);
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
                .updateDate(book.getUpdateDate())
                .annotation(book.getAnnotation())
                .build();
    }

    private void refreshBookTable() {
        try {
            List<Book> books = bookQueryRepository.findAll(10000, 0);
            allBooks.setAll(books.stream().map(this::toDto).collect(Collectors.toList()));
            // Сортування за замовчуванням: спочатку за серією, потім за номером
            allBooks.sort(Comparator.comparing(BookDto::getSeries, Comparator.nullsLast(String::compareTo))
                    .thenComparing(BookDto::getSequenceNumber, Comparator.nullsLast(Integer::compareTo)));
            bookTableView.setItems(allBooks);
            bookCountLabel.setText(allBooks.size() + " книг");
            if (!allBooks.isEmpty()) {
                bookTableView.getSelectionModel().selectFirst();
            } else {
                clearDetails();
            }
            statusLabel.setText("Показано всі книги");
        } catch (Exception e) {
            log.error("Помилка оновлення таблиці", e);
            statusLabel.setText("Помилка завантаження книг: " + e.getMessage());
        }
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
        detailFile.setText("Файл: " + (book.getFileName() != null ? book.getFileName() : ""));
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

    @FXML
    public void handleSearchTab() {
        String query = searchTabField.getText();
        if (query == null || query.isBlank()) return;
        try {
            List<Book> books = bookQueryRepository.search(query, 1000);
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
            refreshBookTable();
            return;
        }
        try {
            List<Book> books = bookQueryRepository.search(query, 1000);
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

    // --- Обробники меню ---
    @FXML
    public void handleOpenCollection() {
        statusLabel.setText("Відкриття колекції... (ще не реалізовано)");
        Alert alert = new Alert(Alert.AlertType.INFORMATION, "Функція в розробці");
        alert.showAndWait();
    }

    @FXML
    public void handleNewCollection() {
        statusLabel.setText("Створення колекції... (ще не реалізовано)");
        Alert alert = new Alert(Alert.AlertType.INFORMATION, "Функція в розробці");
        alert.showAndWait();
    }

    @FXML
    public void handleAddGroup() {
        statusLabel.setText("Додати групу... (ще не реалізовано)");
        Alert alert = new Alert(Alert.AlertType.INFORMATION, "Функція в розробці");
        alert.showAndWait();
    }

    @FXML
    public void handleEditGroup() {
        statusLabel.setText("Редагувати групу... (ще не реалізовано)");
        Alert alert = new Alert(Alert.AlertType.INFORMATION, "Функція в розробці");
        alert.showAndWait();
    }

    @FXML
    public void handleDeleteGroup() {
        statusLabel.setText("Видалити групу... (ще не реалізовано)");
        Alert alert = new Alert(Alert.AlertType.INFORMATION, "Функція в розробці");
        alert.showAndWait();
    }

    @FXML
    public void handleEditMetadata() {
        BookDto selected = bookTableView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING, "Виберіть книгу для редагування");
            alert.showAndWait();
            return;
        }
        statusLabel.setText("Редагування метаданих для: " + selected.getTitle());
        Alert alert = new Alert(Alert.AlertType.INFORMATION, "Редагування метаданих (ще не реалізовано)");
        alert.showAndWait();
    }

    @FXML
    public void handleDeleteBook() {
        BookDto selected = bookTableView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING, "Виберіть книгу для видалення");
            alert.showAndWait();
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Видалити книгу '" + selected.getTitle() + "'?");
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                statusLabel.setText("Видалення книги (ще не реалізовано)");
            }
        });
    }

    @FXML
    public void handleShowColumns() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, "Налаштування колонок (ще не реалізовано)");
        alert.showAndWait();
    }

    @FXML
    public void handleExport() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, "Експорт (ще не реалізовано)");
        alert.showAndWait();
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
            importFile(file.toPath());
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
            importFile(file.toPath());
        }
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
        refreshBookTable();
        statusLabel.setText("Таблицю оновлено");
    }

    @FXML
    public void handleExit() {
        javafx.application.Platform.exit();
    }

    @FXML
    public void handleAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Про програму");
        alert.setHeaderText("MyHomeLib Enterprise");
        alert.setContentText("Версія 1.0.0-SNAPSHOT\n\nJava-версія MyHomeLib\nАрхітектура: Hexagonal + CQRS + MVVM\nJava 21, Spring Boot 3.4, JavaFX 21");
        alert.showAndWait();
    }

    // --- Допоміжні методи імпорту ---
    private void importFile(Path filePath) {
        progressBar.setVisible(true);
        statusLabel.setText("Імпорт: " + filePath.getFileName());

        javafx.concurrent.Task<Integer> task = new javafx.concurrent.Task<>() {
            @Override
            protected Integer call() throws Exception {
                return importerService.importInpx(filePath);
            }
        };

        task.setOnSucceeded(event -> {
            int count = task.getValue();
            progressBar.setVisible(false);
            statusLabel.setText("Імпорт завершено. Додано " + count + " книг");
            refreshBookTable();
        });

        task.setOnFailed(event -> {
            progressBar.setVisible(false);
            statusLabel.setText("Помилка імпорту: " + task.getException().getMessage());
            log.error("Помилка імпорту", task.getException());
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Помилка імпорту");
            alert.setContentText(task.getException().getMessage());
            alert.showAndWait();
        });

        new Thread(task).start();
    }

    private void importDirectory(Path dirPath) {
        progressBar.setVisible(true);
        statusLabel.setText("Імпорт каталогу: " + dirPath.getFileName());

        javafx.concurrent.Task<Integer> task = new javafx.concurrent.Task<>() {
            @Override
            protected Integer call() throws Exception {
                return importerService.importDirectory(dirPath);
            }
        };

        task.setOnSucceeded(event -> {
            int count = task.getValue();
            progressBar.setVisible(false);
            statusLabel.setText("Імпорт каталогу завершено. Додано " + count + " книг");
            refreshBookTable();
        });

        task.setOnFailed(event -> {
            progressBar.setVisible(false);
            statusLabel.setText("Помилка імпорту каталогу: " + task.getException().getMessage());
            log.error("Помилка імпорту каталогу", task.getException());
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Помилка імпорту");
            alert.setContentText(task.getException().getMessage());
            alert.showAndWait();
        });

        new Thread(task).start();
    }
}