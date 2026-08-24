package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import com.myhomelibcorp.ui.event.NavigationRefreshEvent;
import com.myhomelibcorp.ui.navigation.NavigationPanelController;
import com.myhomelibcorp.ui.navigation.WorkspaceManager;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.BookDownloadCoordinator;
import com.myhomelibcorp.ui.service.NavigationHistoryService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
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

@Component
@RequiredArgsConstructor
@Slf4j
public class MainController {

    // ===== Залежності =====
    private final ApplicationState appState;
    private final DialogService dialogService;
    private final BookDownloadCoordinator bookDownloadCoordinator;
    private final WorkspaceManager workspaceManager;
    private final NavigationHistoryService navigationHistory;
    private final ApplicationEventPublisher eventPublisher;
    private final ApplicationContext springContext;

    // ===== Контролери =====
    private final CollectionController collectionController;
    private final GroupController groupController;
    private final BatchOperationsController batchOperationsController;
    private final ViewModeController viewModeController;
    private final ExportController exportController;
    private final DatabaseToolsController databaseToolsController;
    private final ImportController importController;
    private final NavigationPanelController navigationPanelController;
    private final com.myhomelibcorp.ui.service.ApplicationSettingsDialog applicationSettingsDialog;
    private final com.myhomelibcorp.ui.service.ClassicLibraryActionsService classicActions;
    private final com.myhomelibcorp.ui.service.UserDataUiService userDataUiService;
    private final com.myhomelibcorp.ui.service.BookListExportService bookListExportService;
    private final com.myhomelibcorp.ui.service.ExternalBookLauncher externalBookLauncher;
    private final com.myhomelibcorp.ui.service.BookLoaderService bookLoaderService;
    private final com.myhomelibcorp.ui.service.HelpService helpService;
    private final com.myhomelibcorp.ui.service.CollectionCopyUiService collectionCopyUiService;
    private final com.myhomelibcorp.ui.service.CollectionAttachUiService collectionAttachUiService;
    private final com.myhomelibcorp.ui.service.CollectionUpdateUiService collectionUpdateUiService;
    private final com.myhomelibcorp.ui.service.LocalizationService localizationService;
    private final com.myhomelibcorp.ui.service.CollectionPropertiesUiService collectionPropertiesUiService;

