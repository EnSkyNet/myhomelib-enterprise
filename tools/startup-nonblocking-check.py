#!/usr/bin/env python3
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
lifecycle=(ROOT/'myhomelib-application/src/main/java/com/myhomelibcorp/application/service/CollectionLifecycleService.java').read_text()
body=lifecycle.split('public boolean initializeCollection',1)[1].split('private static',1)[0]
assert 'repairTransientRemoteStorageRoots' not in body, 'catalog-wide root repair must not block startup'
assert 'seriesRepository.syncSeriesFromBooks()' not in body, 'catalog-wide series sync must not block startup'
coordinator=(ROOT/'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/BookDownloadCoordinator.java').read_text()
for token in ['normalizeLegacyRemoteRoot(book)', 'isTransientCatalogRoot', 'downloads']:
    assert token in coordinator, f'missing lazy remote-root normalization: {token}'
cred=(ROOT/'myhomelib-infrastructure/src/test/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteCollectionRepositoryCredentialsV7Test.java').read_text()
assert 'connection_script TEXT' in cred, 'credentials test schema must include connection_script'
print('startup-nonblocking-check: PASS')
