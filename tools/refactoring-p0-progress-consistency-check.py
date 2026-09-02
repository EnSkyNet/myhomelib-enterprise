#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def read(rel):
    return (ROOT / rel).read_text(encoding='utf-8')

ui = read('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/imports/ImportWorkspaceController.java')
update_ui = read('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/CollectionUpdateUiService.java')
update_uc = read('myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/UpdateCollectionFromNetworkUseCase.java')
stages = read('myhomelib-application/src/main/java/com/myhomelibcorp/application/progress/OperationStage.java')
pipeline = read('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/importengine/InpxImportPipeline.java')
stats_handler = read('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/event/ImportEventHandler.java')

assert 'value * 1000' not in ui, 'synthetic import progress scale returned'
assert 'java.nio.file.Files.walk(dir)' not in ui, 'directory pre-scan blocks UI before import'
assert '.operationProgressListener(this::updateOperationProgress)' in ui, 'structured import progress is not wired'
assert 'REFRESHING_STATISTICS' in stages, 'statistics stage missing'
assert 'statisticsRepository.invalidate();' in update_uc and 'statisticsRepository.refreshStatistics();' in update_uc
assert update_uc.index('statisticsRepository.refreshStatistics();') < update_uc.index('sourceState.recordApplied(sourceKey, applied);'), 'appliedVersion advances before statistics refresh'
assert 'changes.insertedCount()' in update_ui and 'changes.updatedCount()' in update_ui, 'bounded ID sets are still used as full counters'
assert 'changes.inserted().size()' not in update_ui and 'changes.updated().size()' not in update_ui
assert 'Записи, позначені джерелом як видалені (DEL)' in update_ui
assert 'Явно видалено' not in update_ui
assert 'INPX Hikari [' in pipeline and 'transaction committed' in pipeline and 'connection-returned' in pipeline
assert 'statisticsService.invalidate();' in stats_handler, 'ordinary import does not mark statistics stale before refresh'

print('REFACTORING P0 PROGRESS/CONSISTENCY CHECK: PASS')
print(' - no synthetic 0..1000 progress or FX-thread directory pre-scan')
print(' - online update: Lucene -> statistics -> appliedVersion')
print(' - exact import counters + unambiguous DEL wording')
print(' - Hikari transaction/pool telemetry enabled')
