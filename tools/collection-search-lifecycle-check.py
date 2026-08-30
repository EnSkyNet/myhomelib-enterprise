#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def text(rel):
    return (ROOT / rel).read_text(encoding='utf-8')

lifecycle_port = text('myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/search/SearchIndexLifecycle.java')
lifecycle = text('myhomelib-application/src/main/java/com/myhomelibcorp/application/service/CollectionLifecycleService.java')
synchronizer = text('myhomelib-application/src/main/java/com/myhomelibcorp/application/search/SearchIndexSynchronizer.java')
synchronizer_test = text('myhomelib-application/src/test/java/com/myhomelibcorp/application/search/SearchIndexSynchronizerTest.java')
lucene = text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/search/LuceneSearchService.java')
index_lifecycle = text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/search/LuceneCollectionIndexLifecycle.java')
paths = text('myhomelib-shared/src/main/java/com/myhomelibcorp/shared/util/AppPaths.java')
boot = text('myhomelib-bootstrap/src/main/java/com/myhomelibcorp/MyHomeLibApp.java')
controller = text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/CollectionController.java')

assert 'activateCollectionIndex(Collection collection)' in lifecycle_port
assert 'markCurrentIndexDirty()' in lifecycle_port and 'markCurrentIndexSynchronized()' in lifecycle_port
assert 'closeCurrentIndex()' in lifecycle_port and 'sealClosedIndex(Collection collection)' in lifecycle_port
assert 'collectionSearchIndexDir' in paths and 'collectionSearchIndexStateFile' in paths
assert 'implements SearchIndexer, SearchQueryService, IndexRebuilder' in lucene
assert 'LuceneCollectionIndexLifecycle implements SearchIndexLifecycle' in index_lifecycle
assert 'FSDirectory.open(indexPath)' in index_lifecycle, 'collection activation must open a per-collection filesystem index'
assert 'isCurrentIndexReusable()' in index_lifecycle and 'freshnessToken(' in index_lifecycle
assert 'activeBooks=' in index_lifecycle and 'appendWalState' in index_lifecycle, 'freshness marker must cover DB/WAL state and active-book count'
assert 'if (!reusable && search.getDocumentCount() > 0)' in index_lifecycle and 'search.clearIndex();' in index_lifecycle, 'dirty target index must never stay searchable'
assert 'setCommitObserver' in lucene and 'registerCommitObserver' in index_lifecycle, 'successful Lucene commits must refresh the collection freshness marker'
assert 'activeIndexDirty' in index_lifecycle and 'lastClosedIndexDirty' in index_lifecycle, 'explicit dirty state must survive close/seal'
assert 'persistDirtyMarker' in index_lifecycle and 'Files.writeString(tmp, "DIRTY"' in index_lifecycle, 'dirty proof must survive same-size/same-timestamp edge cases and process restart'
assert 'if (!activeIndexDirty && activeDatabasePath != null && activeStateFile != null)' in index_lifecycle, 'dirty index commits must not refresh freshness marker'
assert 'if (lastClosedIndexDirty)' in index_lifecycle, 'dirty index must never be sealed reusable after SQLite close'
assert 'searchIndexLifecycle.markCurrentIndexDirty();' in synchronizer
assert 'searchIndexLifecycle.markCurrentIndexSynchronized();' in synchronizer
assert 'failedSelectiveAndFullRebuildLeavesFreshnessDirty' in synchronizer_test

close_pos = lifecycle.index('searchIndexLifecycle.closeCurrentIndex();')
switch_pos = lifecycle.index('collectionLifecyclePort.switchToCollection(collection);')
seal_pos = lifecycle.index('searchIndexLifecycle.sealClosedIndex(previous);')
activate_pos = lifecycle.index('searchIndexLifecycle.activateCollectionIndex(collection);')
assert close_pos < switch_pos < seal_pos < activate_pos, 'close/switch/WAL-seal/activate order is unsafe'
assert 'boolean shouldRebuild = rebuildIndex && !reusableIndex;' in lifecycle
assert 'if (!searchIndexLifecycle.activateCollectionIndex(previous)) indexRebuilder.rebuildIndex();' in lifecycle

assert 'switchCollectionUseCase.execute(active, true);' in boot
assert 'luceneService.rebuildIndex();' not in boot, 'startup must not unconditionally rebuild a reusable 1M index'
assert 'switchCollectionUseCase.execute(collection, true)' in controller
assert 'rebuildSearchIndexAsync()' not in controller, 'UI must not duplicate lifecycle rebuild policy'

print('COLLECTION SEARCH LIFECYCLE CHECK: PASS')
print(' - Lucene storage is per-collection with persisted DB/WAL freshness markers')
print(' - clean indexes are reused; dirty indexes are not exposed')
print(' - WAL marker is sealed only after previous SQLite datasource closes/checkpoints')
print(' - bootstrap/UI no longer force unconditional rebuilds')
print(' - failed post-commit Lucene sync remains explicitly dirty across close/seal')
