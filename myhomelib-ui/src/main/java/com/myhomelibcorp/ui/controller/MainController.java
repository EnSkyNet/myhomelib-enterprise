package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import com.myhomelibcorp.ui.author.AuthorWorkspaceController;
import com.myhomelibcorp.ui.book.BookWorkspaceController;
import com.myhomelibcorp.ui.collection.CollectionWorkspaceController;
import com.myhomelibcorp.ui.navigation.WorkspaceManager;
import com.myhomelibcorp.ui.presenter.BookImportPresenter;
import com.myhomelibcorp.ui.presenter.CollectionPresenter;
import com.myhomelibcorp.ui.presenter.GroupPresenter;
import com.myhomelibcorp.ui.presenter.RefreshPresenter;
import com.myhomelibcorp.ui.reader.ReaderWorkspaceController;
import com.myhomelibcorp.ui.search.SearchWorkspaceController;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Button;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class MainController {

    private final ApplicationContext springContext;
    private final ApplicationState appState;
    private final BookImportPresenter bookImportPresenter;
    private final CollectionPresenter collectionPresenter;
    private final GroupPresenter groupPresenter;
    private final RefreshPresenter refreshPresenter;
    private final DialogService dialogService;
    private final WorkspaceManager workspaceManager;

    @FXML private BorderPane mainPane;
    @FXML private TableView<?> bookTableView;
    @FXML private TextField searchField;
    @FXML private Button backButton;
    @FXML private Button forwardButton;

    private Pane currentWorkspace;

    @FXML
    public void initialize() {
        log.info("MainController ініціалізовано");
        workspaceManager.setMainPane(mainPane);
        searchField.setOnAction(event -> handleSearch());
        showDashboard();
        updateNavigationButtons();
    }

    public void showDashboard() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/dashboard.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Pane dashboard = loader.load();
            setWorkspace(dashboard);
            workspaceManager.push("dashboard", "");
        } catch (IOException e) {
            log.error("Failed to load dashboard", e);
        }
    }

    public void showAuthorWorkspace(AuthorId authorId) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/author-workspace.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Pane workspace = loader.load();
            AuthorWorkspaceController controller = loader.getController();
            controller.setAuthorId(authorId);
            setWorkspace(workspace);
            workspaceManager.push("author", authorId != null ? authorId.asString() : "");
        } catch (IOException e) {
            log.error("Failed to load author workspace", e);
        }
    }

    public void showBookWorkspace(BookId bookId) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/book-workspace.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Pane workspace = loader.load();
            BookWorkspaceController controller = loader.getController();
            controller.setBookId(bookId);
            setWorkspace(workspace);
            workspaceManager.push("book", bookId.asString());
        } catch (IOException e) {
            log.error("Failed to load book workspace", e);
        }
    }

    public void showSeriesWorkspace(SeriesId seriesId) {
        log.warn("showSeriesWorkspace викликано, але не реалізовано");
    }

    public void showGenreWorkspace(GenreId genreId) {
        log.warn("showGenreWorkspace викликано, але не реалізовано");
    }

    public void showSearchResults(String query) {
        log.info("showSearchResults: query='{}'", query);
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/search-workspace.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Pane workspace = loader.load();
            SearchWorkspaceController controller = loader.getController();
            controller.setInitialQuery(query);
            setWorkspace(workspace);
            workspaceManager.push("search", query);
        } catch (IOException e) {
            log.error("Failed to load search workspace", e);
            dialogService.showError("Помилка", "Не вдалося відкрити пошук: " + e.getMessage());
        }
    }

    public void showSearchResults(List<BookDto> results) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/search-workspace.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Pane workspace = loader.load();
            SearchWorkspaceController controller = loader.getController();
            controller.setResults(results);
            setWorkspace(workspace);
            workspaceManager.push("search", "");
        } catch (IOException e) {
            log.error("Failed to load search workspace", e);
        }
    }

    public void showCollectionWorkspace() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/collection-workspace.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Pane workspace = loader.load();
            setWorkspace(workspace);
            workspaceManager.push("collection", "");
        } catch (IOException e) {
            log.error("Failed to load collection workspace", e);
        }
    }

    public void showReaderWorkspace(BookId bookId) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/reader-workspace.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Pane workspace = loader.load();
            ReaderWorkspaceController controller = loader.getController();
            controller.setBookId(bookId);
            setWorkspace(workspace);
            workspaceManager.push("reader", bookId.asString());
        } catch (IOException e) {
            log.error("Failed to load reader workspace", e);
        }
    }

    public void showImportWorkspace() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/import-workspace.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Pane workspace = loader.load();
            setWorkspace(workspace);
            workspaceManager.push("import", "");
        } catch (IOException e) {
            log.error("Failed to load import workspace", e);
            dialogService.showError("Помилка", "Не вдалося відкрити імпорт: " + e.getMessage());
        }
    }

    public void setWorkspace(Pane workspace) {
        if (currentWorkspace != null) {
            mainPane.getChildren().remove(currentWorkspace);
        }
        currentWorkspace = workspace;
        mainPane.setCenter(workspace);
        updateNavigationButtons();
    }

    public void updateNavigationButtons() {
        if (backButton != null) {
            backButton.setDisable(!workspaceManager.canGoBack());
        }
        if (forwardButton != null) {
            forwardButton.setDisable(!workspaceManager.canGoForward());
        }
    }

    @FXML public void handleSearch() {
        String query = searchField.getText();
        log.info("Пошук за запитом: '{}'", query);
        if (query != null && !query.isBlank()) {
            showSearchResults(query);
        } else {
            showSearchResults("");
        }
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
        File dir = directoryChooser.showDialog(mainPane.getScene().getWindow());
        if (dir != null) {
            bookImportPresenter.importDirectory(dir.toPath());
        }
    }

    @FXML public void handleRefresh() {
        refreshPresenter.refreshAll();
    }

    @FXML public void handleExit() {
        Platform.exit();
    }

    @FXML public void handleAbout() {
        dialogService.showInfo("Про програму", "MyHomeLib Enterprise",
                "Версія 1.0.0-SNAPSHOT\nJava 21, Spring Boot 3.5, JavaFX 21");
    }

    @FXML public void handleNewCollection() {
        Stage stage = (Stage) mainPane.getScene().getWindow();
        collectionPresenter.showCreateCollectionDialog(
                javafx.collections.FXCollections.observableArrayList(), stage);
    }

    @FXML public void handleRenameCollection() {}
    @FXML public void handleDeleteCollection() {}
    @FXML public void handleSelectCollection() {}
    @FXML public void handleAddGroup() {}
    @FXML public void handleEditGroup() {}
    @FXML public void handleDeleteGroup() {}

    @FXML public void handleEditMetadata() {
        dialogService.showInfo("Інформація", "Редагування метаданих", "Функція поки що не реалізована");
    }

    @FXML public void handleDeleteBook() {
        dialogService.showInfo("Інформація", "Видалення книги", "Функція поки що не реалізована");
    }

    @FXML public void handleShowColumns() {
        dialogService.showInfo("Налаштування", "Відображення колонок", "Функція поки що не реалізована");
    }

    @FXML public void handleExport() {
        dialogService.showInfo("Інформація", "Експорт", "Функція поки що не реалізована");
    }

    @FXML public void handleRebuildIndex() {
        dialogService.showInfo("Інформація", "Перебудова індексу", "Функція поки що не реалізована");
    }

    @FXML public void handleHome() {
        showDashboard();
    }

    @FXML public void handleBack() {
        workspaceManager.goBack();
        updateNavigationButtons();
    }

    @FXML public void handleForward() {
        workspaceManager.goForward();
        updateNavigationButtons();
    }

    @FXML public void handleAddBook() {
        dialogService.showInfo("Інформація", "Додати книгу", "Функція поки що не реалізована");
    }

    @FXML public void handleBackup() {
        dialogService.showInfo("Інформація", "Резервне копіювання", "Функція поки що не реалізована");
    }

    @FXML public void handleSettings() {
        dialogService.showInfo("Інформація", "Налаштування", "Функція поки що не реалізована");
    }

    @FXML public void handleImport() {
        showImportWorkspace();
    }

    @FXML public void handleHistory() {
        dialogService.showInfo("Інформація", "Історія", "Функція поки що не реалізована");
    }
}