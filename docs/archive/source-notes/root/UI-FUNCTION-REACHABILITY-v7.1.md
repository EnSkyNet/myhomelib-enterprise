# UI Function Reachability — MyHomeLib Enterprise v7.1

Дата ревізії: 2026-08-30  
Статус: generated audit matrix; всі кандидати `UNREACHABLE/REVIEW` потребують ручної перевірки Spring/FXML/reflection перед видаленням.

## Висновок

- FXML event-handler references: **163**, усі перевіряються `tools/ui-function-reachability-check.py`.
- Application user-facing actions/checkers: **48**, кожен має прямий reference з UI/MCP/OPDS.
- Видалено `BackgroundTaskService`: bean створював окремий thread pool, але не мав жодного operational caller; bootstrap лише викликав `shutdown()`.
- `DefaultNavigationService` не є dead: він реалізує `NavigationService`, який інжектиться в UI-контролери; callback встановлюється через `@PostConstruct`.

## 1. Application actions/checkers → intended entry areas

| Use case | Entry area | Source |
|---|---|---|
| `LoadAuthorByIdUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/author/LoadAuthorByIdUseCase.java` |
| `UpdateAuthorDescriptionUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/author/UpdateAuthorDescriptionUseCase.java` |
| `LoadBookByIdUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/book/LoadBookByIdUseCase.java` |
| `LoadBooksByAuthorUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/book/LoadBooksByAuthorUseCase.java` |
| `LoadBooksUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/book/LoadBooksUseCase.java` |
| `MarkAsReadBatchUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/book/MarkAsReadBatchUseCase.java` |
| `ResolveBookContentUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/book/ResolveBookContentUseCase.java` |
| `RunBookActionUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/book/RunBookActionUseCase.java` |
| `UpdateProgressBatchUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/book/UpdateProgressBatchUseCase.java` |
| `UpdateRateBatchUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/book/UpdateRateBatchUseCase.java` |
| `AddBookToCollectionUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/AddBookToCollectionUseCase.java` |
| `AttachHlc2CollectionUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/AttachHlc2CollectionUseCase.java` |
| `CollectionAutoUpdateUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/CollectionAutoUpdateUseCase.java` |
| `CollectionMaintenanceUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/CollectionMaintenanceUseCase.java` |
| `CopyBooksBetweenCollectionsUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/CopyBooksBetweenCollectionsUseCase.java` |
| `CreateCollectionUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/CreateCollectionUseCase.java` |
| `DeleteCollectionUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/DeleteCollectionUseCase.java` |
| `IsBookInCollectionUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/IsBookInCollectionUseCase.java` |
| `LoadCollectionBooksUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/LoadCollectionBooksUseCase.java` |
| `LoadCollectionsUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/LoadCollectionsUseCase.java` |
| `RemoveBookFromCollectionUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/RemoveBookFromCollectionUseCase.java` |
| `RenameCollectionUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/RenameCollectionUseCase.java` |
| `SwitchCollectionUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/SwitchCollectionUseCase.java` |
| `UpdateCollectionFromNetworkUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/UpdateCollectionFromNetworkUseCase.java` |
| `UpdateCollectionPropertiesUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/UpdateCollectionPropertiesUseCase.java` |
| `LoadDashboardDataUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/dashboard/LoadDashboardDataUseCase.java` |
| `DownloadBookUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/download/DownloadBookUseCase.java` |
| `RemoveLocalBookCopyUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/download/RemoveLocalBookCopyUseCase.java` |
| `ExportToDeviceUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/export/ExportToDeviceUseCase.java` |
| `ExportToInpxUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/export/ExportToInpxUseCase.java` |
| `AddBookToGroupUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/group/AddBookToGroupUseCase.java` |
| `AddToGroupBatchUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/group/AddToGroupBatchUseCase.java` |
| `CreateGroupUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/group/CreateGroupUseCase.java` |
| `DeleteGroupUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/group/DeleteGroupUseCase.java` |
| `IsBookInGroupUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/group/IsBookInGroupUseCase.java` |
| `LoadBookGroupsUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/group/LoadBookGroupsUseCase.java` |
| `LoadGroupBooksUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/group/LoadGroupBooksUseCase.java` |
| `LoadGroupsUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/group/LoadGroupsUseCase.java` |
| `RemoveBookFromGroupUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/group/RemoveBookFromGroupUseCase.java` |
| `RenameGroupUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/group/RenameGroupUseCase.java` |
| `ImportDirectoryUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/imports/ImportDirectoryUseCase.java` |
| `ImportFileUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/imports/ImportFileUseCase.java` |
| `DataIntegrityChecker` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/integrity/DataIntegrityChecker.java` |
| `DeleteSavedSearchUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/search/DeleteSavedSearchUseCase.java` |
| `LoadSavedSearchesUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/search/LoadSavedSearchesUseCase.java` |
| `SaveSearchUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/search/SaveSearchUseCase.java` |
| `SyncSeriesUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/series/SyncSeriesUseCase.java` |
| `SyncFolderUseCase` | UI | `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/sync/SyncFolderUseCase.java` |

## 2. FXML controls → handlers

| FXML | Controller | Control | fx:id | Event | Handler | Status |
|---|---|---|---|---|---|---|
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `collectionsMenuItem` | `onAction` | `#onCollections` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleNewCollection` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleCollectionWizard` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleAttachCollection` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleCollectionProperties` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleUpdateCollectionManual` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleUpdateCollectionNetwork` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleCancelCollectionUpdate` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleExportUserData` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleImportUserData` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleCopyToCollection` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleResetNavigation` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleExit` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleAddGroup` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleEditGroup` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleDeleteGroup` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleClearGroup` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleEditMetadata` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleDeleteBook` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `openInternalMenuItem` | `onAction` | `#handleOpenNewReader` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `openExternalMenuItem` | `onAction` | `#handleOpenExternalReader` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleDownloadBook` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleRemoveLocalCopy` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleCancelDownload` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleCloseReader` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `refreshMenuItem` | `onAction` | `#handleRefresh` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleShowColumns` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#onNewBooks` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#onUpdates` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#onAlreadyRead` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#onHistory` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#onClearHistory` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleImportFb2` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `importInpxMenuItem` | `onAction` | `#handleImportInpx` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleImportDirectory` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `exportMenuItem` | `onAction` | `#handleExport` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleExportListHtml` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleExportListTxt` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleExportListRtf` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleCheckIntegrity` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleVacuum` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleRebuildIndex` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleBackup` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleRestore` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleStatistics` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleSyncFolder` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `settingsMenuItem` | `onAction` | `#handleSettings` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `bookActionsMenuItem` | `onAction` | `#handleBookActions` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `customizeActionsMenuItem` | `onAction` | `#handleCustomizeActions` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `opdsMenuItem` | `onAction` | `#handleOpds` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `helpMenuItem` | `onAction` | `#handleHelp` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleInpxHelp` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `MenuItem` | `—` | `onAction` | `#handleAbout` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `Button` | `backButton` | `onAction` | `#handleBack` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `Button` | `forwardButton` | `onAction` | `#handleForward` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `Button` | `—` | `onAction` | `#handleHome` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `Button` | `—` | `onAction` | `#handleSearch` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `Button` | `—` | `onAction` | `#handleImportDirectory` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `Button` | `—` | `onAction` | `#handleAddBook` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `Button` | `—` | `onAction` | `#handleBatchRate` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `Button` | `—` | `onAction` | `#handleBatchMarkRead` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `Button` | `—` | `onAction` | `#handleBatchAddToGroup` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `Button` | `—` | `onAction` | `#handleClearSelection` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `Button` | `—` | `onAction` | `#handleToggleView` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `Button` | `—` | `onAction` | `#handleExport` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `Button` | `—` | `onAction` | `#handleExportInpx` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `Button` | `—` | `onAction` | `#handleBackup` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `Button` | `—` | `onAction` | `#handleDownloadBook` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `Button` | `—` | `onAction` | `#handleRemoveLocalCopy` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `Button` | `—` | `onAction` | `#handleCancelDownload` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `Button` | `—` | `onAction` | `#handleOpenNewReader` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/MainView.fxml` | `MainController` | `Button` | `—` | `onAction` | `#handleSettings` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/author-workspace.fxml` | `AuthorWorkspaceController` | `Button` | `—` | `onAction` | `#onEditAuthorDescription` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/author-workspace.fxml` | `AuthorWorkspaceController` | `Button` | `—` | `onAction` | `#onSortByTitle` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/author-workspace.fxml` | `AuthorWorkspaceController` | `Button` | `—` | `onAction` | `#onSortByYear` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/author-workspace.fxml` | `AuthorWorkspaceController` | `Button` | `—` | `onAction` | `#onSortByRating` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/author-workspace.fxml` | `AuthorWorkspaceController` | `Button` | `—` | `onAction` | `#onOpenBook` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/author-workspace.fxml` | `AuthorWorkspaceController` | `Button` | `—` | `onAction` | `#onReadBook` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/backup-dialog.fxml` | `BackupController` | `Button` | `selectPathButton` | `onAction` | `#onSelectPath` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/backup-dialog.fxml` | `BackupController` | `Button` | `backupButton` | `onAction` | `#onBackup` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/backup-dialog.fxml` | `BackupController` | `Button` | `cancelButton` | `onAction` | `#onCancel` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/backup-dialog.fxml` | `BackupController` | `Button` | `—` | `onAction` | `#onClose` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/book-table.fxml` | `BookTableController` | `Button` | `—` | `onAction` | `#openGlobalFilters` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/book-table.fxml` | `BookTableController` | `Button` | `—` | `onAction` | `#applyQuickFilter` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/book-table.fxml` | `BookTableController` | `Button` | `—` | `onAction` | `#clearQuickFilter` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/book-table.fxml` | `BookTableController` | `Button` | `—` | `onAction` | `#showColumnChooser` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/book-table.fxml` | `BookTableController` | `Button` | `—` | `onAction` | `#resetTableProfile` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/book-workspace.fxml` | `BookWorkspaceController` | `Button` | `—` | `onAction` | `#onOpen` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/book-workspace.fxml` | `BookWorkspaceController` | `Button` | `—` | `onAction` | `#onRead` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/book-workspace.fxml` | `BookWorkspaceController` | `Button` | `—` | `onAction` | `#onEdit` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/book-workspace.fxml` | `BookWorkspaceController` | `Button` | `—` | `onAction` | `#onOpenFolder` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/book-workspace.fxml` | `BookWorkspaceController` | `Button` | `—` | `onAction` | `#onAddToCollection` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/book-workspace.fxml` | `BookWorkspaceController` | `Button` | `—` | `onAction` | `#onDeleteBook` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/book-workspace.fxml` | `BookWorkspaceController` | `Button` | `—` | `onAction` | `#onBack` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/collection-wizard.fxml` | `CollectionWizardController` | `Button` | `—` | `onAction` | `#onSelectRootFolder` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/collection-wizard.fxml` | `CollectionWizardController` | `Button` | `—` | `onAction` | `#onSelectDbPath` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/collection-wizard.fxml` | `CollectionWizardController` | `Button` | `—` | `onAction` | `#onSelectSourcePath` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/collection-wizard.fxml` | `CollectionWizardController` | `Button` | `backButton` | `onAction` | `#onBack` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/collection-wizard.fxml` | `CollectionWizardController` | `Button` | `nextButton` | `onAction` | `#onNext` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/collection-wizard.fxml` | `CollectionWizardController` | `Button` | `finishButton` | `onAction` | `#onFinish` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/collection-wizard.fxml` | `CollectionWizardController` | `Button` | `—` | `onAction` | `#onCancel` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/collection-workspace.fxml` | `CollectionWorkspaceController` | `Button` | `—` | `onAction` | `#onCreateCollection` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/collection-workspace.fxml` | `CollectionWorkspaceController` | `Button` | `activateButton` | `onAction` | `#onActivateCollection` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/collection-workspace.fxml` | `CollectionWorkspaceController` | `Button` | `renameButton` | `onAction` | `#onRenameCollection` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/collection-workspace.fxml` | `CollectionWorkspaceController` | `Button` | `deleteButton` | `onAction` | `#onDeleteCollection` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/collection-workspace.fxml` | `CollectionWorkspaceController` | `Button` | `—` | `onAction` | `#onRefresh` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/collection-workspace.fxml` | `CollectionWorkspaceController` | `Button` | `addBookButton` | `onAction` | `#onAddBookToCollection` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/collection-workspace.fxml` | `CollectionWorkspaceController` | `Button` | `removeBookButton` | `onAction` | `#onRemoveBookFromCollection` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/collection-workspace.fxml` | `CollectionWorkspaceController` | `Button` | `—` | `onAction` | `#onBrowseSource` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/collection-workspace.fxml` | `CollectionWorkspaceController` | `Button` | `—` | `onAction` | `#onSaveSourceMonitor` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/collection-workspace.fxml` | `CollectionWorkspaceController` | `Button` | `sourceCheckButton` | `onAction` | `#onCheckSourceNow` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/collection-workspace.fxml` | `CollectionWorkspaceController` | `Button` | `maintenanceAnalyzeButton` | `onAction` | `#onAnalyzeMaintenance` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/collection-workspace.fxml` | `CollectionWorkspaceController` | `Button` | `maintenanceDryRunButton` | `onAction` | `#onDryRunMaintenance` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/collection-workspace.fxml` | `CollectionWorkspaceController` | `Button` | `maintenanceApplyButton` | `onAction` | `#onApplyMaintenance` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/dashboard.fxml` | `DashboardController` | `Button` | `—` | `onAction` | `#onContinueReading` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/details.fxml` | `BookDetailsController` | `Button` | `showImagesButton` | `onAction` | `#onShowImages` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/details.fxml` | `BookDetailsController` | `Button` | `—` | `onAction` | `#onRead` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/details.fxml` | `BookDetailsController` | `Button` | `—` | `onAction` | `#onEdit` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/details.fxml` | `BookDetailsController` | `Button` | `—` | `onAction` | `#onOpenFolder` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/export-dialog.fxml` | `ExportController` | `Button` | `—` | `onAction` | `#onSaveProfileAs` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/export-dialog.fxml` | `ExportController` | `Button` | `updateProfileButton` | `onAction` | `#onUpdateProfile` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/export-dialog.fxml` | `ExportController` | `Button` | `deleteProfileButton` | `onAction` | `#onDeleteProfile` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/export-dialog.fxml` | `ExportController` | `Button` | `—` | `onAction` | `#onChooseDestination` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/export-dialog.fxml` | `ExportController` | `Button` | `—` | `onAction` | `#onShowHistory` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/export-dialog.fxml` | `ExportController` | `Button` | `exportButton` | `onAction` | `#onExport` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/export-dialog.fxml` | `ExportController` | `Button` | `cancelButton` | `onAction` | `#onCancel` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/groups-workspace.fxml` | `GroupWorkspaceController` | `Button` | `—` | `onAction` | `#onCreateGroup` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/groups-workspace.fxml` | `GroupWorkspaceController` | `Button` | `—` | `onAction` | `#onRenameGroup` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/groups-workspace.fxml` | `GroupWorkspaceController` | `Button` | `—` | `onAction` | `#onDeleteGroup` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/groups-workspace.fxml` | `GroupWorkspaceController` | `Button` | `—` | `onAction` | `#onRefresh` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/groups-workspace.fxml` | `GroupWorkspaceController` | `Button` | `addBookButton` | `onAction` | `#onAddBookToGroup` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/groups-workspace.fxml` | `GroupWorkspaceController` | `Button` | `removeBookButton` | `onAction` | `#onRemoveBookFromGroup` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/import-workspace.fxml` | `ImportWorkspaceController` | `Button` | `—` | `onAction` | `#onChooseDirectory` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/import-workspace.fxml` | `ImportWorkspaceController` | `Button` | `—` | `onAction` | `#onChooseFile` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/import-workspace.fxml` | `ImportWorkspaceController` | `Button` | `—` | `onAction` | `#onImportDirectory` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/import-workspace.fxml` | `ImportWorkspaceController` | `Button` | `—` | `onAction` | `#onImportFile` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/import-workspace.fxml` | `ImportWorkspaceController` | `Button` | `cancelButton` | `onAction` | `#onCancel` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/import-workspace.fxml` | `ImportWorkspaceController` | `Button` | `—` | `onAction` | `#onSettings` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/integrity-check.fxml` | `IntegrityCheckController` | `Button` | `checkButton` | `onAction` | `#onCheckIntegrity` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/integrity-check.fxml` | `IntegrityCheckController` | `Button` | `fixButton` | `onAction` | `#onFixIssues` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/integrity-check.fxml` | `IntegrityCheckController` | `Button` | `—` | `onAction` | `#closeDialog` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/restore-dialog.fxml` | `RestoreController` | `Button` | `selectPathButton` | `onAction` | `#onSelectPath` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/restore-dialog.fxml` | `RestoreController` | `Button` | `restoreButton` | `onAction` | `#onRestore` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/restore-dialog.fxml` | `RestoreController` | `Button` | `cancelButton` | `onAction` | `#onCancel` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/restore-dialog.fxml` | `RestoreController` | `Button` | `closeButton` | `onAction` | `#onClose` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/saved-searches.fxml` | `SavedSearchesController` | `Button` | `—` | `onAction` | `#onSaveSearch` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/search-dialog.fxml` | `SearchDialogController` | `Button` | `searchButton` | `onAction` | `#performSearch` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/search-dialog.fxml` | `SearchDialogController` | `Button` | `—` | `onAction` | `#onClose` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/search-workspace.fxml` | `SearchWorkspaceController` | `TextField` | `searchField` | `onAction` | `#onSearch` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/search-workspace.fxml` | `SearchWorkspaceController` | `Button` | `—` | `onAction` | `#onSearch` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/search-workspace.fxml` | `SearchWorkspaceController` | `Button` | `—` | `onAction` | `#onClear` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/search-workspace.fxml` | `SearchWorkspaceController` | `Button` | `—` | `onAction` | `#onGlobalFilters` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/statistics.fxml` | `StatisticsController` | `Button` | `—` | `onAction` | `#onRefresh` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/statistics.fxml` | `StatisticsController` | `Button` | `—` | `onAction` | `#onClose` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/toc-dialog.fxml` | `TOCDialogController` | `Button` | `—` | `onAction` | `#onClose` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/tree-book-table.fxml` | `TreeBookTableController` | `Button` | `—` | `onAction` | `#selectAll` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/tree-book-table.fxml` | `TreeBookTableController` | `Button` | `—` | `onAction` | `#deselectAll` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/tree-book-table.fxml` | `TreeBookTableController` | `Button` | `—` | `onAction` | `#exportSelected` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/tree-book-table.fxml` | `TreeBookTableController` | `Button` | `—` | `onAction` | `#markSelectedAsRead` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/updates-workspace.fxml` | `UpdatesWorkspaceController` | `Button` | `refreshButton` | `onAction` | `#refresh` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/updates-workspace.fxml` | `UpdatesWorkspaceController` | `Button` | `openAuthorButton` | `onAction` | `#openAuthor` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/updates-workspace.fxml` | `UpdatesWorkspaceController` | `Button` | `openBookButton` | `onAction` | `#openBook` | REACHABLE |
| `myhomelib-ui/src/main/resources/view/updates-workspace.fxml` | `UpdatesWorkspaceController` | `Button` | `downloadButton` | `onAction` | `#downloadSelected` | REACHABLE |

