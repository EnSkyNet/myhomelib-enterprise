package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.navigation.GenreNode;
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
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class MainController {

    private final MainViewModel mainViewModel;
    private final NavigationViewModel navigationViewModel;
    private final BookDetailsPresenter bookDetailsPresenter;

    @FXML private TreeView<LibraryNode> authorsTree;
    @FXML private ListView<String> seriesListView;
    @FXML private TreeView<LibraryNode> genresTree;
    @FXML private ListView<Group> groupsListView;
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

    private ContextMenu bookContextMenu;
    private MenuItem addToGroupMenuItem;
    private MenuItem removeFromGroupMenuItem;

    @FXML
    public void initialize() {
        log.info("🔵 MainController.initialize() started");

        statusLabel.textProperty().bind(mainViewModel.statusTextProperty());
        progressBar.progressProperty().bind(mainViewModel.importProgressProperty());
        progressBar.visibleProperty().bind(mainViewModel.importInProgressProperty());

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

        searchField.textProperty().bindBidirectional(mainViewModel.searchQueryProperty());

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

        // === СЕРІЇ ===
        seriesListView.setItems(navigationViewModel.seriesNamesProperty());
        seriesListView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> {
                    if (newVal != null && !newVal.isBlank()) {
                        log.info("Вибрано серію: {}", newVal);
                        mainViewModel.loadBooksBySeries(newVal);
                    }
                }
        );

        // === ЖАНРИ ===
        genresTree.rootProperty().bind(navigationViewModel.genresRootProperty());
        genresTree.setShowRoot(false);
        genresTree.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(LibraryNode item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) setText(null);
                else setText(item.toString());
            }
        });
        genresTree.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> {
                    if (newVal != null && newVal.getValue() != null) {
                        LibraryNode node = newVal.getValue();
                        if (node instanceof GenreNode genreNode) {
                            Genre genre = genreNode.genre();
                            String genreCode = genre.getId().asString();
                            log.info("Вибрано жанр: {} ({})", genre.getName(), genreCode);
                            mainViewModel.loadBooksByGenre(genreCode);
                        }
                    }
                }
        );

        // === ГРУПИ ===
        groupsListView.setItems(navigationViewModel.groupsProperty());
        groupsListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Group item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) setText(null);
                else setText(item.getName());
            }
        });
        groupsListView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> {
                    if (newVal != null) {
                        log.info("Вибрано групу: {} (id={})", newVal.getName(), newVal.getId());
                        mainViewModel.loadBooksByGroup(newVal.getId());
                    }
                }
        );

        // === ДЕТАЛІ ===
        bookDetailsPresenter.bind(
                detailTitle, detailAuthors, detailSeries, detailGenres,
                detailLanguage, detailRate, detailProgress,
                detailFile, detailFolder, detailSize, detailAnnotation,
                detailReview, detailCreated, detailKeywords
        );

        mainViewModel.initWithoutBooks();
        navigationViewModel.refreshAll();

        // === КОНТЕКСТНЕ МЕНЮ ===
        setupBookContextMenu();

        // === ЗАГЛУШКИ ===
        downloadsListView.getItems().addAll("Завантаження 1");

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

    private void setupBookContextMenu() {
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

    // === ОБРОБНИКИ ГРУП ===

    @FXML
    public void handleAddGroup() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Додати групу");
        dialog.setHeaderText("Введіть назву нової групи");
        dialog.setContentText("Назва:");
        Optional<String> result = dialog.showAndWait();
        result.ifPresent(name -> {
            if (!name.isBlank()) {
                try {
                    mainViewModel.createGroup(name);
                    navigationViewModel.loadGroups();
                    mainViewModel.setStatusText("Групу '" + name + "' створено");
                } catch (Exception e) {
                    showError("Помилка створення групи", e.getMessage());
                }
            }
        });
    }

    @FXML
    public void handleEditGroup() {
        Group selected = groupsListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showError("Помилка", "Не вибрано жодної групи");
            return;
        }
        if (!selected.isAllowDelete()) {
            showError("Помилка", "Цю групу не можна перейменовувати (системна група)");
            return;
        }
        TextInputDialog dialog = new TextInputDialog(selected.getName());
        dialog.setTitle("Редагування групи");
        dialog.setHeaderText("Введіть нову назву групи");
        dialog.setContentText("Назва:");
        Optional<String> result = dialog.showAndWait();
        result.ifPresent(newName -> {
            if (!newName.isBlank() && !newName.equals(selected.getName())) {
                try {
                    mainViewModel.renameGroup(selected.getId(), newName);
                    navigationViewModel.loadGroups();
                    mainViewModel.setStatusText("Групу перейменовано на '" + newName + "'");
                } catch (Exception e) {
                    showError("Помилка перейменування", e.getMessage());
                }
            }
        });
    }

    @FXML
    public void handleDeleteGroup() {
        Group selected = groupsListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showError("Помилка", "Не вибрано жодної групи");
            return;
        }
        if (!selected.isAllowDelete()) {
            showError("Помилка", "Цю групу не можна видалити (системна група)");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Підтвердження");
        confirm.setHeaderText("Видалити групу '" + selected.getName() + "'?");
        confirm.setContentText("Книги, що належать до групи, не будуть видалені, але зв'язок буде втрачено.");
        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            try {
                mainViewModel.deleteGroup(selected.getId());
                navigationViewModel.loadGroups();
                mainViewModel.setStatusText("Групу '" + selected.getName() + "' видалено");
            } catch (Exception e) {
                showError("Помилка видалення", e.getMessage());
            }
        }
    }

    // === ДОДАТИ/ВИДАЛИТИ КНИГУ З ГРУПИ ===

    private void handleAddBookToGroup() {
        BookDto selectedBook = bookTableView.getSelectionModel().getSelectedItem();
        if (selectedBook == null) {
            showError("Помилка", "Не вибрано жодної книги");
            return;
        }
        var groups = navigationViewModel.groupsProperty();
        if (groups.isEmpty()) {
            showError("Помилка", "Немає жодної групи. Створіть групу спочатку.");
            return;
        }
        ChoiceDialog<Group> dialog = new ChoiceDialog<>(groups.get(0), groups);
        dialog.setTitle("Додати до групи");
        dialog.setHeaderText("Виберіть групу для книги '" + selectedBook.getTitle() + "'");
        dialog.setContentText("Група:");
        Optional<Group> result = dialog.showAndWait();
        result.ifPresent(group -> {
            try {
                mainViewModel.addBookToGroup(group.getId(), selectedBook.getId());
                mainViewModel.setStatusText("Книгу додано до групи '" + group.getName() + "'");
            } catch (Exception e) {
                showError("Помилка додавання", e.getMessage());
            }
        });
    }

    private void handleRemoveBookFromGroup() {
        BookDto selectedBook = bookTableView.getSelectionModel().getSelectedItem();
        if (selectedBook == null) {
            showError("Помилка", "Не вибрано жодної книги");
            return;
        }
        Group selectedGroup = groupsListView.getSelectionModel().getSelectedItem();
        if (selectedGroup == null) {
            showError("Помилка", "Не вибрано групу для видалення книги");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Підтвердження");
        confirm.setHeaderText("Видалити книгу '" + selectedBook.getTitle() + "' з групи '" + selectedGroup.getName() + "'?");
        confirm.setContentText("Книга не буде видалена з бібліотеки, лише зв'язок буде втрачено.");
        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            try {
                mainViewModel.removeBookFromGroup(selectedGroup.getId(), selectedBook.getId());
                mainViewModel.loadBooksByGroup(selectedGroup.getId());
                mainViewModel.setStatusText("Книгу видалено з групи '" + selectedGroup.getName() + "'");
            } catch (Exception e) {
                showError("Помилка видалення з групи", e.getMessage());
            }
        }
    }

    // === ДОПОМІЖНІ ===

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    // === ІНШІ ОБРОБНИКИ ===

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

    // === ЗАГОТОВКИ ===
    @FXML public void handleOpenCollection() {}
    @FXML public void handleNewCollection() {}
    @FXML public void handleEditMetadata() {}
    @FXML public void handleDeleteBook() {}
    @FXML public void handleShowColumns() {}
    @FXML public void handleExport() {}
}