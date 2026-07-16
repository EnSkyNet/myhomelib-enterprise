package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.statistics.StatisticsService;
import com.myhomelibcorp.application.usecase.collection.DeleteCollectionUseCase;
import com.myhomelibcorp.application.usecase.collection.RenameCollectionUseCase;
import com.myhomelibcorp.application.usecase.group.DeleteGroupUseCase;
import com.myhomelibcorp.application.usecase.group.RenameGroupUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.initializer.DatabaseInitializer;
import com.myhomelibcorp.infrastructure.persistence.sqlite.SqliteSeriesRepository;
import com.myhomelibcorp.ui.author.AuthorWorkspaceController;
import com.myhomelibcorp.ui.book.BookWorkspaceController;
import com.myhomelibcorp.ui.event.CollectionChangedEvent;
import com.myhomelibcorp.ui.event.NavigationRefreshEvent;
import com.myhomelibcorp.ui.group.GroupWorkspaceController;
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
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

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
    private final SqliteSeriesRepository seriesRepository;
    private final CollectionManager collectionManager;
    private final DatabaseInitializer databaseInitializer;
    private final RenameCollectionUseCase renameCollectionUseCase;
    private final DeleteCollectionUseCase deleteCollectionUseCase;
    private final RenameGroupUseCase renameGroupUseCase;
    private final DeleteGroupUseCase deleteGroupUseCase;
    private final StatisticsService statisticsService;
    private final ApplicationEventPublisher eventPublisher;

    @FXML private BorderPane mainPane;
    @FXML private TableView<?> bookTableView;
    @FXML private TextField searchField;
    @FXML private Button backButton;
    @FXML private Button forwardButton;

    private Pane currentWorkspace;

    @FXML
    public void initialize() {
        log.info("MainController ініціалізовано");
        try {
            seriesRepository.syncSeriesFromBooks();
            log.info("Серії синхронізовано при старті");
        } catch (Exception e) {
            log.error("Помилка синхронізації серій", e);
        }
        searchField.setOnAction(event -> handleSearch());
        showDashboard();
        updateNavigationButtons();
        // Оновлюємо статус-бар статистикою
        statisticsService.refreshStatistics();
        appState.getStatusBar().setStatistics(statisticsService.getStatistics());
        // Переконуємося, що статус-бар видимий
        appState.getStatusBar().setProgressVisible(false);
    }

    // ==================== НАВІГАЦІЯ ПО ВОРКСПЕЙСАМ ====================

    public void showDashboard() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/dashboard.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Pane dashboard = loader.load();
            setWorkspace(dashboard);
            workspaceManager.push("dashboard", "");
        } catch (IOException e) {
            log.error("Failed to load dashboard", e);
            dialogService.showError("Помилка", "Не вдалося завантажити дашборд: " + e.getMessage());
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
            dialogService.showError("Помилка", "Не вдалося завантажити автора: " + e.getMessage());
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
            dialogService.showError("Помилка", "Не вдалося завантажити книгу: " + e.getMessage());
        }
    }

    public void showSeriesWorkspace(SeriesId seriesId) {
        showSearchResults(seriesId != null ? seriesId.asString() : "");
    }

    public void showGenreWorkspace(GenreId genreId) {
        showSearchResults(genreId != null ? genreId.asString() : "");
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
            dialogService.showError("Помилка", "Не вдалося завантажити пошук: " + e.getMessage());
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
            dialogService.showError("Помилка", "Не вдалося завантажити пошук: " + e.getMessage());
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
            dialogService.showError("Помилка", "Не вдалося завантажити колекції: " + e.getMessage());
        }
    }

    public void showGroupWorkspace(Group group) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/groups-workspace.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Pane workspace = loader.load();
            GroupWorkspaceController controller = loader.getController();
            if (group != null) {
                controller.setGroup(group);
            }
            setWorkspace(workspace);
            workspaceManager.push("groups", group != null ? group.getId().toString() : "");
        } catch (IOException e) {
            log.error("Failed to load groups workspace", e);
            dialogService.showError("Помилка", "Не вдалося завантажити групи: " + e.getMessage());
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
            dialogService.showError("Помилка", "Не вдалося завантажити читалку: " + e.getMessage());
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
            dialogService.showError("Помилка", "Не вдалося завантажити імпорт: " + e.getMessage());
        }
    }

    public void setWorkspace(Pane workspace) {
        if (currentWorkspace != null) {
            mainPane.getChildren().remove(currentWorkspace);
        }
        currentWorkspace = workspace;
        workspace.setMaxHeight(Double.MAX_VALUE);
        workspace.setMaxWidth(Double.MAX_VALUE);
        mainPane.setCenter(workspace);

        // Примусове оновлення макета через Platform.runLater
        Platform.runLater(() -> {
            mainPane.layout();
            mainPane.requestLayout();
            // Переконуємося, що статус-бар видимий
            appState.getStatusBar().setProgressVisible(false);
            appState.getStatusBar().setStatusText("Поточна колекція: " +
                    (appState.getCurrentLibraryCollection() != null ?
                            appState.getCurrentLibraryCollection().getName() : "не вибрано"));
        });

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

    // ==================== КОЛЕКЦІЇ (БАЗИ ДАНИХ) ====================

    public void switchToCollection(Collection collection) {
        if (collection == null) return;
        log.info("Переключення на колекцію: {}", collection.getName());

        appState.setCurrentLibraryCollection(collection);
        collectionManager.switchToCollection(collection);
        databaseInitializer.initializeCurrentCollection();

        // Оновлюємо статистику та статус-бар
        statisticsService.refreshStatistics();
        appState.getStatusBar().setStatistics(statisticsService.getStatistics());
        appState.getStatusBar().setStatusText("Переключено на колекцію: " + collection.getName());
        appState.getStatusBar().setProgressVisible(false);

        // Публікуємо подію зміни колекції (тільки одну)
        eventPublisher.publishEvent(new CollectionChangedEvent(collection));

        // Оновлюємо дашборд
        showDashboard();
    }

    @FXML
    public void handleNewCollection() {
        Stage stage = (Stage) mainPane.getScene().getWindow();
        collectionPresenter.showCreateCollectionDialog(
                javafx.collections.FXCollections.observableArrayList(), stage);
        eventPublisher.publishEvent(new NavigationRefreshEvent());
    }

    @FXML
    public void handleRenameCollection() {
        Collection current = appState.getCurrentLibraryCollection();
        if (current == null) {
            dialogService.showWarning("Немає колекції", "Спочатку виберіть колекцію в навігації.");
            return;
        }
        Optional<String> result = dialogService.showTextInput(
                "Перейменувати колекцію",
                "Введіть нову назву для \"" + current.getName() + "\"",
                "Нова назва:",
                current.getName());
        result.ifPresent(newName -> {
            if (!newName.isBlank() && !newName.equals(current.getName())) {
                try {
                    Collection renamed = renameCollectionUseCase.execute(current.getId(), newName);
                    appState.setCurrentLibraryCollection(renamed);
                    statisticsService.refreshStatistics();
                    appState.getStatusBar().setStatistics(statisticsService.getStatistics());
                    eventPublisher.publishEvent(new CollectionChangedEvent(renamed));
                    dialogService.showInfo("Успішно", "Колекцію перейменовано на \"" + newName + "\"");
                } catch (Exception e) {
                    dialogService.showError("Помилка", "Не вдалося перейменувати: " + e.getMessage());
                }
            }
        });
    }

    @FXML
    public void handleDeleteCollection() {
        Collection current = appState.getCurrentLibraryCollection();
        if (current == null) {
            dialogService.showWarning("Немає колекції", "Спочатку виберіть колекцію.");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Підтвердження");
        confirm.setHeaderText("Видалити колекцію \"" + current.getName() + "\"?");
        confirm.setContentText("Всі дані колекції будуть видалені без можливості відновлення.");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            try {
                deleteCollectionUseCase.execute(current.getId());
                appState.setCurrentLibraryCollection(null);
                eventPublisher.publishEvent(new NavigationRefreshEvent());
                dialogService.showInfo("Успішно", "Колекцію видалено");
                showDashboard();
                statisticsService.refreshStatistics();
                appState.getStatusBar().setStatistics(statisticsService.getStatistics());
            } catch (Exception e) {
                dialogService.showError("Помилка", "Не вдалося видалити: " + e.getMessage());
            }
        }
    }

    @FXML
    public void handleSelectCollection() {
        Collection current = appState.getCurrentLibraryCollection();
        if (current == null) {
            dialogService.showWarning("Немає колекції", "Спочатку виберіть колекцію.");
            return;
        }
        switchToCollection(current);
    }

    // ==================== ГРУПИ (СПИСКИ КНИГ) ====================

    @FXML
    public void handleAddGroup() {
        groupPresenter.showAddGroupDialog(null, () -> {
            eventPublisher.publishEvent(new NavigationRefreshEvent());
            showGroupWorkspace(appState.getCurrentGroup());
        });
    }

    @FXML
    public void handleEditGroup() {
        Group current = appState.getCurrentGroup();
        if (current == null) {
            dialogService.showWarning("Немає групи", "Спочатку виберіть групу в навігації.");
            return;
        }
        groupPresenter.showEditGroupDialog(null, () -> {
            eventPublisher.publishEvent(new NavigationRefreshEvent());
            showGroupWorkspace(appState.getCurrentGroup());
        });
    }

    @FXML
    public void handleDeleteGroup() {
        Group current = appState.getCurrentGroup();
        if (current == null) {
            dialogService.showWarning("Немає групи", "Спочатку виберіть групу.");
            return;
        }
        groupPresenter.showDeleteGroupDialog(null, () -> {
            eventPublisher.publishEvent(new NavigationRefreshEvent());
            showGroupWorkspace(null);
        });
    }

    // ==================== ІНШІ ДІЇ ====================

    @FXML
    public void handleSearch() {
        String query = searchField.getText();
        log.info("Пошук за запитом: '{}'", query);
        if (query != null && !query.isBlank()) {
            showSearchResults(query);
        } else {
            showSearchResults("");
        }
    }

    @FXML
    public void handleImportFb2() {
        bookImportPresenter.importFb2();
        eventPublisher.publishEvent(new NavigationRefreshEvent());
    }

    @FXML
    public void handleImportInpx() {
        bookImportPresenter.importInpx();
        eventPublisher.publishEvent(new NavigationRefreshEvent());
    }

    @FXML
    public void handleImportDirectory() {
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Виберіть каталог з книгами");
        File dir = directoryChooser.showDialog(mainPane.getScene().getWindow());
        if (dir != null) {
            bookImportPresenter.importDirectory(dir.toPath());
            eventPublisher.publishEvent(new NavigationRefreshEvent());
        }
    }

    @FXML
    public void handleRefresh() {
        refreshPresenter.refreshAll();
        eventPublisher.publishEvent(new NavigationRefreshEvent());
    }

    @FXML
    public void handleExit() {
        Platform.exit();
    }

    @FXML
    public void handleAbout() {
        dialogService.showInfo("Про програму", "MyHomeLib Enterprise",
                "Версія 1.0.0-SNAPSHOT\nJava 21, Spring Boot 3.5, JavaFX 21");
    }

    @FXML
    public void handleEditMetadata() {
        dialogService.showInfo("Редагування метаданих", "Функція в розробці");
    }

    @FXML
    public void handleDeleteBook() {
        dialogService.showInfo("Видалення книги", "Функція в розробці");
    }

    @FXML
    public void handleShowColumns() {
        dialogService.showInfo("Налаштування колонок", "Функція в розробці");
    }

    @FXML
    public void handleExport() {
        dialogService.showInfo("Експорт", "Функція в розробці");
    }

    @FXML
    public void handleRebuildIndex() {
        dialogService.showInfo("Перебудова індексу", "Функція в розробці");
    }

    @FXML
    public void handleHome() {
        showDashboard();
    }

    @FXML
    public void handleBack() {
        workspaceManager.goBack();
        updateNavigationButtons();
    }

    @FXML
    public void handleForward() {
        workspaceManager.goForward();
        updateNavigationButtons();
    }

    @FXML
    public void handleAddBook() {
        dialogService.showInfo("Додати книгу", "Функція в розробці");
    }

    @FXML
    public void handleBackup() {
        dialogService.showInfo("Резервне копіювання", "Функція в розробці");
    }

    @FXML
    public void handleSettings() {
        dialogService.showInfo("Налаштування", "Функція в розробці");
    }

    @FXML
    public void handleImport() {
        showImportWorkspace();
    }

    @FXML
    public void handleHistory() {
        dialogService.showInfo("Історія", "Функція в розробці");
    }
}