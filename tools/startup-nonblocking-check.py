#!/usr/bin/env python3
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
lifecycle=(ROOT/'myhomelib-application/src/main/java/com/myhomelibcorp/application/service/CollectionLifecycleService.java').read_text()
body=lifecycle.split('public boolean initializeCollection',1)[1].split('private static',1)[0]
assert 'repairTransientRemoteStorageRoots' not in body, 'catalog-wide root repair must not block startup'
assert 'seriesRepository.syncSeriesFromBooks()' not in body, 'catalog-wide series sync must not block startup'
coordinator=(ROOT/'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/BookDownloadCoordinator.java').read_text()
for token in ['normalizeLegacyRemoteStorage(book)', 'isTransientCatalogRoot', 'downloads']:
    assert token in coordinator, f'missing lazy remote-root normalization: {token}'
cred=(ROOT/'myhomelib-infrastructure/src/test/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteCollectionRepositoryCredentialsV7Test.java').read_text()
assert 'connection_script TEXT' in cred, 'credentials test schema must include connection_script'

bootstrap=(ROOT/'myhomelib-bootstrap/src/main/java/com/myhomelibcorp/MyHomeLibApp.java').read_text()
bootstrap_init=bootstrap.split('private CollectionManager initializeBackend()',1)[1].split('private void showMainWindow',1)[0]
assert 'statisticsService.refreshStatistics()' not in bootstrap_init, 'full statistics refresh must not block startup'
status=(ROOT/'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/statusbar/StatusBarController.java').read_text()
assert status.count('statisticsService.getStatistics()') == 1, 'status bar must read cached statistics once during initialize'
assert 'executor.submit(() -> statisticsService.getStatistics())' in status, 'status bar cache read must stay off the JavaFX thread'
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
assert 'PRAGMA busy_timeout=15000' in data_source and 'PRAGMA busy_timeout=15000' in metadata_source, 'all SQLite pools must tolerate sustained short writer contention'
book_workspace=(ROOT/'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/book/BookWorkspaceController.java').read_text()
assert 'saveLastOpenedBookId' not in book_workspace, 'opening book details must not create an unnecessary session DB write'
workspace=(ROOT/'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/navigation/WorkspaceManager.java').read_text()
reader_body=workspace.split('public void showNewReaderWorkspace(BookId bookId)',1)[1].split('private void openNewReaderWorkspaceLocal',1)[0]
assert 'ensureLocalForOpen(bookId)' in reader_body, 'Reader must use the single asynchronous guarded BookId entry point'
assert 'loadBookByIdUseCase' not in reader_body, 'Reader entry must not duplicate a synchronous DB load on the FX thread'

print('startup-nonblocking-check: PASS')
