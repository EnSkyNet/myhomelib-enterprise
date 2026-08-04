package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.statistics.StatisticsService;
import com.myhomelibcorp.application.usecase.series.SyncSeriesUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import com.myhomelibcorp.reader.service.ReaderLifecycleManager;
import com.myhomelibcorp.ui.event.NavigationRefreshEvent;
import com.myhomelibcorp.ui.navigation.WorkspaceManager;
import com.myhomelibcorp.ui.presenter.BookImportPresenter;
import com.myhomelibcorp.ui.presenter.GroupPresenter;
import com.myhomelibcorp.ui.presenter.RefreshPresenter;
import com.myhomelibcorp.ui.search.SearchWorkspaceController;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class MainController {

    // ===== БАЗОВІ КОМПОНЕНТИ =====
    private final ApplicationContext springContext;
    private final ApplicationState appState;
    private final DialogService dialogService;
    private final WorkspaceManager workspaceManager;
    private final StatisticsService statisticsService;
    private final ApplicationEventPublisher eventPublisher;
    private final SyncSeriesUseCase syncSeriesUseCase;
    private final ReaderLifecycleManager readerLifecycleManager;

    // ===== ПРЕЗЕНТЕРИ ТА КОНТРОЛЕРИ =====
    private final GroupPresenter groupPresenter;
    private final BookImportPresenter bookImportPresenter;
    private final RefreshPresenter refreshPresenter;
    private final CollectionController collectionController;
    private final BatchOperationsController batchOperationsController;
    private final ViewModeController viewModeController;
    private final ExportController exportController;
    private final DatabaseToolsController databaseToolsController;

    // ===== FXML =====
    @FXML private BorderPane mainPane;
    @FXML private TextField searchField;
    @FXML private Button backButton;
    @FXML private Button forwardButton;

    // ===== СТАН =====
    private Pane currentWorkspace;

    @FXML
    public void initialize() {
        log.info("MainController ініціалізовано");
        syncSeriesUseCase.execute();
        viewModeController.init(mainPane);
        workspaceManager.setMainController(this);
        searchField.setOnAction(event -> handleSearch());
        showDashboard();
        updateNavigationButtons();
        statisticsService.refreshStatistics();
        appState.getStatusBar().setStatistics(statisticsService.getStatistics());
        appState.getStatusBar().setProgressVisible(false);
    }

    // ==================== НАВІГАЦІЯ ====================

    public void showDashboard() { workspaceManager.showDashboard(); }
    public void showAuthorWorkspace(AuthorId authorId) { workspaceManager.showAuthorWorkspace(authorId); }
    public void showBookWorkspace(BookId bookId) { workspaceManager.showBookWorkspace(bookId); }
    public void showSeriesWorkspace(SeriesId seriesId) { workspaceManager.showSeriesWorkspace(seriesId); }
    public void showGenreWorkspace(GenreId genreId) { workspaceManager.showGenreWorkspace(genreId); }
    public void showSearchResults(String query) { workspaceManager.showSearchResults(query); }
    public void showCollectionWorkspace() { workspaceManager.showCollectionWorkspace(); }
    public void showGroupWorkspace(Group group) { workspaceManager.showGroupWorkspace(group); }
    public void showReaderWorkspace(BookId bookId) { workspaceManager.showReaderWorkspace(bookId); }
    public void showImportWorkspace() { workspaceManager.showImportWorkspace(); }
    public void setWorkspace(Pane workspace) { workspaceManager.setWorkspace(workspace); }
    public void updateNavigationButtons() {
        backButton.setDisable(!workspaceManager.canGoBack());
        forwardButton.setDisable(!workspaceManager.canGoForward());
    }
    public void switchToCollection(Collection collection) {
        collectionController.switchToCollection(collection, this::showDashboard);
    }
    public BorderPane getMainPane() { return mainPane; }
    public void cleanupReader() {
        if (readerLifecycleManager != null && readerLifecycleManager.isReaderOpen()) {
            readerLifecycleManager.saveState();
            readerLifecycleManager.closeBook();
        }
    }

    // ==================== ПОШУК З РЕЗУЛЬТАТАМИ ====================

    public void showSearchResults(List<BookDto> results) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/search-workspace.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Parent root = loader.load();
            SearchWorkspaceController controller = loader.getController();
            controller.setResults(results);
            setWorkspace((Pane) root);
            workspaceManager.push("search", "results_" + (results != null ? results.size() : 0));
        } catch (Exception e) {
            log.error("Failed to load search workspace with results", e);
            dialogService.showError("Помилка", "Не вдалося завантажити пошук: " + e.getMessage());
        }
    }

    // ==================== КОЛЕКЦІЇ ====================

    @FXML public void handleNewCollection() {
        Stage stage = (Stage) mainPane.getScene().getWindow();
        collectionController.handleNewCollection(stage, () -> {
            eventPublisher.publishEvent(new NavigationRefreshEvent());
            showDashboard();
        });
    }

    @FXML public void handleRenameCollection() {
        collectionController.handleRenameCollection(() -> {
            eventPublisher.publishEvent(new NavigationRefreshEvent());
            showDashboard();
        });
    }

    @FXML public void handleDeleteCollection() {
        collectionController.handleDeleteCollection(() -> {
            eventPublisher.publishEvent(new NavigationRefreshEvent());
            showDashboard();
        });
    }

    @FXML public void handleSelectCollection() {
        collectionController.handleSelectCollection(this::showDashboard);
    }

    // ==================== ГРУПИ ====================

    @FXML public void handleAddGroup() {
        groupPresenter.showAddGroupDialog(null, () -> {
            eventPublisher.publishEvent(new NavigationRefreshEvent());
            showGroupWorkspace(appState.getCurrentGroup());
        });
    }

    @FXML public void handleEditGroup() {
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

    @FXML public void handleDeleteGroup() {
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

    // ==================== ГРУПОВІ ОПЕРАЦІЇ ====================

    @FXML public void handleBatchRate() {
        batchOperationsController.handleBatchRate(() -> eventPublisher.publishEvent(new NavigationRefreshEvent()));
    }
    @FXML public void handleBatchMarkRead() {
        batchOperationsController.handleBatchMarkRead(() -> eventPublisher.publishEvent(new NavigationRefreshEvent()));
    }
    @FXML public void handleBatchAddToGroup() {
        batchOperationsController.handleBatchAddToGroup(() -> eventPublisher.publishEvent(new NavigationRefreshEvent()));
    }
    @FXML public void handleClearSelection() {
        batchOperationsController.handleClearSelection();
    }

    // ==================== РЕЖИМ ПЕРЕГЛЯДУ ====================

    @FXML public void handleToggleView() { viewModeController.toggleView(); }

    // ==================== ЕКСПОРТ ====================

    @FXML public void handleExport() { exportController.handleExport(mainPane); }
    @FXML public void handleExportInpx() {
        exportController.handleExportInpx(mainPane, () -> eventPublisher.publishEvent(new NavigationRefreshEvent()));
    }

    // ==================== ПОШУК ТА ІМПОРТ ====================

    @FXML public void handleSearch() {
        String query = searchField.getText();
        if (query != null && !query.isBlank()) {
            showSearchResults(query);
        }
    }

    @FXML public void handleImportFb2() {
        bookImportPresenter.importFb2();
        eventPublisher.publishEvent(new NavigationRefreshEvent());
    }

    @FXML public void handleImportInpx() {
        bookImportPresenter.importInpx();
        eventPublisher.publishEvent(new NavigationRefreshEvent());
    }

    @FXML public void handleImportDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Виберіть каталог з книгами");
        File dir = chooser.showDialog(mainPane.getScene().getWindow());
        if (dir != null) {
            bookImportPresenter.importDirectory(dir.toPath());
            eventPublisher.publishEvent(new NavigationRefreshEvent());
        }
    }

    // ==================== ІНСТРУМЕНТИ ====================

    @FXML public void handleCheckIntegrity() {
        Stage stage = (Stage) mainPane.getScene().getWindow();
        databaseToolsController.handleCheckIntegrity(stage);
    }

    @FXML public void handleVacuum() {
        databaseToolsController.handleVacuum();
    }

    @FXML public void handleRebuildIndex() {
        databaseToolsController.handleRebuildIndex();
    }

    @FXML public void handleBackup() {
        Stage stage = (Stage) mainPane.getScene().getWindow();
        databaseToolsController.handleBackup(stage);
    }

    @FXML public void handleRestore() {
        Stage stage = (Stage) mainPane.getScene().getWindow();
        databaseToolsController.handleRestore(stage);
    }

    @FXML public void handleStatistics() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/statistics.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Parent root = loader.load();
            Stage stage = new Stage();
            stage.setTitle("📊 Статистика колекції");
            stage.setScene(new Scene(root, 600, 400));
            stage.initModality(Modality.WINDOW_MODAL);
            stage.initOwner(mainPane.getScene().getWindow());
            stage.show();
        } catch (Exception e) {
            log.error("Помилка відкриття статистики", e);
            dialogService.showError("Помилка", "Не вдалося відкрити статистику: " + e.getMessage());
        }
    }

    // ==================== ЗАГАЛЬНІ ДІЇ ====================

    @FXML public void handleRefresh() {
        refreshPresenter.refreshAll();
        eventPublisher.publishEvent(new NavigationRefreshEvent());
    }

    @FXML public void handleExit() { Platform.exit(); }

    @FXML public void handleAbout() {
        dialogService.showInfo("Про програму", "MyHomeLib Enterprise",
                "Версія 1.0.0-SNAPSHOT\nJava 21, Spring Boot 3.5, JavaFX 21");
    }

    @FXML public void handleBack() { workspaceManager.goBack(); updateNavigationButtons(); }
    @FXML public void handleForward() { workspaceManager.goForward(); updateNavigationButtons(); }
    @FXML public void handleHome() { showDashboard(); }

    // ==================== ЗАГЛУШКИ ====================

    @FXML public void handleEditMetadata() { dialogService.showInfo("Редагування метаданих", "Функція в розробці"); }
    @FXML public void handleDeleteBook() { dialogService.showInfo("Видалення книги", "Функція в розробці"); }
    @FXML public void handleShowColumns() { dialogService.showInfo("Налаштування колонок", "Функція в розробці"); }
    @FXML public void handleAddBook() { dialogService.showInfo("Додати книгу", "Використовуйте 'Імпорт'"); }
    @FXML public void handleSettings() { dialogService.showInfo("Налаштування", "Функція в розробці"); }
    @FXML public void handleImport() { showImportWorkspace(); }
    @FXML public void handleHistory() { dialogService.showInfo("Історія", "Функція в розробці"); }
}