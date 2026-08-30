#!/usr/bin/env python3
"""Offline guard for user-state -> Lucene invalidation and folder-INPX finalization."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
errors=[]
def read(rel):
    p=ROOT/rel
    if not p.exists(): errors.append(f'missing {rel}'); return ''
    return p.read_text(encoding='utf-8')
def need(cond,msg):
    if not cond: errors.append(msg)

sync=read('myhomelib-application/src/main/java/com/myhomelibcorp/application/search/SearchIndexSynchronizer.java')
need('TransactionSynchronizationManager.registerSynchronization' in sync, 'search synchronization is not deferred to AFTER_COMMIT')
need('beginAtomicUpdate()' in sync and 'rollbackAtomicUpdate()' in sync and 'commit()' in sync,
     'selective search synchronization is not atomic')
need('rebuildIndex()' in sync and 'synchronizeSafelyNow' in sync,
     'committed-state selective failure lacks full-rebuild fallback')

for rel, mutation in [
 ('myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/book/UpdateRateBatchUseCase.java','updateRateBatch'),
 ('myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/book/UpdateProgressBatchUseCase.java','updateProgressBatch'),
 ('myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/book/MarkAsReadBatchUseCase.java','updateProgressBatch')]:
    t=read(rel)
    need(mutation in t, f'{rel} lost DB mutation')
    need('synchronizeAfterCommit(bookIds)' in t, f'{rel} does not invalidate Lucene after committed user-state change')

for rel in [
 'myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/download/DownloadBookUseCase.java',
 'myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/download/RemoveLocalBookCopyUseCase.java']:
    t=read(rel)
    need('updateStorage' in t, f'{rel} lost local-storage mutation')
    need('synchronizeSafelyNow' in t, f'{rel} does not refresh Lucene local-state filter')

folder=read('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/sync/FolderSyncService.java')
inpx=read('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/sync/FolderSyncInpxSupport.java')
need('FolderSyncInpxSupport.importAndAccumulate' in folder, 'FolderSyncService still performs inline per-INPX indexing')
need('FolderSyncInpxSupport.finalizeIndex' in folder, 'FolderSyncService lacks one final INPX index finalization')
need('ImportChangeAccumulator' in folder, 'FolderSyncService lacks bounded cross-file INPX change tracking')
need('importFileWithResult' in inpx, 'Folder sync still uses count-only INPX import result')
need(inpx.count('rebuildIndex()') == 1, 'FolderSync INPX support has more than one full-rebuild call path')
need('synchronizeSafelyNow' in inpx, 'complete INPX changes do not use selective Lucene synchronization')

need((ROOT/'myhomelib-application/src/test/java/com/myhomelibcorp/application/search/SearchIndexSynchronizerTest.java').is_file(),
     'SearchIndexSynchronizer regression test missing')
ft=read('myhomelib-infrastructure/src/test/java/com/myhomelibcorp/infrastructure/sync/FolderSyncServiceTest.java')
need('multipleInpxFilesUseOneBoundedSelectiveLuceneFinalization' in ft, 'multi-INPX selective finalization regression test missing')
need('incompleteInpxTrackingTriggersOnlyOneFullRebuildAfterAllFiles' in ft, 'bounded overflow single-rebuild regression test missing')

if errors:
    print('USER STATE / SEARCH INDEX CHECK: FAIL')
    for e in errors: print(' -',e)
    sys.exit(1)
print('USER STATE / SEARCH INDEX CHECK: PASS')
print(' - rating/progress/read changes defer Lucene refresh until DB commit')
print(' - local download/remove changes refresh Lucene derived state')
print(' - folder INPX changes aggregate bounded exact IDs and finalize once')
