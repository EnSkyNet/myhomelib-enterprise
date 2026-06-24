package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.domain.model.navigation.LibraryNode;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.ui.presentation.BookDetailsPresenter;
import com.myhomelibcorp.ui.viewmodel.MainViewModel;
import com.myhomelibcorp.ui.viewmodel.NavigationViewModel;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;

@Component
@RequiredArgsConstructor
@Slf4j
public class MainController {

    private final MainViewModel mainViewModel;
    private final NavigationViewModel navigationViewModel;
    private final BookDetailsPresenter bookDetailsPresenter;

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

    @FXML private Label detailReview;
    @FXML private Label detailCreated;
    @FXML private Label detailKeywords;

    @FXML private TextField searchField;
    @FXML private ProgressIndicator searchIndicator;

    @FXML private Label statusLabel;
    @FXML private ProgressBar progressBar;

    @FXML
    public void initialize() {
        log.info("🔵 MainController.initialize() started");

        // Статус та прогрес
        statusLabel.textProperty().bind(mainViewModel.statusTextProperty());
        progressBar.progressProperty().bind(mainViewModel.importProgressProperty());
        progressBar.visibleProperty().bind(mainViewModel.importInProgressProperty());

        // Таблиця книг
        setupBookTable();
        bookTableView.setItems(mainViewModel.booksProperty());
        bookTableView.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, newVal) -> {
                    mainViewModel.selectedBookProperty().set(newVal);
                    if (newVal != null) {
                        bookDetailsPresenter.showBookDetails(newVal);
                    } else {
                        bookDetailsPresenter.clearDetails();
                    }
                }
        );

        // Пошук
        searchField.textProperty().bindBidirectional(mainViewModel.searchQueryProperty());

        // Жанри
        genresListView.setItems(mainViewModel.genreNamesProperty());

        // === АВТОРИ ===
        authorsTree.rootProperty().bind(navigationViewModel.authorsRootProperty());
        authorsTree.setShowRoot(false);
        authorsTree.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(LibraryNode item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) setText(null);
                else setText(item.toString());
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

        navigationViewModel.authorsRootProperty().addListener((obs, oldRoot, newRoot) -> {
            if (newRoot != null && !newRoot.getChildren().isEmpty()) {
                TreeItem<LibraryNode> firstItem = newRoot.getChildren().get(0);
                authorsTree.getSelectionModel().select(firstItem);
            } else {
                mainViewModel.refreshBooks();
            }
        });

        // === СЕРІЇ – ПРИВ'ЯЗКА ТА ОЧИЩЕННЯ ===
        log.info("🔵 Налаштування списку серій...");

        // Очищаємо список (на випадок, якщо раніше були заглушки)
        seriesListView.getItems().clear();
        log.info("🔵 seriesListView очищено. Розмір: {}", seriesListView.getItems().size());

        // Прив'язуємо до даних
        seriesListView.setItems(navigationViewModel.seriesNamesProperty());
        log.info("🔵 seriesListView прив'язано до seriesNamesProperty. Розмір даних: {}", navigationViewModel.seriesNamesProperty().size());

        // Слухач вибору
        seriesListView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> {
                    if (newVal != null && !newVal.isBlank()) {
                        log.info("🔵 Вибрано серію: {}", newVal);
                        mainViewModel.loadBooksBySeries(newVal);
                    }
                }
        );

        // === ДЕТАЛІ КНИГИ ===
        bookDetailsPresenter.bind(
                detailTitle, detailAuthors, detailSeries, detailGenres,
                detailLanguage, detailRate, detailProgress,
                detailFile, detailFolder, detailSize, detailAnnotation,
                detailReview, detailCreated, detailKeywords
        );

        // === ІНІЦІАЛІЗАЦІЯ ДАНИХ ===
        mainViewModel.initWithoutBooks();
        navigationViewModel.refreshAll();

        // === ТИМЧАСОВІ ЗАГЛУШКИ ДЛЯ ІНШИХ ВКЛАДОК ===
        groupsListView.getItems().addAll("Favorites", "To Read");
        downloadsListView.getItems().addAll("Завантаження 1");

        // Додаткова перевірка: через 2 секунди вивести стан списку (для діагностики)
        new Thread(() -> {
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            Platform.runLater(() -> {
                log.info("🔵 Через 2 секунди: seriesListView розмір = {}", seriesListView.getItems().size());
                log.info("🔵 Через 2 секунди: seriesNamesProperty розмір = {}", navigationViewModel.seriesNamesProperty().size());
                if (!seriesListView.getItems().isEmpty()) {
                    log.info("🔵 Елементи в списку: {}", seriesListView.getItems());
                } else {
                    log.warn("⚠️ Список серій порожній!");
                }
            });
        }).start();

        log.info("🔵 MainController.initialize() finished");
    }

    private void setupBookTable() {
        bookTableView.getColumns().clear();

        TableColumn<BookDto, String> titleCol = new TableColumn<>("Назва");
        titleCol.setCellValueFactory(cellData -> new javafx.beans.property.SimpleStringProperty(cellData.getValue().getTitle()));
        titleCol.setPrefWidth(200);

        TableColumn<BookDto, String> authorCol = new TableColumn<>("Автор");
        authorCol.setCellValueFactory(cellData -> new javafx.beans.property.SimpleStringProperty(cellData.getValue().getAuthorsText()));
        authorCol.setPrefWidth(150);

        TableColumn<BookDto, String> seriesCol = new TableColumn<>("Серія");
        seriesCol.setCellValueFactory(cellData -> new javafx.beans.property.SimpleStringProperty(cellData.getValue().getSeries()));
        seriesCol.setPrefWidth(100);

        TableColumn<BookDto, String> genresCol = new TableColumn<>("Жанри");
        genresCol.setCellValueFactory(cellData -> new javafx.beans.property.SimpleStringProperty(cellData.getValue().getGenresText()));
        genresCol.setPrefWidth(100);

        TableColumn<BookDto, Integer> seqCol = new TableColumn<>("№");
        seqCol.setCellValueFactory(cellData -> new javafx.beans.property.SimpleObjectProperty<>(cellData.getValue().getSequenceNumber()));
        seqCol.setPrefWidth(40);

        TableColumn<BookDto, String> langCol = new TableColumn<>("Мова");
        langCol.setCellValueFactory(cellData -> new javafx.beans.property.SimpleStringProperty(cellData.getValue().getLanguage()));
        langCol.setPrefWidth(60);

        TableColumn<BookDto, String> sizeCol = new TableColumn<>("Розмір");
        sizeCol.setCellValueFactory(cellData -> new javafx.beans.property.SimpleStringProperty(cellData.getValue().getFileSizeFormatted()));
        sizeCol.setPrefWidth(80);

        TableColumn<BookDto, String> rateCol = new TableColumn<>("Оцінка");
        rateCol.setCellValueFactory(cellData -> new javafx.beans.property.SimpleStringProperty(cellData.getValue().getRateStars()));
        rateCol.setPrefWidth(80);

        TableColumn<BookDto, String> dateCol = new TableColumn<>("Додано");
        dateCol.setCellValueFactory(cellData -> new javafx.beans.property.SimpleStringProperty(cellData.getValue().getUpdateDateFormatted()));
        dateCol.setPrefWidth(100);

        TableColumn<BookDto, String> statusCol = new TableColumn<>("Статус");
        statusCol.setCellValueFactory(cellData -> new javafx.beans.property.SimpleStringProperty(cellData.getValue().getLocalStatus()));
        statusCol.setPrefWidth(70);

        TableColumn<BookDto, String> progressCol = new TableColumn<>("Прогрес");
        progressCol.setCellValueFactory(cellData -> new javafx.beans.property.SimpleStringProperty(cellData.getValue().getProgressFormatted()));
        progressCol.setPrefWidth(80);

        bookTableView.getColumns().addAll(
                titleCol, authorCol, seriesCol, genresCol, seqCol,
                langCol, sizeCol, rateCol, dateCol, statusCol, progressCol
        );
    }

    @FXML public void handleRefresh() {
        log.info("🔄 handleRefresh() called");
        navigationViewModel.refreshAll();
    }

    @FXML public void handleImportFb2() {
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

    @FXML public void handleImportInpx() {
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

    @FXML public void handleImportDirectory() {
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Виберіть каталог з книгами");
        File dir = directoryChooser.showDialog(null);
        if (dir != null && dir.isDirectory()) {
            mainViewModel.importDirectory(dir.toPath(), this::onImportComplete);
        }
    }

    @FXML public void handleAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Про програму");
        alert.setHeaderText("MyHomeLib Enterprise");
        alert.setContentText("Версія 1.0.0-SNAPSHOT\n\nJava-версія MyHomeLib\nJava 21, Spring Boot 3.5, JavaFX 21");
        alert.showAndWait();
    }

    @FXML public void handleExit() {
        Platform.exit();
    }

    @FXML public void handleRebuildIndex() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Перебудова індексу");
        confirm.setHeaderText("Це може зайняти деякий час");
        confirm.setContentText("Перебудувати Lucene індекс для пошуку?");
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                mainViewModel.rebuildIndex();
            }
        });
    }

    private void onImportComplete() {
        log.info("✅ Імпорт завершено, оновлюємо навігацію");
        navigationViewModel.refreshAll();
    }

    @FXML public void handleOpenCollection() {}
    @FXML public void handleNewCollection() {}
    @FXML public void handleAddGroup() {}
    @FXML public void handleEditGroup() {}
    @FXML public void handleDeleteGroup() {}
    @FXML public void handleEditMetadata() {}
    @FXML public void handleDeleteBook() {}
    @FXML public void handleShowColumns() {}
    @FXML public void handleExport() {}
}