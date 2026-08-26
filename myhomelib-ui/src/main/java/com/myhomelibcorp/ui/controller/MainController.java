package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.navigation.ArchiveNavigationKey;
import com.myhomelibcorp.application.navigation.NavigationMode;
import com.myhomelibcorp.application.navigation.ReviewNavigationFilter;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.GroupId;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import com.myhomelibcorp.ui.action.ActionCustomizationDialog;
import com.myhomelibcorp.ui.action.ActionRegistry;
import com.myhomelibcorp.ui.action.BookActionProfilesDialog;
import com.myhomelibcorp.ui.action.CoreActions;
import com.myhomelibcorp.ui.collection.CollectionWorkspaceController;
import com.myhomelibcorp.ui.event.NavigationRefreshEvent;
import com.myhomelibcorp.ui.navigation.NavigationPanelController;
import com.myhomelibcorp.ui.navigation.MainNavigationCoordinator;
import com.myhomelibcorp.ui.navigation.WorkspaceManager;
import com.myhomelibcorp.ui.opds.OpdsUiService;
import com.myhomelibcorp.ui.presenter.CollectionPresenter;
import com.myhomelibcorp.ui.service.*;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ToggleGroup;
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
    private final MainBookCommandCoordinator bookCommandCoordinator;
    private final MainNavigationCoordinator mainNavigationCoordinator;
    private final WorkspaceManager workspaceManager;
    private final NavigationHistoryService navigationHistory;
    private final ApplicationEventPublisher eventPublisher;
    private final ApplicationContext springContext;
    private final ActionRegistry actionRegistry;
    private final ActionCustomizationDialog actionCustomizationDialog;
    private final BookActionProfilesDialog bookActionProfilesDialog;
    private final OpdsUiService opdsUiService;

    // ===== Контролери =====
    private final CollectionController collectionController;
    private final GroupController groupController;
    private final BatchOperationsController batchOperationsController;
    private final ViewModeController viewModeController;
    private final ExportController exportController;
    private final DatabaseToolsController databaseToolsController;
    private final ImportController importController;
    private final NavigationPanelController navigationPanelController;
    private final CollectionWorkspaceController collectionWorkspaceController;
    private final CollectionPresenter collectionPresenter;
    private final com.myhomelibcorp.ui.service.ApplicationSettingsDialog applicationSettingsDialog;
    private final com.myhomelibcorp.ui.service.UserDataUiService userDataUiService;
    private final com.myhomelibcorp.ui.service.BookListExportService bookListExportService;
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
    @FXML private Menu recentBooksMenu;
    @FXML private MenuItem collectionsMenuItem;
    @FXML private MenuItem openInternalMenuItem;
    @FXML private MenuItem openExternalMenuItem;
    @FXML private MenuItem refreshMenuItem;
    @FXML private MenuItem importInpxMenuItem;
    @FXML private MenuItem exportMenuItem;
    @FXML private MenuItem settingsMenuItem;
    @FXML private MenuItem helpMenuItem;
    @FXML private MenuItem bookActionsMenuItem;
    @FXML private MenuItem customizeActionsMenuItem;
    @FXML private MenuItem opdsMenuItem;

    @FXML
    public void initialize() {
        log.info("MainController ініціалізовано");

        viewModeController.init(mainPane);
        workspaceManager.init(workspaceStackPane);
        backButton.disableProperty().bind(workspaceManager.canGoBackProperty().not());
        forwardButton.disableProperty().bind(workspaceManager.canGoForwardProperty().not());
        workspaceManager.canGoBackProperty().addListener((obs, oldValue, newValue) -> actionRegistry.refreshContexts());
        workspaceManager.canGoForwardProperty().addListener((obs, oldValue, newValue) -> actionRegistry.refreshContexts());

        searchField.setOnAction(event -> handleSearch());

        showDashboard();
        localizationService.apply(mainPane);
        populateLanguages();
        if (languageMenu != null) languageMenu.setOnShowing(event -> populateLanguages());
        if (recentBooksMenu != null) recentBooksMenu.setOnShowing(event -> mainNavigationCoordinator.populateRecentBooksMenu(recentBooksMenu));

        configureActionRegistry();
        updateNavigationButtons();
        appState.getBookTable().selectedBookProperty().addListener((obs, oldBook, newBook) -> actionRegistry.refreshContexts());
        mainPane.sceneProperty().addListener((obs, oldScene, scene) -> { if (scene != null) actionRegistry.attach(scene); });
        if (mainPane.getScene() != null) actionRegistry.attach(mainPane.getScene());

        log.info("MainController готовий до роботи");
    }

    private void populateLanguages() {
        if (languageMenu == null) return;
        var languages = localizationService.availableLanguages();
        var selectedLanguage = localizationService.language();
        var toggleGroup = new ToggleGroup();
        languageMenu.getItems().clear();

        for (var entry : languages.entrySet()) {
            RadioMenuItem item = new RadioMenuItem(entry.getValue());
            item.setToggleGroup(toggleGroup);
            item.setSelected(entry.getKey().equals(selectedLanguage));
            item.setOnAction(e -> {
                localizationService.setLanguage(entry.getKey());
                dialogService.showInfo(
                        "Мова / Language",
                        entry.getValue() + " — перезапустіть MyHomeLib, щоб застосувати мову до всіх вікон."
                );
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

    public void showYearWorkspace(int year) {
        workspaceManager.showYearWorkspace(year);
    }

    public void showLanguageWorkspace(String languageCode) {
        workspaceManager.showLanguageWorkspace(languageCode);
    }

    public void showArchiveWorkspace(ArchiveNavigationKey archive) {
        workspaceManager.showArchiveWorkspace(archive);
    }

    public void showKeywordWorkspace(String keyword) {
        workspaceManager.showKeywordWorkspace(keyword);
    }

    public void showGroupBooksWorkspace(GroupId groupId) {
        workspaceManager.showGroupBooksWorkspace(groupId);
    }

    public void showReviewsWorkspace(ReviewNavigationFilter filter) {
        workspaceManager.showReviewsWorkspace(filter);
    }

    public void showAllBooksWorkspace() {
        workspaceManager.showAllBooksWorkspace();
    }

    public void showUpdatesWorkspace() {
        workspaceManager.showUpdatesWorkspace();
    }

    public void showAlreadyReadWorkspace() {
        workspaceManager.showAlreadyReadWorkspace();
    }

    public void showHistoryWorkspace() {
        workspaceManager.showHistoryWorkspace();
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
        actionRegistry.refreshContexts();
    }

    private void configureActionRegistry() {
        actionRegistry.register(CoreActions.NAV_BACK, null, navigationHistory::canGoBack, this::handleBack);
        actionRegistry.register(CoreActions.NAV_FORWARD, null, navigationHistory::canGoForward, this::handleForward);
        actionRegistry.register(CoreActions.HELP_CONTEXT, helpMenuItem, () -> true, this::handleHelp);
        actionRegistry.register(CoreActions.SEARCH_FOCUS, null, () -> true, () -> { searchField.requestFocus(); searchField.selectAll(); });
        actionRegistry.register(CoreActions.VIEW_REFRESH, refreshMenuItem, () -> true, this::handleRefresh);
        actionRegistry.register(CoreActions.BOOK_OPEN_INTERNAL, openInternalMenuItem, bookCommandCoordinator::hasSelectedBook, this::handleOpenNewReader);
        actionRegistry.register(CoreActions.BOOK_OPEN_EXTERNAL, openExternalMenuItem, bookCommandCoordinator::hasSelectedBook, this::handleOpenExternalReader);
        actionRegistry.register(CoreActions.COLLECTION_MANAGE, collectionsMenuItem, () -> true, this::onCollections);
        actionRegistry.register(CoreActions.IMPORT_INPX, importInpxMenuItem, () -> true, this::handleImportInpx);
        actionRegistry.register(CoreActions.EXPORT_BOOKS, exportMenuItem, () -> true, this::handleExport);
        actionRegistry.register(CoreActions.SETTINGS, settingsMenuItem, () -> true, this::handleSettings);
        actionRegistry.register(CoreActions.BOOK_ACTIONS, bookActionsMenuItem, () -> true, this::handleBookActions);
        actionRegistry.register(CoreActions.ACTIONS_CUSTOMIZE, customizeActionsMenuItem, () -> true, this::handleCustomizeActions);
        actionRegistry.register(CoreActions.OPDS_MANAGE, opdsMenuItem, () -> true, this::handleOpds);
    }

    public void switchToCollection(Collection collection) {
        collectionController.switchToCollection(collection, this::showDashboard);
    }

    public BorderPane getMainPane() {
        return mainPane;
    }

    // ==================== Reader ====================

    public void cleanupReader() {
        mainNavigationCoordinator.cleanupReader();
    }

    // ==================== FXML дії ====================

    @FXML
    public void handleSearch() {
        String query = searchField.getText();
        if (query != null && !query.isBlank()) mainNavigationCoordinator.search(query);
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
    public void handleOpds() {
        opdsUiService.show(mainPane.getScene().getWindow());
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
    public void onAuthors() { mainNavigationCoordinator.authors(); }

    @FXML
    public void onSeries() { mainNavigationCoordinator.series(); }

    @FXML
    public void onGenres() { mainNavigationCoordinator.genres(); }

    @FXML
    public void onAllBooks() { mainNavigationCoordinator.allBooks(); }

    @FXML
    public void onCollections() { mainNavigationCoordinator.collections(); }

    @FXML
    public void onGroups() {
        cleanupReader();
        Group currentGroup = appState.getCurrentGroup();
        showGroupWorkspace(currentGroup);
    }

    @FXML
    public void onNewBooks() { mainNavigationCoordinator.newBooks(); }

    @FXML
    public void onUpdates() { mainNavigationCoordinator.updates(); }

    @FXML
    public void onAlreadyRead() { mainNavigationCoordinator.alreadyRead(); }

    @FXML
    public void onHistory() { mainNavigationCoordinator.history(); }

    @FXML
    public void onClearHistory() { mainNavigationCoordinator.clearHistory(recentBooksMenu); }

    @FXML
    public void onSearch() { mainNavigationCoordinator.search(searchField.getText()); }

    @FXML
    public void onImport() { mainNavigationCoordinator.importWorkspace(); }

    // ==================== Дії з колекціями ====================

    @FXML
    public void handleNewCollection() {
        Stage stage = (Stage) mainPane.getScene().getWindow();
        collectionController.handleNewCollection(stage, this::showDashboard);
    }

    @FXML
    public void handleRenameCollection() {
        cleanupReader();
        showCollectionWorkspace();
    }

    @FXML
    public void handleDeleteCollection() {
        cleanupReader();
        showCollectionWorkspace();
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

            // Передаємо список колекцій у візард
            if (collectionWorkspaceController != null) {
                controller.setCollectionList(collectionWorkspaceController.getCollectionList());
            }

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
    public void handleEditMetadata() { bookCommandCoordinator.editMetadata(mainPane.getScene().getWindow(), this::handleRefresh); }

    @FXML
    public void handleDeleteBook() { bookCommandCoordinator.deleteBook(mainPane.getScene().getWindow(), this::handleRefresh); }

    @FXML
    public void handleAddBook() {
        importController.importFb2(this::handleRefresh);
    }

    // ==================== МЕТОДИ ДЛЯ РОБОТИ З READER ====================

    @FXML
    public void handleOpenNewReader() { bookCommandCoordinator.openInternal(); }

    @FXML
    public void handleDownloadBook() { bookCommandCoordinator.download(); }

    @FXML
    public void handleRemoveLocalCopy() { bookCommandCoordinator.removeLocalCopy(this::handleRefresh); }

    @FXML
    public void handleCancelDownload() { bookCommandCoordinator.cancelDownload(); }

    public void openInNewReader(BookId bookId) { bookCommandCoordinator.openInNewReader(bookId); }

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

    @FXML public void handleOpenExternalReader() { bookCommandCoordinator.openExternal(); }

    @FXML public void handleCustomizeActions() {
        actionCustomizationDialog.show(mainPane.getScene() == null ? null : mainPane.getScene().getWindow());
    }

    @FXML public void handleBookActions() {
        bookActionProfilesDialog.show(mainPane.getScene() == null ? null : mainPane.getScene().getWindow());
        if (appState.getBookTableController() != null) appState.getBookTableController().refreshRows();
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
    @FXML
    public void handleResetNavigation() {
        navigationPanelController.resetNavigation();
        dialogService.showInfo("Навігація", "Навігаційну панель скинуто.");
    }
}