    // ===== FXML =====
    @FXML private BorderPane mainPane;
    @FXML private TextField searchField;
    @FXML private Button backButton;
    @FXML private Button forwardButton;
    @FXML private StackPane workspaceStackPane;
    @FXML private Menu languageMenu;

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
        localizationService.apply(mainPane);
        populateExternalLanguages();
        mainPane.sceneProperty().addListener((obs, oldScene, scene) -> {
            if (scene != null) scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
                if (e.getCode() == javafx.scene.input.KeyCode.F1) { handleHelp(); e.consume(); }
            });
        });

        log.info("MainController готовий до роботи");
    }


    private void populateExternalLanguages() {
        if (languageMenu == null) return;
        var extras = localizationService.availableLanguages().entrySet().stream()
                .filter(e -> !java.util.Set.of("uk", "en", "bg").contains(e.getKey()))
                .toList();
        if (extras.isEmpty()) return;
        languageMenu.getItems().add(new SeparatorMenuItem());
        for (var entry : extras) {
            MenuItem item = new MenuItem(entry.getValue());
            item.setOnAction(e -> {
                localizationService.setLanguage(entry.getKey());
                dialogService.showInfo("Мова / Language", entry.getValue() + " — перезапустіть MyHomeLib, щоб застосувати мову до всіх вікон.");
            });
            languageMenu.getItems().add(item);
        }
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

    /**
     * НОВИЙ МЕТОД: відкриває новий Reader (без WebView).
     */
    public void showNewReaderWorkspace(BookId bookId) {
        workspaceManager.showNewReaderWorkspace(bookId);
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

    public void cleanupReader() {
        // WorkspaceManager owns the active Reader lifecycle and disposes it on navigation.
        workspaceManager.disposeCurrentReaderIfActive();
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
        dialogService.showInfo("Про програму", "MyHomeLib",
                "Версія 1.0.0\nJava 21, Spring Boot 3.5, JavaFX 21\n\n" +
                        "Новий Reader на Canvas (без WebView)");
    }

    @FXML
    public void handleSettings() {
        applicationSettingsDialog.show(mainPane.getScene().getWindow());
    }

    @FXML
    public void handleCollectionProperties() {
        var updated = collectionPropertiesUiService.show(mainPane.getScene().getWindow());
        if (updated != null) { eventPublisher.publishEvent(new NavigationRefreshEvent()); navigationPanelController.refreshAll(); }
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
        showSearchResults(classicActions.newBooks(500));
    }

    @FXML
    public void onHistory() {
        cleanupReader();
        showSearchResults(classicActions.readingHistory(500));
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
        if (appState.getBookTableController() != null) appState.getBookTableController().showColumnChooser();
        else dialogService.showWarning("Таблиця недоступна", "Відкрийте список книг.");
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
        BookDto selected = appState.getBookDetails().getCurrentBook();
        if (selected == null) { dialogService.showWarning("Немає книги", "Спочатку виберіть книгу."); return; }
        if (classicActions.editBook(mainPane.getScene().getWindow(), BookId.fromString(selected.getId()))) handleRefresh();
    }

    @FXML
    public void handleDeleteBook() {
        BookDto selected = appState.getBookDetails().getCurrentBook();
        if (selected == null) { dialogService.showWarning("Немає книги", "Спочатку виберіть книгу."); return; }
        if (classicActions.deleteBook(mainPane.getScene().getWindow(), BookId.fromString(selected.getId()))) {
            appState.getBookDetails().setCurrentBook(null); handleRefresh();
        }
    }

    @FXML
    public void handleAddBook() {
        importController.importFb2(this::handleRefresh);
    }

    // ==================== МЕТОДИ ДЛЯ РОБОТИ З READER ====================

    @FXML
    public void handleOpenNewReader() {
        BookDto selectedBook = appState.getBookDetails().getCurrentBook();
        if (selectedBook == null) {
            dialogService.showWarning("Немає книги", "Спочатку виберіть книгу в таблиці.");
            return;
        }
        bookDownloadCoordinator.ensureLocal(selectedBook).whenComplete((path, error) -> {
            if (error == null) Platform.runLater(() -> showNewReaderWorkspace(BookId.fromString(selectedBook.getId())));
        });
    }

    @FXML
    public void handleDownloadBook() {
        BookDto selectedBook = appState.getBookDetails().getCurrentBook();
        if (selectedBook == null) {
            dialogService.showWarning("Немає книги", "Спочатку виберіть книгу в таблиці.");
            return;
        }
        bookDownloadCoordinator.ensureLocal(selectedBook);
    }

    @FXML
    public void handleRemoveLocalCopy() {
        BookDto selectedBook = appState.getBookDetails().getCurrentBook();
        if (selectedBook == null) {
            dialogService.showWarning("Немає книги", "Спочатку виберіть книгу в таблиці.");
            return;
        }
        bookDownloadCoordinator.removeLocalCopy(selectedBook).whenComplete((count, error) -> {
            if (error == null) Platform.runLater(this::handleRefresh);
        });
    }

    @FXML
    public void handleCancelDownload() {
        BookDto selectedBook = appState.getBookDetails().getCurrentBook();
        if (selectedBook == null || !bookDownloadCoordinator.cancel(selectedBook)) {
            dialogService.showInfo("Завантаження", "Для вибраної книги активного завантаження немає.");
        }
    }

    public void openInNewReader(BookId bookId) {
        if (bookId != null) {
            showNewReaderWorkspace(bookId);
        }
    }

    @FXML
    public void handleCloseReader() {
        cleanupReader();
        showDashboard();
        dialogService.showInfo("Reader закрито", "Поточну книгу закрито.");
    }
    @FXML public void handleExportUserData() { userDataUiService.exportData(mainPane.getScene().getWindow()); }
    @FXML public void handleImportUserData() { userDataUiService.importData(mainPane.getScene().getWindow()); handleRefresh(); }
    @FXML public void handleExportListHtml() { bookListExportService.export(mainPane.getScene().getWindow(), "html"); }
    @FXML public void handleExportListTxt() { bookListExportService.export(mainPane.getScene().getWindow(), "txt"); }
    @FXML public void handleExportListRtf() { bookListExportService.export(mainPane.getScene().getWindow(), "rtf"); }

    @FXML public void handleOpenExternalReader() {
        BookDto selected = appState.getBookDetails().getCurrentBook();
        if (selected == null) { dialogService.showWarning("Немає книги", "Спочатку виберіть книгу."); return; }
        bookDownloadCoordinator.ensureLocal(selected).whenComplete((p,e) -> {
            if (e != null) return;
            try { externalBookLauncher.open(selected); }
            catch (Exception ex) { Platform.runLater(() -> dialogService.showError("Зовнішня читалка", ex.getMessage())); }
        });
    }


    @FXML public void handleLanguageUkrainian() {
        localizationService.setLanguage("uk");
        dialogService.showInfo("Мова / Language", "Українську мову вибрано. Перезапустіть MyHomeLib.");
    }

    @FXML public void handleLanguageEnglish() {
        localizationService.setLanguage("en");
        dialogService.showInfo("Language / Мова", "English selected. Restart MyHomeLib to apply it to every window.");
    }

    @FXML public void handleLanguageBulgarian() {
        localizationService.setLanguage("bg");
        dialogService.showInfo("Език / Мова", "Българският език е избран. Рестартирайте MyHomeLib.");
    }
    @FXML public void handleHelp() { helpService.show(mainPane.getScene() == null ? null : mainPane.getScene().getWindow(), workspaceManager.currentHelpTopic()); }
    @FXML public void handleInpxHelp() { helpService.show(mainPane.getScene() == null ? null : mainPane.getScene().getWindow(), "inpx"); }

    @FXML public void handleClearGroup() { groupController.handleClearGroup(this::handleRefresh); }

    @FXML public void handleCopyToCollection() { collectionCopyUiService.copySelected(mainPane.getScene().getWindow(), this::handleRefresh); }


    @FXML public void handleUpdateCollectionManual() { importController.importInpx(this::handleRefresh); }
    @FXML public void handleUpdateCollectionNetwork() { collectionUpdateUiService.updateFromNetwork(mainPane.getScene().getWindow(), this::handleRefresh); }
    @FXML public void handleCancelCollectionUpdate() { if(!collectionUpdateUiService.cancel()) dialogService.showInfo("Оновлення", "Активного оновлення колекції немає."); }

    @FXML public void handleAttachCollection() {
        try {
            var result = collectionAttachUiService.attach(mainPane.getScene().getWindow());
            if (result != null) collectionController.switchToCollection(result.collection(), this::showDashboard);
        } catch (Exception e) {
            dialogService.showError("Підключення колекції", e.getMessage());
        }
    }

}
