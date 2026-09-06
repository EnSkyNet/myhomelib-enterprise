#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def text(rel):
    return (ROOT / rel).read_text(encoding='utf-8')

orchestrator = text('myhomelib-bootstrap/src/main/java/com/myhomelibcorp/startup/StartupOrchestrator.java')
expected = [
    'recoveryStartupTask',
    'migrationStartupTask',
    'searchStartupTask',
    'backupStartupTask',
    'opdsStartupTask',
]
pos = [orchestrator.index(name) for name in expected]
assert pos == sorted(pos), 'startup task constructor/order is not explicit recovery->migration->search->backup->opds'
list_body = orchestrator.split('this.tasks = List.of(', 1)[1].split(');', 1)[0]
for name in expected:
    assert name in list_body, f'{name} missing from explicit startup sequence'
assert 'task.failurePolicy() == StartupFailurePolicy.REQUIRED' in orchestrator, 'required/best-effort policy dispatch missing'
assert 'throw new StartupException(task.id(), failure);' in orchestrator, 'required failure must abort with task identity'
assert 'StartupTaskOutcome.Status.DEGRADED' in orchestrator, 'best-effort failure must be recorded as degraded'

recovery = text('myhomelib-bootstrap/src/main/java/com/myhomelibcorp/startup/RecoveryStartupTask.java')
migration = text('myhomelib-bootstrap/src/main/java/com/myhomelibcorp/startup/MigrationStartupTask.java')
search = text('myhomelib-bootstrap/src/main/java/com/myhomelibcorp/startup/SearchStartupTask.java')
backup = text('myhomelib-bootstrap/src/main/java/com/myhomelibcorp/startup/BackupStartupTask.java')
opds = text('myhomelib-bootstrap/src/main/java/com/myhomelibcorp/startup/OPDSStartupTask.java')
manager = text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/collection/CollectionManager.java')
boot = text('myhomelib-bootstrap/src/main/java/com/myhomelibcorp/MyHomeLibApp.java')

assert 'StartupFailurePolicy.REQUIRED' in recovery and 'recoverBeforeOpen(context.activeCollection())' in recovery
assert 'StartupFailurePolicy.REQUIRED' in migration and 'executeWithStatus(context.activeCollection(), false)' in migration
assert 'context.reusableSearchIndex(result.reusableSearchIndex())' in migration
assert 'StartupFailurePolicy.BEST_EFFORT' in search and 'rebuildSearchIndexAsync()' in search
assert 'if (context.reusableSearchIndex())' in search, 'reusable indexes must not be rebuilt at startup'
assert 'StartupFailurePolicy.BEST_EFFORT' in backup and '.snapshot.tmp' in backup
assert 'StartupFailurePolicy.BEST_EFFORT' in opds and 'settings.autostart()' in opds and 'serverControl.start(settings)' in opds
assert 'startupRecoveryService.recoverBeforeOpen(collection);' in manager, 'CollectionManager safety net must use the same recovery boundary'
assert 'startupExecutor.submit(startupOrchestrator::run)' in boot, 'JavaFX startup must delegate backend orchestration off the FX thread'
for forbidden in ['SqliteCollectionRepository', 'SwitchCollectionUseCase', 'InpxImporter', 'initializeBackend()']:
    assert forbidden not in boot, f'MyHomeLibApp regained startup responsibility: {forbidden}'

print('startup-orchestration-check: PASS')
