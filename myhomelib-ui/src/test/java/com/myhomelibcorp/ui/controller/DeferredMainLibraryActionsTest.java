package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.ui.collection.CollectionWorkspaceController;
import com.myhomelibcorp.ui.service.BookListExportService;
import com.myhomelibcorp.ui.service.CollectionAttachUiService;
import com.myhomelibcorp.ui.service.CollectionCopyUiService;
import com.myhomelibcorp.ui.service.UserDataUiService;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeferredMainLibraryActionsTest {

    @Test
    void everyMainLibraryCommandDelegatesToItsOwnedLazyBoundary() {
        CollectionController collections = mock(CollectionController.class);
        GroupController groups = mock(GroupController.class);
        BatchOperationsController batches = mock(BatchOperationsController.class);
        ExportController exports = mock(ExportController.class);
        DatabaseToolsController database = mock(DatabaseToolsController.class);
        ImportController imports = mock(ImportController.class);
        CollectionWorkspaceController collectionWorkspace = mock(CollectionWorkspaceController.class);
        UserDataUiService userData = mock(UserDataUiService.class);
        BookListExportService bookList = mock(BookListExportService.class);
        CollectionCopyUiService copy = mock(CollectionCopyUiService.class);
        CollectionAttachUiService attach = mock(CollectionAttachUiService.class);

        DeferredMainLibraryActions actions = new DeferredMainLibraryActions(
                provider(collections), provider(groups), provider(batches), provider(exports), provider(database),
                provider(imports), provider(collectionWorkspace), provider(userData), provider(bookList), provider(copy), provider(attach));

        Stage stage = mock(Stage.class);
        Window window = mock(Window.class);
        BorderPane pane = mock(BorderPane.class);
        Runnable done = mock(Runnable.class);

        actions.newCollection(stage, done);
        actions.selectCollection(done);
        actions.addGroup(done);
        actions.editGroup(done);
        actions.deleteGroup(done);
        actions.clearGroup(done);
        actions.batchRate(done);
        actions.batchProgress(done);
        actions.batchMarkRead(done);
        actions.batchAddToGroup(done);
        actions.clearSelection();
        actions.batchDelete(done);
        actions.batchDownload(window, done);
        actions.batchRemoveLocal(done);
        actions.exportBooks(window, done);
        actions.exportInpx(pane, done);
        actions.importFb2(done);
        actions.importInpx(done);
        actions.importDirectory(done);
        actions.syncFolder(done);
        actions.updateCollectionFromInpx(done);
        actions.checkIntegrity(stage);
        actions.vacuum();
        actions.rebuildIndex();
        actions.backup(stage);
        actions.restore(stage);
        actions.statistics(stage);
        actions.exportUserData(window);
        actions.importUserData(window);
        actions.exportBookList(window, "csv");
        actions.copySelectedToCollection(window, done);
        actions.attachCollection(window, done);

        verify(collections).handleNewCollection(stage, done);
        verify(collections).handleSelectCollection(done);
        verify(groups).handleAddGroup(done);
        verify(groups).handleEditGroup(done);
        verify(groups).handleDeleteGroup(done);
        verify(groups).handleClearGroup(done);
        verify(batches).handleBatchRate(done);
        verify(batches).handleBatchProgress(done);
        verify(batches).handleBatchMarkRead(done);
        verify(batches).handleBatchAddToGroup(done);
        verify(batches).handleClearSelection();
        verify(batches).handleBatchDelete(done);
        verify(batches).handleBatchDownload(window, done);
        verify(batches).handleBatchRemoveLocal(done);
        verify(exports).handleExport(window, done);
        verify(exports).handleExportInpx(pane, done);
        verify(imports).importFb2(done);
        verify(imports).importInpx(done);
        verify(imports).importDirectory(done);
        verify(imports).handleSyncFolder(done);
        verify(imports).updateCollectionFromInpx(done);
        verify(database).handleCheckIntegrity(stage);
        verify(database).handleVacuum();
        verify(database).handleRebuildIndex();
        verify(database).handleBackup(stage);
        verify(database).handleRestore(stage);
        verify(database).handleStatistics(stage);
        verify(userData).exportData(window);
        verify(userData).importData(window);
        verify(bookList).export(window, "csv");
        verify(copy).copySelected(window, done);
        verify(attach).attach(eq(window), any());
    }

    @Test
    void collectionListUsesOptionalWorkspaceWithoutForcingCreation() {
        @SuppressWarnings("unchecked") ObjectProvider<CollectionWorkspaceController> workspaceProvider = mock(ObjectProvider.class);
        when(workspaceProvider.getIfAvailable()).thenReturn(null);
        DeferredMainLibraryActions actions = new DeferredMainLibraryActions(
                emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(),
                workspaceProvider, emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider());

        assertThat(actions.collectionListIfAvailable()).isNull();
        verify(workspaceProvider).getIfAvailable();
        verify(workspaceProvider, never()).getObject();
    }

    private static <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked") ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(value);
        return provider;
    }

    private static <T> ObjectProvider<T> emptyProvider() {
        @SuppressWarnings("unchecked") ObjectProvider<T> provider = mock(ObjectProvider.class);
        return provider;
    }
}
