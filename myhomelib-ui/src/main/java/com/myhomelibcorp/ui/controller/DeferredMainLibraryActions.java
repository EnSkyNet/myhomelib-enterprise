package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.dto.CollectionDto;
import com.myhomelibcorp.ui.collection.CollectionWorkspaceController;
import com.myhomelibcorp.ui.service.BookListExportService;
import com.myhomelibcorp.ui.service.CollectionAttachUiService;
import com.myhomelibcorp.ui.service.CollectionCopyUiService;
import com.myhomelibcorp.ui.service.UserDataUiService;
import javafx.collections.ObservableList;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Lazy command boundary for library-management operations reachable from the main window.
 * Heavy import/export/database/batch controllers are created only when the command is used.
 */
@Component
@RequiredArgsConstructor
public final class DeferredMainLibraryActions {
    private final ObjectProvider<CollectionController> collectionController;
    private final ObjectProvider<GroupController> groupController;
    private final ObjectProvider<BatchOperationsController> batchOperationsController;
    private final ObjectProvider<ExportController> exportController;
    private final ObjectProvider<DatabaseToolsController> databaseToolsController;
    private final ObjectProvider<ImportController> importController;
    private final ObjectProvider<CollectionWorkspaceController> collectionWorkspaceController;
    private final ObjectProvider<UserDataUiService> userDataUiService;
    private final ObjectProvider<BookListExportService> bookListExportService;
    private final ObjectProvider<CollectionCopyUiService> collectionCopyUiService;
    private final ObjectProvider<CollectionAttachUiService> collectionAttachUiService;

    public void newCollection(Stage owner, Runnable onComplete) {
        collectionController.getObject().handleNewCollection(owner, onComplete);
    }

    public void selectCollection(Runnable onComplete) {
        collectionController.getObject().handleSelectCollection(onComplete);
    }

    public ObservableList<CollectionDto> collectionListIfAvailable() {
        CollectionWorkspaceController controller = collectionWorkspaceController.getIfAvailable();
        return controller == null ? null : controller.getCollectionList();
    }

    public void addGroup(Runnable onComplete) {
        groupController.getObject().handleAddGroup(onComplete);
    }

    public void editGroup(Runnable onComplete) {
        groupController.getObject().handleEditGroup(onComplete);
    }

    public void deleteGroup(Runnable onComplete) {
        groupController.getObject().handleDeleteGroup(onComplete);
    }

    public void clearGroup(Runnable onComplete) {
        groupController.getObject().handleClearGroup(onComplete);
    }

    public void batchRate(Runnable onComplete) {
        batchOperationsController.getObject().handleBatchRate(onComplete);
    }

    public void batchProgress(Runnable onComplete) {
        batchOperationsController.getObject().handleBatchProgress(onComplete);
    }

    public void batchMarkRead(Runnable onComplete) {
        batchOperationsController.getObject().handleBatchMarkRead(onComplete);
    }

    public void batchAddToGroup(Runnable onComplete) {
        batchOperationsController.getObject().handleBatchAddToGroup(onComplete);
    }

    public void clearSelection() {
        batchOperationsController.getObject().handleClearSelection();
    }

    public void batchDelete(Runnable onComplete) {
        batchOperationsController.getObject().handleBatchDelete(onComplete);
    }

    public void batchDownload(Window owner, Runnable onComplete) {
        batchOperationsController.getObject().handleBatchDownload(owner, onComplete);
    }

    public void batchRemoveLocal(Runnable onComplete) {
        batchOperationsController.getObject().handleBatchRemoveLocal(onComplete);
    }

    public void exportBooks(Window owner, Runnable onSuccessfulExport) {
        exportController.getObject().handleExport(owner, onSuccessfulExport);
    }

    public void exportInpx(BorderPane owner, Runnable onComplete) {
        exportController.getObject().handleExportInpx(owner, onComplete);
    }

    public void importFb2(Runnable onComplete) {
        importController.getObject().importFb2(onComplete);
    }

    public void importInpx(Runnable onComplete) {
        importController.getObject().importInpx(onComplete);
    }

    public void importDirectory(Runnable onComplete) {
        importController.getObject().importDirectory(onComplete);
    }

    public void syncFolder(Runnable onComplete) {
        importController.getObject().handleSyncFolder(onComplete);
    }

    public void updateCollectionFromInpx(Runnable onComplete) {
        importController.getObject().updateCollectionFromInpx(onComplete);
    }

    public void checkIntegrity(Stage owner) {
        databaseToolsController.getObject().handleCheckIntegrity(owner);
    }

    public void vacuum() {
        databaseToolsController.getObject().handleVacuum();
    }

    public void rebuildIndex() {
        databaseToolsController.getObject().handleRebuildIndex();
    }

    public void backup(Stage owner) {
        databaseToolsController.getObject().handleBackup(owner);
    }

    public void restore(Stage owner) {
        databaseToolsController.getObject().handleRestore(owner);
    }

    public void statistics(Stage owner) {
        databaseToolsController.getObject().handleStatistics(owner);
    }

    public void exportUserData(Window owner) {
        userDataUiService.getObject().exportData(owner);
    }

    public void importUserData(Window owner) {
        userDataUiService.getObject().importData(owner);
    }

    public void exportBookList(Window owner, String format) {
        bookListExportService.getObject().export(owner, format);
    }

    public void copySelectedToCollection(Window owner, Runnable onComplete) {
        collectionCopyUiService.getObject().copySelected(owner, onComplete);
    }

    public void attachCollection(Window owner, Runnable onComplete) {
        collectionAttachUiService.getObject().attach(owner,
                result -> collectionController.getObject().switchToCollection(result.collection(), onComplete));
    }
}
