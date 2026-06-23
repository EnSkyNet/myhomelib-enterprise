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

    @FXML private TextField searchField;
    @FXML private ProgressIndicator searchIndicator;

    @FXML private Label statusLabel;
    @FXML private ProgressBar progressBar;

    @FXML
    public void initialize() {
        statusLabel.textProperty().bind(mainViewModel.statusTextProperty());
        progressBar.progressProperty().bind(mainViewModel.importProgressProperty());
        progressBar.visibleProperty().bind(mainViewModel.importInProgressProperty());

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
        genresListView.setItems(mainViewModel.genreNamesProperty());

        // Дерево авторів
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

        // Автоматичний вибір першого автора
        navigationViewModel.authorsRootProperty().addListener((obs, oldRoot, newRoot) -> {
            if (newRoot != null && !newRoot.getChildren().isEmpty()) {
                TreeItem<LibraryNode> firstItem = newRoot.getChildren().get(0);
                authorsTree.getSelectionModel().select(firstItem);
            } else {
                mainViewModel.refreshBooks();
            }
        });

        bookDetailsPresenter.bind(
                detailTitle, detailAuthors, detailSeries, detailGenres,
                detailLanguage, detailRate, detailProgress,
                detailFile, detailFolder, detailSize, detailAnnotation
        );

        mainViewModel.initWithoutBooks();
        navigationViewModel.loadAuthors();

        // Тимчасові списки
        seriesListView.getItems().addAll("Серія 1", "Серія 2");
        groupsListView.getItems().addAll("Favorites", "To Read");
        downloadsListView.getItems().addAll("Завантаження 1");
    }

    @FXML public void handleRefresh() {
        navigationViewModel.loadAuthors();
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
            mainViewModel.importFile(file.toPath(), this::onImportComplete);
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
            mainViewModel.importFile(file.toPath(), this::onImportComplete);
        }
    }

    @FXML
    public void handleImportDirectory() {
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Виберіть каталог з книгами");
        File dir = directoryChooser.showDialog(null);
        if (dir != null && dir.isDirectory()) {
            mainViewModel.importDirectory(dir.toPath(), this::onImportComplete);
        }
    }

    @FXML
    public void handleAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Про програму");
        alert.setHeaderText("MyHomeLib Enterprise");
        alert.setContentText("Версія 1.0.0-SNAPSHOT\n\nJava-версія MyHomeLib\nJava 21, Spring Boot 3.5, JavaFX 21");
        alert.showAndWait();
    }

    @FXML public void handleExit() { Platform.exit(); }

    private void onImportComplete() {
        navigationViewModel.loadAuthors();
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