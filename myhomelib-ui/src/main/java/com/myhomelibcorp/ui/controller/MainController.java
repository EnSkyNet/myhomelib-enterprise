package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.session.SessionService;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.ui.action.ActionCustomizationDialog;
import com.myhomelibcorp.ui.action.ActionRegistry;
import com.myhomelibcorp.ui.action.BookActionProfilesDialog;
import com.myhomelibcorp.ui.action.CoreActions;
import com.myhomelibcorp.ui.accessibility.UiAccessibilitySupport;
import com.myhomelibcorp.ui.collection.CollectionWorkspaceController;
import com.myhomelibcorp.ui.duplicate.DuplicateReviewUiService;
import com.myhomelibcorp.ui.event.NavigationRefreshEvent;
import com.myhomelibcorp.ui.navigation.NavigationPanelController;
import com.myhomelibcorp.ui.navigation.MainNavigationCoordinator;
import com.myhomelibcorp.ui.navigation.WorkspaceManager;
import com.myhomelibcorp.ui.opds.OpdsUiService;
import com.myhomelibcorp.ui.service.*;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;


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
    private final ApplicationEventPublisher eventPublisher;
    private final ApplicationContext springContext;
    private final ActionRegistry actionRegistry;
    private final ActionCustomizationDialog actionCustomizationDialog;
    private final BookActionProfilesDialog bookActionProfilesDialog;
    private final OpdsUiService opdsUiService;
    private final MainLayoutService mainLayoutService;
    private final CatalogUpdateBadgeService catalogUpdateBadgeService;
    private final ApplicationThemeService applicationThemeService;
    private final DuplicateReviewUiService duplicateReviewUiService;
    private final BookSelectionService bookSelectionService;
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
    @FXML private HBox mainToolbar;
    @FXML private FlowPane selectionToolbar;
    @FXML private Label selectedBooksLabel;
    @FXML private Label activeScopeLabel;
    @FXML private TextField searchField;
    @FXML private Button backButton;
    @FXML private Button forwardButton;
    @FXML private StackPane workspaceStackPane;
    @FXML private StackPane leftSidebarContainer;
    @FXML private Pane rightSidebarContainer;
    @FXML private Menu languageMenu;
    @FXML private Menu viewMenu;
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
    @FXML private MenuItem updatesMenuItem;
    @FXML private CheckMenuItem leftSidebarMenuItem;
    @FXML private CheckMenuItem rightSidebarMenuItem;

    @FXML
    public void initialize() {
        log.info("MainController ініціалізовано");

        viewModeController.init(mainPane);
        workspaceManager.init(workspaceStackPane);
        mainLayoutService.registerSidebars(leftSidebarContainer, rightSidebarContainer);
        bindSidebarMenuItems();
        backButton.disableProperty().bind(workspaceManager.canGoBackProperty().not());
        forwardButton.disableProperty().bind(workspaceManager.canGoForwardProperty().not());
        workspaceManager.canGoBackProperty().addListener((obs, oldValue, newValue) -> actionRegistry.refreshContexts());
        workspaceManager.canGoForwardProperty().addListener((obs, oldValue, newValue) -> actionRegistry.refreshContexts());

        searchField.setOnAction(event -> handleSearch());

        showDashboard();
        localizationService.apply(mainPane);
        UiAccessibilitySupport.enhance(mainPane);
        populateLanguages();
        if (languageMenu != null) languageMenu.setOnShowing(event -> populateLanguages());
        if (recentBooksMenu != null) recentBooksMenu.setOnShowing(event -> mainNavigationCoordinator.populateRecentBooksMenu(recentBooksMenu));
        if (viewMenu != null) viewMenu.setOnShowing(event -> refreshUpdateBadge());
        appState.currentLibraryCollectionProperty().addListener((obs, oldCollection, newCollection) -> {
            refreshUpdateBadge();
            updateActiveScope();
        });
        bookSelectionService.selectedCountProperty().addListener((obs, oldCount, newCount) -> updateSelectionToolbar());
        updateActiveScope();
        updateSelectionToolbar();
        Platform.runLater(this::refreshUpdateBadge);
        configureActionRegistry();
        updateNavigationButtons();
        // Book commands use BookDetailsViewModel as the canonical current-book source across
        // the classic table, Search, Author and Reader workspaces. Refreshing from BookTableViewModel
        // was too early (before BookDetails was updated) and did not run at all for non-classic tables,
        // leaving “Open in Reader / external reader” permanently disabled.
        bookCommandCoordinator.selectedBookProperty().addListener((obs, oldBook, newBook) -> actionRegistry.refreshContexts());
        mainPane.sceneProperty().addListener((obs, oldScene, scene) -> { if (scene != null) actionRegistry.attach(scene); });
        if (mainPane.getScene() != null) actionRegistry.attach(mainPane.getScene());

        Platform.runLater(() -> collectionUpdateUiService.autoUpdateOnStartup(
                mainPane.getScene() == null ? null : mainPane.getScene().getWindow(),
                () -> eventPublisher.publishEvent(new NavigationRefreshEvent())));

        log.info("MainController готовий до роботи");
    }

    private void updateSelectionToolbar() {
        int count = bookSelectionService.count();
        if (selectionToolbar != null) {
            selectionToolbar.setVisible(count > 0);
            selectionToolbar.setManaged(count > 0);
        }
        if (selectedBooksLabel != null) {
            selectedBooksLabel.setText(count <= 0
                    ? localizationService.text("ui.main.selection.none")
                    : localizationService.format("ui.main.selection.count", count));
        }
    }

    private void updateActiveScope() {
        if (activeScopeLabel == null) return;
        Collection collection = appState.getCurrentLibraryCollection();
        String name = collection == null || collection.getName() == null || collection.getName().isBlank()
                ? localizationService.text("ui.main.scope.none")
                : collection.getName();
        activeScopeLabel.setText(localizationService.format("ui.main.scope.collection", name));
        activeScopeLabel.setTooltip(new javafx.scene.control.Tooltip(
                localizationService.format("ui.main.scope.collection_tooltip", name)));
    }

    private void refreshUpdateBadge() {
        catalogUpdateBadgeService.refresh(updatesMenuItem, currentCollectionId(), this::currentCollectionId);
    }

    private String currentCollectionId() {
        Collection collection = appState.getCurrentLibraryCollection();
        return collection == null || collection.getId() == null ? "" : collection.getId();
    }

    private void bindSidebarMenuItems() {
        if (leftSidebarMenuItem != null) {
            leftSidebarMenuItem.setSelected(mainLayoutService.isLeftSidebarVisible());
            leftSidebarMenuItem.selectedProperty().bindBidirectional(mainLayoutService.leftSidebarVisibleProperty());
        }
        if (rightSidebarMenuItem != null) {
            rightSidebarMenuItem.setSelected(mainLayoutService.isRightSidebarVisible());
            rightSidebarMenuItem.selectedProperty().bindBidirectional(mainLayoutService.rightSidebarVisibleProperty());
        }
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
                        localizationService.text("ui.main.language.changed_title"),
                        localizationService.format("ui.main.language.restart_required", entry.getValue())
                );
            });
            languageMenu.getItems().add(item);
        }
    }
    // ==================== Навігація по воркспейсах ====================
    public void showDashboard() {
        workspaceManager.showDashboard();
    }

    /** Bootstrap-only restoration entry; all runtime navigation goes through WorkspaceManager/coordinators. */
    public void restoreSessionWorkspace(SessionService.WorkspaceState state) {
        if (state != null) workspaceManager.restoreSessionWorkspace(state);
    }

    public void updateNavigationButtons() {
        actionRegistry.refreshContexts();
    }

    private void configureActionRegistry() {
        actionRegistry.register(CoreActions.NAV_BACK, null, workspaceManager::canGoBack, this::handleBack);
        actionRegistry.register(CoreActions.NAV_FORWARD, null, workspaceManager::canGoForward, this::handleForward);
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
    public void handleClearSearch() {
        searchField.clear();
        searchField.requestFocus();
    }

    @FXML
    public void handleCycleApplicationTheme() {
        applicationThemeService.cyclePreset();
    }

    @FXML
    public void handleRefresh() {
        eventPublisher.publishEvent(new NavigationRefreshEvent());
        navigationPanelController.refreshAll();
        refreshUpdateBadge();
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
        dialogService.showInfo(localizationService.text("ui.main.about.title"), "MyHomeLib",
                localizationService.format("ui.main.about.body", SupportBundleService.runtimeVersion()));
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
        workspaceManager.showGroupWorkspace(currentGroup);
    }

    @FXML
    public void onNewBooks() { mainNavigationCoordinator.newBooks(); }

    @FXML
    public void onUpdates() {
        mainNavigationCoordinator.updates();
        refreshUpdateBadge();
    }

    @FXML
    public void onFollowedAuthors() { mainNavigationCoordinator.followedAuthors(); }

    @FXML
    public void onAnnotations() {
        mainNavigationCoordinator.annotations();
    }

    @FXML
    public void onOperations() {
        cleanupReader();
        workspaceManager.showOperationCenterWorkspace();
    }

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
            stage.setTitle(localizationService.text("ui.collection.wizard.title"));
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
            dialogService.showError(localizationService.text("common.error"), localizationService.format("ui.collection.wizard.open_error", e.getMessage()));
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
    public void handleBatchProgress() {
        batchOperationsController.handleBatchProgress(this::handleRefresh);
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
    public void handleToggleLeftSidebar() {
        // selectedProperty is bound bidirectionally to MainLayoutService; this
        // handler exists as the explicit FXML command and keeps the action clear.
        mainLayoutService.setLeftSidebarVisible(leftSidebarMenuItem == null
                ? !mainLayoutService.isLeftSidebarVisible()
                : leftSidebarMenuItem.isSelected());
    }

    @FXML
    public void handleToggleRightSidebar() {
        mainLayoutService.setRightSidebarVisible(rightSidebarMenuItem == null
                ? !mainLayoutService.isRightSidebarVisible()
                : rightSidebarMenuItem.isSelected());
    }

    @FXML
    public void handleShowColumns() {
        if (workspaceManager.showColumnChooserForCurrentWorkspace()) return;
        if (appState.getBookTableController() != null) {
            appState.getBookTableController().showColumnChooser();
            return;
        }
        dialogService.showWarning(localizationService.text("ui.main.columns.unavailable_title"), localizationService.text("ui.main.columns.unavailable_message"));
    }
    // ==================== Експорт ====================
    @FXML
    public void handleExport() {
        exportController.handleExport(mainPane == null || mainPane.getScene() == null ? null : mainPane.getScene().getWindow(),
                this::refreshAfterSuccessfulExport);
    }

    private void refreshAfterSuccessfulExport() {
        if (appState.getBookTableController() != null) appState.getBookTableController().refreshRows();
        eventPublisher.publishEvent(new NavigationRefreshEvent());
        navigationPanelController.refreshAll();
        refreshUpdateBadge();
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
    public void handleDuplicates() {
        duplicateReviewUiService.show(mainPane.getScene() == null ? null : mainPane.getScene().getWindow());
    }

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
    public void handleBatchMetadata() { bookCommandCoordinator.editBatchMetadata(mainPane.getScene().getWindow(), this::handleRefresh); }

    @FXML
    public void handleLocalBatchMetadata() { bookCommandCoordinator.editLocalBatchMetadata(mainPane.getScene().getWindow(), this::handleRefresh); }

    @FXML
    public void handleUndoLastLibraryOperation() { bookCommandCoordinator.undoLastLibraryOperation(mainPane.getScene().getWindow(), this::handleRefresh); }

    @FXML
    public void handleManageCustomFields() { bookCommandCoordinator.manageCustomFields(mainPane.getScene().getWindow()); }

    @FXML
    public void handleEditCustomFieldValues() { bookCommandCoordinator.editCustomFieldValues(mainPane.getScene().getWindow()); }

    @FXML
    public void handleDeleteBook() {
        batchOperationsController.handleBatchDelete(this::handleRefresh);
    }

    @FXML
    public void handleAddBook() {
        importController.importFb2(this::handleRefresh);
    }
    // ==================== МЕТОДИ ДЛЯ РОБОТИ З READER ====================
    @FXML
    public void handleOpenNewReader() { bookCommandCoordinator.openInternal(); }

    @FXML
    public void handleDownloadBook() {
        batchOperationsController.handleBatchDownload(mainPane.getScene() == null ? null : mainPane.getScene().getWindow(), workspaceManager::refreshAfterStorageChange);
    }

    @FXML
    public void handleRemoveLocalCopy() {
        batchOperationsController.handleBatchRemoveLocal(this::handleRefresh);
    }

    @FXML
    public void handleCancelDownload() { bookCommandCoordinator.cancelDownload(); }


    @FXML
    public void handleCloseReader() {
        cleanupReader();
        showDashboard();
        dialogService.showInfo(localizationService.text("ui.main.reader_closed_title"), localizationService.text("ui.main.reader_closed_message"));
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

    @FXML public void handleUpdateCollectionManual() { importController.updateCollectionFromInpx(this::handleRefresh); }
    @FXML public void handleUpdateCollectionNetwork() { collectionUpdateUiService.updateFromNetwork(mainPane.getScene().getWindow(), this::handleRefresh); }
    @FXML public void handleCancelCollectionUpdate() { if(!collectionUpdateUiService.cancel()) dialogService.showInfo(localizationService.text("ui.main.update.title"), localizationService.text("ui.main.update.none")); }

    @FXML public void handleAttachCollection() {
        collectionAttachUiService.attach(mainPane.getScene().getWindow(),
                result -> collectionController.switchToCollection(result.collection(), this::showDashboard));
    }
    @FXML
    public void handleResetNavigation() {
        navigationPanelController.resetNavigation();
        dialogService.showInfo(localizationService.text("ui.main.navigation.title"), localizationService.text("ui.main.navigation.reset_message"));
    }
}
