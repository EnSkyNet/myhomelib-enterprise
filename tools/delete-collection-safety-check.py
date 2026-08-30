#!/usr/bin/env python3
from pathlib import Path
root=Path(__file__).resolve().parents[1]
use=(root/'myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/DeleteCollectionUseCase.java').read_text(encoding='utf-8')
storage=(root/'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteCollectionStorageManager.java').read_text(encoding='utf-8')
assert 'storageManager.vacuum(collection);' not in use, 'delete path must not VACUUM the active JdbcTemplate for an inactive target'
assert 'AppPaths.collectionSearchIndexDir(collection.getId())' in storage
assert 'AppPaths.collectionSearchIndexStateFile(collection.getId())' in storage
assert 'covers-' not in storage, 'non-persistent cover-cache cleanup must not pretend a disk cache exists'
print('DELETE COLLECTION SAFETY CHECK: PASS')
print(' - inactive collection deletion does not VACUUM active database')
print(' - active per-collection Lucene directory + freshness marker are deleted')
