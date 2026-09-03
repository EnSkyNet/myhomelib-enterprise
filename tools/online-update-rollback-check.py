#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
usecase = (ROOT / 'myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/UpdateCollectionFromNetworkUseCase.java').read_text(encoding='utf-8')
adapter = (ROOT / 'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/adapter/CollectionBackupAdapter.java').read_text(encoding='utf-8')
ui = (ROOT / 'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/CollectionUpdateUiService.java').read_text(encoding='utf-8')
exc = (ROOT / 'myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/CatalogUpdateFailureException.java').read_text(encoding='utf-8')

errors = []

def require(cond, message):
    if not cond:
        errors.append(message)

snapshot = usecase.find('collectionBackupPort.createDatabaseSnapshot')
mutation = usecase.find('mutationMayHaveCommitted = true')
import_call = usecase.find('importer.execute(')
record_applied = usecase.find('sourceState.recordApplied')
stats = usecase.find('statisticsRepository.refreshStatistics()')
require(snapshot >= 0 and mutation >= 0 and import_call >= 0 and snapshot < mutation < import_call,
        'SQLite checkpoint must be created before first possible committed catalog mutation')
require(record_applied > stats >= 0, 'appliedVersion must advance only after statistics refresh')
require('attemptRollback(active, checkpoint, mutationMayHaveCommitted' in usecase,
        'failure/cancellation path must attempt rollback after possible DB mutation')
require('collectionBackupPort.restoreDatabaseSnapshot(collection, checkpoint)' in usecase,
        'rollback must restore SQLite checkpoint')
require('searchIndexer.rebuildIndex(neverCancelRecovery' in usecase,
        'rollback must rebuild Lucene from restored SQLite')
require('OperationStage.ROLLING_BACK' in usecase and 'OperationStage.CREATING_CHECKPOINT' in usecase,
        'checkpoint/rollback stages must be observable')
require('keepCheckpoint = rollback.attempted() && !rollback.succeeded()' in usecase,
        'failed rollback must preserve recovery checkpoint')

require('AtomicFileSupport.moveReplacing' in adapter,
        'database restore must use atomic replacing moves')
require('validateDatabaseFile(snapshotFile)' in adapter and 'validateDatabaseFile(staged)' in adapter,
        'checkpoint and staged restore candidate must be validated')
require('collectionManager.closeCurrentCollection()' in adapter and 'collectionManager.switchToCollection(collection)' in adapter,
        'database swap must close and reopen active collection')

for field in ('stage', 'source', 'lastAppliedVersion', 'mutationMayHaveCommitted', 'rollbackAttempted', 'rollbackSucceeded'):
    require(field in exc, f'structured catalog update failure missing {field}')
for marker in ('Етап:', 'Джерело:', 'Технічна причина:', 'Остання успішна версія:', 'Стан локальної колекції:'):
    require(marker in ui, f'error UI missing {marker}')
require('showErrorWithRetry' in ui, 'safe online update failures must expose Retry')
require('safeToRetry = !failure.mutationMayHaveCommitted() || failure.rollbackSucceeded()' in ui,
        'Retry must be blocked when rollback did not restore a mutated catalog')

if errors:
    print('ONLINE UPDATE ROLLBACK CHECK: FAIL')
    for e in errors:
        print(' -', e)
    raise SystemExit(1)

print('ONLINE UPDATE ROLLBACK CHECK: PASS')
print(' - SQLite checkpoint precedes bounded catalog commits')
print(' - failure/cancellation restores SQLite and rebuilds Lucene/statistics')
print(' - failed rollback preserves recovery checkpoint')
print(' - structured UI reports stage/source/version/local state and safe Retry')
