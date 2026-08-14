package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import com.myhomelibcorp.reader.service.ReaderFacade;
import com.myhomelibcorp.reader.session.ReaderSession;
import com.myhomelibcorp.reader.session.ReaderSessionManager;
import com.myhomelibcorp.ui.event.NavigationRefreshEvent;
import com.myhomelibcorp.ui.navigation.NavigationPanelController;
import com.myhomelibcorp.ui.navigation.WorkspaceManager;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.NavigationHistoryService;
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
import javafx.scene.layout.StackPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Головний контролер програми.
 * Координує всі воркспейси та навігацію.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MainController {

    // ===== Залежності =====
    private final ApplicationState appState;
    private final DialogService dialogService;
    private final WorkspaceManager workspaceManager;
    private final NavigationHistoryService navigationHistory;
    private final ApplicationEventPublisher eventPublisher;
    private final ReaderFacade readerFacade;
    private final ApplicationContext springContext;
    private final ReaderSessionManager readerSessionManager;

    // ===== Контролери =====
    private final CollectionController collectionController;
    private final GroupController groupController;
    private final BatchOperationsController batchOperationsController;
    private final ViewModeController viewModeController;
    private final ExportController exportController;
    private final DatabaseToolsController databaseToolsController;
    private final ImportController importController;
    private final NavigationPanelController navigationPanelController;

    // ===== FXML =====
    @FXML private BorderPane mainPane;
    @FXML private TextField searchField;
    @FXML private Button backButton;
    @FXML private Button forwardButton;
    @FXML private StackPane workspaceStackPane;

    // ===== Стан =====
    private Pane currentWorkspace;

    @FXML
    public void initialize() {
        log.info("MainController ініціалізовано");

        viewModeController.init(mainPane);
        workspaceManager.setMainController(this);
        workspaceManager.init(workspaceStackPane);
        navigationHistory.setMainController(this);

        searchField.setOnAction(event -> handleSearch());

        showDashboard();
        updateNavigationButtons();

        log.info("MainController готовий до роботи");
    }

    // ==================== Навігація по воркспейсах ====================

    public void showDashboard() {
        workspaceManager.showDashboard();
    }

    public void showAuthorWorkspace(AuthorId authorId) {
        workspaceManager.showAuthorWorkspace(authorId);
    }

    public void showBookWorkspace(BookId bookId) {
        workspaceManager.showBookWorkspace(bookId);
    }

    public void showSeriesWorkspace(SeriesId seriesId) {
        workspaceManager.showSeriesWorkspace(seriesId);
    }

    public void showGenreWorkspace(GenreId genreId) {
        workspaceManager.showGenreWorkspace(genreId);
    }

    public void showSearchResults(String query) {
        workspaceManager.showSearchResults(query);
    }

    public void showSearchResults(List<BookDto> results) {
        workspaceManager.showSearchResults(results);
    }

    public void showCollectionWorkspace() {
        workspaceManager.showCollectionWorkspace();
    }

    public void showGroupWorkspace(Group group) {
        workspaceManager.showGroupWorkspace(group);
    }

    public void showReaderWorkspace(BookId bookId) {
        workspaceManager.showReaderWorkspace(bookId);
    }

    public void showImportWorkspace() {
        workspaceManager.showImportWorkspace();
    }

    public void setWorkspace(Pane workspace) {
        workspaceManager.setWorkspace(workspace, "custom");
    }

    public void updateNavigationButtons() {
        backButton.setDisable(!navigationHistory.canGoBack());
        forwardButton.setDisable(!navigationHistory.canGoForward());
    }

    public void switchToCollection(Collection collection) {
        collectionController.switchToCollection(collection, this::showDashboard);
    }

    public BorderPane getMainPane() {
        return mainPane;
    }

    // ==================== Reader ====================

    /**
     * Очищує Reader при переході на інший воркспейс.
     */
    public void cleanupReader() {
        if (readerFacade.isBookOpen()) {
            ReaderSession session = readerSessionManager.getCurrentSession();
            if (session != null) {
                log.info("Очищення Reader при переході...");
                readerFacade.saveCurrentPosition();
                readerFacade.closeBook();
            }
        }
    }

    // ==================== FXML дії ====================

    @FXML
    public void handleSearch() {
        String query = searchField.getText();
        if (query != null && !query.isBlank()) {
            cleanupReader();
            showSearchResults(query);
        }
    }

    @FXML
    public void handleRefresh() {
        eventPublisher.publishEvent(new NavigationRefreshEvent());
        navigationPanelController.refreshAll();
        showDashboard();
    }

    @FXML
    public void handleBack() {
        navigationHistory.goBack();
        updateNavigationButtons();
    }

    @FXML
    public void handleForward() {
        navigationHistory.goForward();
        updateNavigationButtons();
    }

    @FXML
    public void handleHome() {
        cleanupReader();
        showDashboard();
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
    public void handleSettings() {
        dialogService.showInfo("Налаштування", "Функція налаштувань", "Розробляється...");
    }

    // ==================== Навігаційні дії ====================

    @FXML
    public void onAuthors() {
        cleanupReader();
        navigationPanelController.onAuthors();
    }

    @FXML
    public void onSeries() {
        cleanupReader();
        navigationPanelController.onSeries();
    }

    @FXML
    public void onGenres() {
        cleanupReader();
        navigationPanelController.onGenres();
    }

    @FXML
    public void onCollections() {
        cleanupReader();
        showCollectionWorkspace();
    }

    @FXML
    public void onGroups() {
        cleanupReader();
        Group currentGroup = appState.getCurrentGroup();
        if (currentGroup != null) {
            showGroupWorkspace(currentGroup);
        } else {
            showGroupWorkspace(null);
        }
    }

    @FXML
    public void onNewBooks() {
        cleanupReader();
        dialogService.showInfo("Нові книги", "Функція нових книг", "Розробляється...");
    }

    @FXML
    public void onHistory() {
        cleanupReader();
        dialogService.showInfo("Історія", "Функція історії", "Розробляється...");
    }

    @FXML
    public void onSearch() {
        cleanupReader();
        String query = searchField.getText();
        if (query != null && !query.isBlank()) {
            showSearchResults(query);
        } else {
            showSearchResults("");
        }
    }

    @FXML
    public void onImport() {
        cleanupReader();
        showImportWorkspace();
    }

    // ==================== Дії з колекціями ====================

    @FXML
    public void handleNewCollection() {
        Stage stage = (Stage) mainPane.getScene().getWindow();
        collectionController.handleNewCollection(stage, this::showDashboard);
    }

    @FXML
    public void handleRenameCollection() {
        collectionController.handleRenameCollection(this::showDashboard);
    }

    @FXML
    public void handleDeleteCollection() {
        collectionController.handleDeleteCollection(this::showDashboard);
    }

    @FXML
    public void handleSelectCollection() {
        collectionController.handleSelectCollection(this::showDashboard);
    }

    @FXML
    public void handleCollectionWizard() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/collection-wizard.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Parent root = loader.load();

            CollectionWizardController controller = loader.getController();
            Stage stage = new Stage();
            stage.setTitle("Майстер створення колекції");
            stage.setScene(new Scene(root, 620, 480));
            stage.initModality(Modality.WINDOW_MODAL);
            stage.initOwner(mainPane.getScene().getWindow());
            controller.setStage(stage);
            controller.setOnComplete(() -> {
                eventPublisher.publishEvent(new NavigationRefreshEvent());
                navigationPanelController.refreshAll();
                showDashboard();
            });
            stage.show();

        } catch (Exception e) {
            log.error("Помилка відкриття майстра колекцій", e);
            dialogService.showError("Помилка", "Не вдалося відкрити майстер: " + e.getMessage());
        }
    }

    // ==================== Дії з групами ====================

    @FXML
    public void handleAddGroup() {
        groupController.handleAddGroup(this::showDashboard);
    }

    @FXML
    public void handleEditGroup() {
        groupController.handleEditGroup(this::showDashboard);
    }

    @FXML
    public void handleDeleteGroup() {
        groupController.handleDeleteGroup(this::showDashboard);
    }

    // ==================== Пакетні операції ====================

    @FXML
    public void handleBatchRate() {
        batchOperationsController.handleBatchRate(this::handleRefresh);
    }

    @FXML
    public void handleBatchMarkRead() {
        batchOperationsController.handleBatchMarkRead(this::handleRefresh);
    }

    @FXML
    public void handleBatchAddToGroup() {
        batchOperationsController.handleBatchAddToGroup(this::handleRefresh);
    }

    @FXML
    public void handleClearSelection() {
        batchOperationsController.handleClearSelection();
    }

    // ==================== Вигляд ====================

    @FXML
    public void handleToggleView() {
        viewModeController.toggleView();
    }

    @FXML
    public void handleShowColumns() {
        dialogService.showInfo("Налаштування колонок", "Функція налаштування колонок", "Розробляється...");
    }

    // ==================== Експорт ====================

    @FXML
    public void handleExport() {
        exportController.handleExport(mainPane);
    }

    @FXML
    public void handleExportInpx() {
        exportController.handleExportInpx(mainPane, this::handleRefresh);
    }

    // ==================== Імпорт ====================

    @FXML
    public void handleImportFb2() {
        importController.importFb2(this::handleRefresh);
    }

    @FXML
    public void handleImportInpx() {
        importController.importInpx(this::handleRefresh);
    }

    @FXML
    public void handleImportDirectory() {
        importController.importDirectory(this::handleRefresh);
    }

    @FXML
    public void handleSyncFolder() {
        importController.handleSyncFolder(this::handleRefresh);
    }

    // ==================== Інструменти БД ====================

    @FXML
    public void handleCheckIntegrity() {
        Stage stage = (Stage) mainPane.getScene().getWindow();
        databaseToolsController.handleCheckIntegrity(stage);
    }

    @FXML
    public void handleVacuum() {
        databaseToolsController.handleVacuum();
    }

    @FXML
    public void handleRebuildIndex() {
        databaseToolsController.handleRebuildIndex();
    }

    @FXML
    public void handleBackup() {
        Stage stage = (Stage) mainPane.getScene().getWindow();
        databaseToolsController.handleBackup(stage);
    }

    @FXML
    public void handleRestore() {
        Stage stage = (Stage) mainPane.getScene().getWindow();
        databaseToolsController.handleRestore(stage);
    }

    @FXML
    public void handleStatistics() {
        Stage stage = (Stage) mainPane.getScene().getWindow();
        databaseToolsController.handleStatistics(stage);
    }

    // ==================== Редагування книг ====================

    @FXML
    public void handleEditMetadata() {
        dialogService.showInfo("Редагування метаданих", "Функція редагування метаданих", "Розробляється...");
    }

    @FXML
    public void handleDeleteBook() {
        dialogService.showInfo("Видалення книги", "Функція видалення книги", "Розробляється...");
    }

    @FXML
    public void handleAddBook() {
        dialogService.showInfo("Додати книгу", "Функція додавання книги", "Розробляється...");
    }
}