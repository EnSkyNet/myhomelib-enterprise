#!/usr/bin/env python3
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
lifecycle=(ROOT/'myhomelib-application/src/main/java/com/myhomelibcorp/application/service/CollectionLifecycleService.java').read_text()
body=lifecycle.split('public boolean initializeCollection',1)[1].split('private static',1)[0]
assert 'repairTransientRemoteStorageRoots' not in body, 'catalog-wide root repair must not block startup'
assert 'seriesRepository.syncSeriesFromBooks()' not in body, 'catalog-wide series sync must not block startup'
coordinator=(ROOT/'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/BookDownloadCoordinator.java').read_text()
for token in ['normalizeLegacyRemoteStorage(book, collection)', 'isTransientCatalogRoot', 'downloads']:
    assert token in coordinator, f'missing lazy remote-root normalization: {token}'
cred=(ROOT/'myhomelib-infrastructure/src/test/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteCollectionRepositoryCredentialsV7Test.java').read_text()
assert 'connection_script TEXT' in cred, 'credentials test schema must include connection_script'

bootstrap=(ROOT/'myhomelib-bootstrap/src/main/java/com/myhomelibcorp/MyHomeLibApp.java').read_text()
migration_task=(ROOT/'myhomelib-bootstrap/src/main/java/com/myhomelibcorp/startup/MigrationStartupTask.java').read_text()
assert 'new Thread(() ->' in bootstrap and '"myhomelib-startup"' in bootstrap and 'startupThread.start();' in bootstrap, 'startup orchestration must stay off the JavaFX thread'
assert 'statisticsService.refreshStatistics()' not in bootstrap and 'statisticsService.refreshStatistics()' not in migration_task, 'full statistics refresh must not block startup'
status=(ROOT/'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/statusbar/StatusBarController.java').read_text()
assert status.count('statisticsService::getStatistics') == 1, 'status bar must read cached statistics once during initialize'
assert 'executor.submitCollectionScoped(LibraryOperationType.QUERY, statisticsService::getStatistics)' in status, 'status bar cache read must stay off the JavaFX thread and under the collection QUERY barrier'
stats=(ROOT/'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteStatisticsRepository.java').read_text()
get_body=stats.split('public LibraryStatistics getStatistics()',1)[1].split('public void invalidate()',1)[0]
assert 'refreshStatistics();' not in get_body, 'cached statistics read must not trigger a catalog-wide refresh'
inv_body=stats.split('public void invalidate()',1)[1].split('public void refreshStatistics()',1)[0]
assert 'DELETE FROM library_statistics' not in inv_body, 'invalidating statistics must preserve the last O(1) startup snapshot'

session_repo=(ROOT/'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteSessionRepository.java').read_text()
assert 'prefs.put(prefKey, bookId);' in session_repo, 'session save must write the non-SQLite fallback before DB I/O'
assert 'catch (Exception e)' in session_repo, 'session DB failures must be contained'
data_source=(ROOT/'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/config/DataSourceConfig.java').read_text()
metadata_source=(ROOT/'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/config/MetadataDatabaseConfig.java').read_text()
pragma_support=(ROOT/'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/config/SqliteConnectionPragmas.java').read_text()
assert 'SqliteConnectionPragmas.applyInteractive(config, 15_000' in data_source, 'collection SQLite pool must use the centralized 15s busy timeout'
assert 'SqliteConnectionPragmas.applyBase(config, 15_000)' in metadata_source, 'metadata SQLite pool must use the centralized 15s busy timeout'
assert 'sqlite.setBusyTimeout(busyTimeoutMs)' in pragma_support, 'central SQLite pragma boundary must actually apply busy_timeout'
book_workspace=(ROOT/'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/book/BookWorkspaceController.java').read_text()
assert 'saveLastOpenedBookId' not in book_workspace, 'opening book details must not create an unnecessary session DB write'
workspace=(ROOT/'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/navigation/WorkspaceManager.java').read_text()
reader_body=workspace.split('public void showNewReaderWorkspace(BookId bookId)',1)[1].split('private void openNewReaderWorkspaceLocal',1)[0]
assert 'ensureLocalForOpen(bookId)' in reader_body, 'Reader must use the single asynchronous guarded BookId entry point'
assert 'loadBookByIdUseCase' not in reader_body, 'Reader entry must not duplicate a synchronous DB load on the FX thread'


update_ui=(ROOT/'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/CollectionUpdateUiService.java').read_text()
assert 'settings.getBoolean("online.autoUpdateOnStartup", false)' in update_ui, 'online catalog update must be opt-in so first startup cannot silently create a multi-GB recovery checkpoint'
settings_dialog=(ROOT/'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/ApplicationSettingsDialog.java').read_text()
assert '"online.autoUpdateOnStartup"' in settings_dialog and 'ui.settings.online.auto_update_on_startup' in settings_dialog, 'startup online-update option must be visible and localized'
backup_adapter=(ROOT/'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/adapter/CollectionBackupAdapter.java').read_text()
recovery_body=backup_adapter.split('public void createRecoveryCheckpoint',1)[1].split('private void createSnapshot',1)[0]
assert 'createSnapshot(collection, targetFile, false)' in recovery_body, 'recovery checkpoint must use the fast validation path'
assert 'validateRecoveryCheckpoint(staged)' in backup_adapter, 'fast recovery checkpoint validation missing'

print('startup-nonblocking-check: PASS')