## 3. Programmatic event bindings (Java)

Нижче — прямі `setOn...` bindings, які не видно через FXML. Це статичний inventory; lambdas/method references можуть вести до окремих services/actions.

| Source | Line | Target | Event | Binding |
|---|---:|---|---|---|
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/navigation/MainNavigationCoordinator.java` | 102 | `menuItem` | `setOnAction` | `event -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/navigation/AlphabetToolbarController.java` | 118 | `btn` | `setOnAction` | `e -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/collection/CollectionWorkspaceController.java` | 126 | `activateItem` | `setOnAction` | `e -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/collection/CollectionWorkspaceController.java` | 135 | `renameItem` | `setOnAction` | `e -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/collection/CollectionWorkspaceController.java` | 144 | `deleteItem` | `setOnAction` | `e -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/collection/CollectionWorkspaceController.java` | 153 | `refreshItem` | `setOnAction` | `e -> loadCollections())` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/collection/CollectionWorkspaceController.java` | 157 | `copyIdItem` | `setOnAction` | `e -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/collection/CollectionWorkspaceController.java` | 202 | `collectionsListView` | `setOnMouseClicked` | `event -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/collection/CollectionWorkspaceController.java` | 211 | `booksTableView` | `setOnMouseClicked` | `event -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/table/TreeBookTableController.java` | 183 | `treeTableView` | `setOnMouseClicked` | `event -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/table/BookTableController.java` | 157 | `bookTableView` | `setOnMouseClicked` | `event -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/table/BookTableController.java` | 206 | `quickFilterValueField` | `setOnAction` | `e -> applyQuickFilter())` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/table/BookTableController.java` | 213 | `pageSizeComboBox` | `setOnAction` | `e -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/table/BookTableController.java` | 217 | `prevPageButton` | `setOnAction` | `e -> bookLoaderService.previousPage())` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/table/BookTableController.java` | 218 | `nextPageButton` | `setOnAction` | `e -> bookLoaderService.nextPage())` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/details/BookDetailsController.java` | 166 | `publisherLink` | `setOnAction` | `event -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/details/BookDetailsController.java` | 233 | `link` | `setOnAction` | `event -> navigationService.navigateToAuthor(AuthorId.fromString(author.getId())))` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/details/BookDetailsController.java` | 247 | `link` | `setOnAction` | `event -> navigationService.navigateToSeriesByName(series))` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/details/BookDetailsController.java` | 258 | `link` | `setOnAction` | `event -> navigationService.navigateToGenre(GenreId.fromCode(genre.getCode())))` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/details/BookDetailsController.java` | 274 | `link` | `setOnAction` | `event -> navigationService.navigateToKeyword(keyword))` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/details/BookDetailsController.java` | 286 | `link` | `setOnAction` | `event -> navigationService.navigateToGroup(GroupId.fromLong(group.getId())))` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/details/BookDetailsController.java` | 305 | `link` | `setOnAction` | `event -> navigationService.navigateToReviews(filter))` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/search/SearchWorkspaceController.java` | 129 | `saveSearchButton` | `setOnAction` | `e -> onSaveSearch())` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/search/SearchWorkspaceController.java` | 130 | `savedSearchesButton` | `setOnAction` | `e -> onOpenSavedSearches())` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/search/SearchWorkspaceController.java` | 160 | `authorsListView` | `setOnMouseClicked` | `e -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/search/SearchWorkspaceController.java` | 173 | `seriesListView` | `setOnMouseClicked` | `e -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/search/SearchWorkspaceController.java` | 186 | `genresListView` | `setOnMouseClicked` | `e -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/search/SearchWorkspaceController.java` | 202 | `booksListView` | `setOnMouseClicked` | `e -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/ReaderSettingsDialog.java` | 51 | `applyPreset` | `setOnAction` | `e -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/ReaderSettingsDialog.java` | 124 | `reset` | `setOnAction` | `e -> c.resetTypography(ReaderSettings.defaultSettings()))` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/ReaderSettingsDialog.java` | 135 | `reset` | `setOnAction` | `e -> c.resetColors(ReaderSettings.defaultSettings()))` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/ReaderSettingsDialog.java` | 148 | `reset` | `setOnAction` | `e -> c.resetLayout(ReaderSettings.defaultSettings()))` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/ReaderSettingsDialog.java` | 173 | `reset` | `setOnAction` | `e -> c.resetNavigation(ReaderSettings.defaultSettings()))` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/ReaderSettingsDialog.java` | 201 | `reset` | `setOnAction` | `e -> c.resetStatus(ReaderSettings.defaultSettings()))` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/ReaderSettingsDialog.java` | 278 | `theme` | `setOnAction` | `e->{ ReaderTheme t=ReaderTheme.fromName(theme.getValue())` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/TOCDialogController.java` | 43 | `tocListView` | `setOnMouseClicked` | `e -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/SearchDialogController.java` | 44 | `searchField` | `setOnAction` | `e -> performSearch())` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/SearchDialogController.java` | 45 | `searchButton` | `setOnAction` | `e -> performSearch())` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/SearchDialogController.java` | 63 | `resultsListView` | `setOnMouseClicked` | `e -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/imports/ImportProgressDialog.java` | 87 | `scene` | `setOnKeyPressed` | `event -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/SavedSearchesController.java` | 53 | `savedSearchesListView` | `setOnMouseClicked` | `event -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/SavedSearchesController.java` | 65 | `loadItem` | `setOnAction` | `e -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/SavedSearchesController.java` | 73 | `deleteItem` | `setOnAction` | `e -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/MainController.java` | 122 | `searchField` | `setOnAction` | `event -> handleSearch())` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/MainController.java` | 150 | `item` | `setOnAction` | `e -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/ApplicationSettingsDialog.java` | 98 | `languageDiagnostics` | `setOnAction` | `e -> showLanguageDiagnostics())` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/ApplicationSettingsDialog.java` | 100 | `diagnostics` | `setOnAction` | `e -> createSupportBundle(diagnostics.getScene() == null ? null : diagnostics.getScene().getWindow()))` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/ApplicationSettingsDialog.java` | 185 | `test` | `setOnAction` | `e -> testCommand(field.getText(), placeholders))` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/CatalogUpdateProgressDialog.java` | 83 | `cancelButton` | `setOnAction` | `e -> requestCancel())` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/CollectionPropertiesUiService.java` | 41 | `browse` | `setOnAction` | `e->{DirectoryChooser dc=new DirectoryChooser()` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/action/BookActionProfilesDialog.java` | 128 | `addProfile` | `setOnAction` | `e -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/action/BookActionProfilesDialog.java` | 133 | `removeProfile` | `setOnAction` | `e -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/action/BookActionProfilesDialog.java` | 137 | `addCommand` | `setOnAction` | `e -> editCommand(owner, null).ifPresent(c -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/action/BookActionProfilesDialog.java` | 142 | `editCommand` | `setOnAction` | `e -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/action/BookActionProfilesDialog.java` | 149 | `removeCommand` | `setOnAction` | `e -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/action/BookActionProfilesDialog.java` | 154 | `upCommand` | `setOnAction` | `e -> move(commands, -1, updateControls))` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/action/BookActionProfilesDialog.java` | 155 | `downCommand` | `setOnAction` | `e -> move(commands, 1, updateControls))` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/action/BookActionProfilesDialog.java` | 156 | `preview` | `setOnAction` | `e -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/action/BookActionProfilesDialog.java` | 186 | `chooseExe` | `setOnAction` | `e -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/action/BookActionProfilesDialog.java` | 191 | `chooseDir` | `setOnAction` | `e -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/action/BookActionUiService.java` | 39 | `item` | `setOnAction` | `e -> run(book, profile))` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/action/ActionRegistry.java` | 37 | `menuItem` | `setOnAction` | `e -> execute(definition.id()))` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/author/AuthorWorkspaceController.java` | 123 | `booksTableView` | `setOnMouseClicked` | `event -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/updates/UpdatesWorkspaceController.java` | 72 | `updatesTree` | `setOnMouseClicked` | `event -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/dashboard/DashboardController.java` | 85 | `label` | `setOnMouseClicked` | `e -> navigationService.navigateToBook(BookId.fromString(book.getId())))` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/group/GroupWorkspaceController.java` | 83 | `booksTableView` | `setOnMouseClicked` | `event -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/group/GroupWorkspaceController.java` | 107 | `selectItem` | `setOnAction` | `e -> groupsListView.getSelectionModel().select(group))` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/group/GroupWorkspaceController.java` | 110 | `renameItem` | `setOnAction` | `e -> renameGroup(group))` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/group/GroupWorkspaceController.java` | 113 | `deleteItem` | `setOnAction` | `e -> deleteGroup(group))` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/opds/OpdsUiService.java` | 78 | `save` | `setOnAction` | `e -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/opds/OpdsUiService.java` | 88 | `start` | `setOnAction` | `e -> {` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/opds/OpdsUiService.java` | 101 | `stop` | `setOnAction` | `e -> { serverControl.stop()` |
| `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/opds/OpdsUiService.java` | 102 | `refresh` | `setOnAction` | `e -> refreshStatus.run())` |

## 4. UI service reachability

| Service/class | Status | Direct class refs | Interface refs | Implements |
|---|---|---:|---:|---|
| `ApplicationSettingsDialog` | REACHABLE | 1 | 0 | `—` |
| `BookDownloadCoordinator` | REACHABLE | 3 | 0 | `—` |
| `BookListExportService` | REACHABLE | 1 | 0 | `—` |
| `BookLoaderService` | REACHABLE | 4 | 0 | `—` |
| `CatalogUpdateProgressDialog` | REACHABLE | 1 | 0 | `—` |
| `ClassicLibraryActionsService` | REACHABLE | 2 | 0 | `—` |
| `CollectionAttachUiService` | REACHABLE | 1 | 0 | `—` |
| `CollectionCopyUiService` | REACHABLE | 1 | 0 | `—` |
| `CollectionPropertiesUiService` | REACHABLE | 1 | 0 | `—` |
| `CollectionUpdateUiService` | REACHABLE | 1 | 0 | `—` |
| `DefaultNavigationService` | REACHABLE | 0 | 12 | `NavigationService` |
| `DialogService` | REACHABLE | 32 | 0 | `—` |
| `ExternalBookLauncher` | REACHABLE | 1 | 0 | `—` |
| `FileChooserService` | REACHABLE | 5 | 0 | `—` |
| `FxmlLoaderFactory` | REACHABLE | 1 | 0 | `—` |
| `HelpService` | REACHABLE | 1 | 0 | `—` |
| `HelpTopicRegistry` | REACHABLE | 2 | 0 | `—` |
| `LanguageCatalogService` | REACHABLE | 1 | 0 | `—` |
| `LocalizationService` | REACHABLE | 12 | 0 | `—` |
| `MainBookCommandCoordinator` | REACHABLE | 1 | 0 | `—` |
| `NavigationHistoryService` | REACHABLE | 1 | 0 | `—` |
| `NavigationService` | REACHABLE | 12 | 0 | `—` |
| `SupportBundleService` | REACHABLE | 1 | 0 | `—` |
| `UiBackgroundExecutor` | REACHABLE | 12 | 0 | `—` |
| `UserDataUiService` | REACHABLE | 1 | 0 | `—` |

## 5. Guard / policy

- FXML handler without method → release blocker.
- Use case without direct UI/MCP/OPDS entry → manual review blocker; do not delete solely by text count.
- Spring `@Component/@Service/@Configuration/@Bean`, FXML, ServiceLoader and reflection wiring must be checked separately.
- Control that only mutates label/state without business behavior is not considered reachable merely because a handler exists.
- Heavy operations must not execute on JavaFX thread; programmatic bindings remain subject to async/orchestration review.
