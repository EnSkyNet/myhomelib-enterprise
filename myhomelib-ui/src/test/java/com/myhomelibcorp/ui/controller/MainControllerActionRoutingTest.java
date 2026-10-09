package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.ui.action.ActionRegistry;
import com.myhomelibcorp.ui.navigation.MainNavigationCoordinator;
import com.myhomelibcorp.ui.navigation.NavigationPanelController;
import com.myhomelibcorp.ui.navigation.WorkspaceManager;
import com.myhomelibcorp.ui.service.*;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MainControllerActionRoutingTest {
    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try { Platform.startup(started::countDown); }
        catch (IllegalStateException alreadyStarted) { started.countDown(); }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        Platform.setImplicitExit(false);
    }

    @Test
    void navigationHandlersRouteToExactCoordinatorOperations() throws Exception {
        Fixture f = fixture();
        invoke(f.controller, "onAllBooks"); verify(f.navigation).allBooks();
        invoke(f.controller, "onAuthors"); verify(f.navigation).authors();
        invoke(f.controller, "onSeries"); verify(f.navigation).series();
        invoke(f.controller, "onGenres"); verify(f.navigation).genres();
        invoke(f.controller, "onNewBooks"); verify(f.navigation).newBooks();
        invoke(f.controller, "onFollowedAuthors"); verify(f.navigation).followedAuthors();
        invoke(f.controller, "onAlreadyRead"); verify(f.navigation).alreadyRead();
        invoke(f.controller, "onHistory"); verify(f.navigation).history();
        invoke(f.controller, "onClearHistory"); verify(f.navigation).clearHistory(any());
        invoke(f.controller, "onCollections"); verify(f.navigation).collections();
    }

    @Test
    void readerAndBookCommandHandlersRouteToExactCoordinatorOperations() throws Exception {
        Fixture f = fixture();
        invoke(f.controller, "handleOpenNewReader"); verify(f.bookCommands).openInternal();
        invoke(f.controller, "handleOpenExternalReader"); verify(f.bookCommands).openExternal();
        invoke(f.controller, "handleCancelDownload"); verify(f.bookCommands).cancelDownload();
        invoke(f.controller, "handleEditMetadata"); verify(f.bookCommands).editMetadata(any(), any());
        invoke(f.controller, "handleLocalBatchMetadata"); verify(f.bookCommands).editLocalBatchMetadata(any(), any());
        invoke(f.controller, "handleUndoLastLibraryOperation"); verify(f.bookCommands).undoLastLibraryOperation(any(), any());
        invoke(f.controller, "handleEditCustomFieldValues"); verify(f.bookCommands).editCustomFieldValues(any());
        invoke(f.controller, "handleManageCustomFields"); verify(f.bookCommands).manageCustomFields(any());
    }

    @Test
    void collectionNavigationSearchAndViewHandlersHaveObservableEffects() throws Exception {
        Fixture f = fixture();
        invoke(f.controller, "handleUpdateCollectionNetwork");
        verify(f.collectionUpdate).updateFromNetwork(any(), any());

        when(f.collectionUpdate.cancel()).thenReturn(false);
        invoke(f.controller, "handleCancelCollectionUpdate");
        verify(f.dialogs).showInfo(anyString(), anyString());

        invoke(f.controller, "handleResetNavigation");
        verify(f.navigationPanel).resetNavigation();
        verify(f.dialogs, atLeastOnce()).showInfo(anyString(), anyString());

        fx(() -> { f.search.setText("needle"); return null; });
        invoke(f.controller, "handleSearch");
        verify(f.navigation).search("needle");
        invoke(f.controller, "handleClearSearch");
        String cleared = fx(() -> f.search.getText());
        assertThat(cleared).isEmpty();

        invoke(f.controller, "handleToggleView");
        verify(f.viewMode).toggleView();

        invoke(f.controller, "handleAbout");
        verify(f.dialogs, atLeastOnce()).showInfo(anyString(), anyString(), anyString());
    }


    @Test
    void optionalFeatureHandlersDelegateToExactFeatureBoundary() throws Exception {
        Fixture f = fixture();
        when(f.features.showCollectionProperties(any())).thenReturn(false);

        invoke(f.controller, "handleOpds");
        invoke(f.controller, "handleSettings");
        invoke(f.controller, "handleIntegrations");
        invoke(f.controller, "handleConvertBook");
        invoke(f.controller, "handleAiSummarizeBook");
        invoke(f.controller, "handleAiAskBook");
        invoke(f.controller, "handleCollectionProperties");
        invoke(f.controller, "handleOrganizeLibrary");
        invoke(f.controller, "handleDuplicates");
        invoke(f.controller, "handleCustomizeActions");
        invoke(f.controller, "handleBookActions");
        invoke(f.controller, "handleHelp");
        invoke(f.controller, "handleInpxHelp");

        verify(f.features).showOpds(any());
        verify(f.features).showSettings(any());
        verify(f.features).showIntegrations(any());
        verify(f.features).showBookConversion(any(), any(), anyList());
        verify(f.features).summarizeBook(any(), any());
        verify(f.features).askBook(any(), any());
        verify(f.features).showCollectionProperties(any());
        verify(f.features).showLibraryOrganizer(any());
        verify(f.features).showDuplicateReview(any());
        verify(f.features).showActionCustomization(any());
        verify(f.features).showBookActionProfiles(any());
        verify(f.features).showHelp(any(), anyString());
        verify(f.features).showHelp(any(), eq("inpx"));
    }

    @Test
    void libraryHandlersDelegateToExactLibraryBoundary() throws Exception {
        Fixture f = fixture();

        invoke(f.controller, "handleNewCollection");
        invoke(f.controller, "handleSelectCollection");
        invoke(f.controller, "handleAddGroup");
        invoke(f.controller, "handleEditGroup");
        invoke(f.controller, "handleDeleteGroup");
        invoke(f.controller, "handleBatchRate");
        invoke(f.controller, "handleBatchProgress");
        invoke(f.controller, "handleBatchMarkRead");
        invoke(f.controller, "handleBatchAddToGroup");
        invoke(f.controller, "handleClearSelection");
        invoke(f.controller, "handleExport");
        invoke(f.controller, "handleExportInpx");
        invoke(f.controller, "handleImportFb2");
        invoke(f.controller, "handleImportInpx");
        invoke(f.controller, "handleImportDirectory");
        invoke(f.controller, "handleSyncFolder");
        invoke(f.controller, "handleCheckIntegrity");
        invoke(f.controller, "handleVacuum");
        invoke(f.controller, "handleRebuildIndex");
        invoke(f.controller, "handleBackup");
        invoke(f.controller, "handleRestore");
        invoke(f.controller, "handleStatistics");
        invoke(f.controller, "handleDeleteBook");
        invoke(f.controller, "handleAddBook");
        invoke(f.controller, "handleDownloadBook");
        invoke(f.controller, "handleRemoveLocalCopy");
        invoke(f.controller, "handleExportUserData");
        invoke(f.controller, "handleImportUserData");
        invoke(f.controller, "handleExportListHtml");
        invoke(f.controller, "handleExportListTxt");
        invoke(f.controller, "handleExportListRtf");
        invoke(f.controller, "handleClearGroup");
        invoke(f.controller, "handleCopyToCollection");
        invoke(f.controller, "handleUpdateCollectionManual");
        invoke(f.controller, "handleAttachCollection");

        verify(f.libraries).newCollection(any(), any());
        verify(f.libraries).selectCollection(any());
        verify(f.libraries).addGroup(any());
        verify(f.libraries).editGroup(any());
        verify(f.libraries).deleteGroup(any());
        verify(f.libraries).batchRate(any());
        verify(f.libraries).batchProgress(any());
        verify(f.libraries).batchMarkRead(any());
        verify(f.libraries).batchAddToGroup(any());
        verify(f.libraries).clearSelection();
        verify(f.libraries).exportBooks(any(), any());
        verify(f.libraries).exportInpx(any(), any());
        verify(f.libraries, times(2)).importFb2(any());
        verify(f.libraries).importInpx(any());
        verify(f.libraries).importDirectory(any());
        verify(f.libraries).syncFolder(any());
        verify(f.libraries).checkIntegrity(any());
        verify(f.libraries).vacuum();
        verify(f.libraries).rebuildIndex();
        verify(f.libraries).backup(any());
        verify(f.libraries).restore(any());
        verify(f.libraries).statistics(any());
        verify(f.libraries).batchDelete(any());
        verify(f.libraries).batchDownload(any(), any());
        verify(f.libraries).batchRemoveLocal(any());
        verify(f.libraries).exportUserData(any());
        verify(f.libraries).importUserData(any());
        verify(f.libraries).exportBookList(any(), eq("html"));
        verify(f.libraries).exportBookList(any(), eq("txt"));
        verify(f.libraries).exportBookList(any(), eq("rtf"));
        verify(f.libraries).clearGroup(any());
        verify(f.libraries).copySelectedToCollection(any(), any());
        verify(f.libraries).updateCollectionFromInpx(any());
        verify(f.libraries).attachCollection(any(), any());
    }

    @Test
    void navigationViewAndReaderLifecycleHandlersHaveObservableEffects() throws Exception {
        Fixture f = fixture();
        when(f.layout.isLeftSidebarVisible()).thenReturn(false);
        when(f.layout.isRightSidebarVisible()).thenReturn(true);
        when(f.workspace.showColumnChooserForCurrentWorkspace()).thenReturn(true);

        invoke(f.controller, "handleBack");
        verify(f.workspace).goBack();
        invoke(f.controller, "handleForward");
        verify(f.workspace).goForward();
        invoke(f.controller, "handleHome");
        verify(f.navigation, atLeastOnce()).cleanupReader();
        verify(f.workspace, atLeastOnce()).showDashboard();
        invoke(f.controller, "onGroups");
        verify(f.workspace).showGroupWorkspace(any());
        invoke(f.controller, "onReadingLists");
        verify(f.workspace).showReadingListsWorkspace();
        invoke(f.controller, "onUpdates");
        verify(f.navigation).updates();
        invoke(f.controller, "onAnnotations");
        verify(f.navigation).annotations();
        invoke(f.controller, "onOperations");
        verify(f.workspace).showOperationCenterWorkspace();
        invoke(f.controller, "onImport");
        verify(f.navigation).importWorkspace();

        invoke(f.controller, "handleToggleLeftSidebar");
        verify(f.layout).setLeftSidebarVisible(true);
        invoke(f.controller, "handleToggleRightSidebar");
        verify(f.layout).setRightSidebarVisible(false);
        invoke(f.controller, "handleShowColumns");
        verify(f.workspace).showColumnChooserForCurrentWorkspace();

        invoke(f.controller, "handleCloseReader");
        verify(f.dialogs).showInfo(anyString(), anyString());
    }

    private static Fixture fixture() throws Exception {
        ApplicationState state = mock(ApplicationState.class);
        DialogService dialogs = mock(DialogService.class);
        MainBookCommandCoordinator bookCommands = mock(MainBookCommandCoordinator.class);
        MainNavigationCoordinator navigation = mock(MainNavigationCoordinator.class);
        WorkspaceManager workspace = mock(WorkspaceManager.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        FxmlLoaderFactory loaderFactory = mock(FxmlLoaderFactory.class);
        ActionRegistry actionRegistry = mock(ActionRegistry.class);
        DeferredMainFeatureActions features = mock(DeferredMainFeatureActions.class);
        DeferredMainLibraryActions libraries = mock(DeferredMainLibraryActions.class);
        MainLayoutService layout = mock(MainLayoutService.class);
        CatalogUpdateBadgeService badge = mock(CatalogUpdateBadgeService.class);
        ApplicationThemeService theme = mock(ApplicationThemeService.class);
        BookSelectionService selection = mock(BookSelectionService.class);
        ViewModeController viewMode = mock(ViewModeController.class);
        NavigationPanelController navigationPanel = mock(NavigationPanelController.class);
        CollectionUpdateUiService collectionUpdate = mock(CollectionUpdateUiService.class);
        LocalizationService localization = mock(LocalizationService.class);
        when(localization.text(anyString())).thenAnswer(i -> i.getArgument(0));
        when(localization.format(anyString(), any())).thenAnswer(i -> i.getArgument(0));

        MainController controller = new MainController(state, dialogs, bookCommands, navigation, workspace,
                events, loaderFactory, actionRegistry, features, libraries, layout, badge, theme, selection,
                viewMode, navigationPanel, collectionUpdate, localization);
        BorderPane mainPane = fx(BorderPane::new);
        fx(() -> { new Scene(mainPane); return null; });
        TextField search = fx(TextField::new);
        setField(controller, "mainPane", mainPane);
        setField(controller, "searchField", search);
        return new Fixture(controller, dialogs, bookCommands, navigation, workspace, features, libraries, layout,
                viewMode, navigationPanel, collectionUpdate, search);
    }

    private static void invoke(MainController target, String name) throws Exception {
        Method m = MainController.class.getDeclaredMethod(name);
        m.setAccessible(true);
        fx(() -> {
            try { m.invoke(target); }
            catch (ReflectiveOperationException e) { throw new RuntimeException(e.getCause() == null ? e : e.getCause()); }
            return null;
        });
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value);
    }

    private static <T> T fx(java.util.concurrent.Callable<T> action) throws Exception {
        if (Platform.isFxApplicationThread()) return action.call();
        AtomicReference<T> result = new AtomicReference<>(); AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> { try { result.set(action.call()); } catch (Throwable t) { error.set(t); } finally { done.countDown(); } });
        assertThat(done.await(8, TimeUnit.SECONDS)).isTrue();
        if (error.get() != null) {
            if (error.get() instanceof RuntimeException r) throw r;
            if (error.get() instanceof Error e) throw e;
            throw new RuntimeException(error.get());
        }
        return result.get();
    }

    private record Fixture(MainController controller, DialogService dialogs,
                           MainBookCommandCoordinator bookCommands, MainNavigationCoordinator navigation,
                           WorkspaceManager workspace, DeferredMainFeatureActions features,
                           DeferredMainLibraryActions libraries, MainLayoutService layout,
                           ViewModeController viewMode, NavigationPanelController navigationPanel,
                           CollectionUpdateUiService collectionUpdate, TextField search) {}
}
