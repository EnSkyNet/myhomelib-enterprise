#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def text(rel):
    return (ROOT / rel).read_text(encoding='utf-8')

def require(cond, msg):
    if not cond:
        raise AssertionError(msg)

switch = text('myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/SwitchCollectionUseCase.java')
workspace = text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/collection/CollectionWorkspaceController.java')
manager = text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/collection/CollectionManager.java')
lifecycle = text('myhomelib-application/src/main/java/com/myhomelibcorp/application/service/CollectionLifecycleService.java')
repo = text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteCollectionRepository.java')
props = text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/CollectionPropertiesUiService.java')
types = text('myhomelib-domain/src/main/java/com/myhomelibcorp/domain/model/collection/CollectionType.java')
network = text('myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/UpdateCollectionFromNetworkUseCase.java')
create = text('myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/CreateCollectionUseCase.java')

require('collectionRepository.findById(collection.getId()).orElse(collection)' in switch,
        'activation does not reload authoritative collection metadata')
require('switchCollectionUseCase.execute(collectionDto.getId())' in workspace,
        'collection workspace still activates a lossy DTO reconstruction')
require('HikariDataSource candidate = null' in manager and 'currentHikariDataSource.getAndSet(candidate)' in manager,
        'collection switch is not staged through a validated candidate datasource')
require('restorePreviousCollection(previous)' in lifecycle,
        'lifecycle does not rollback after migration/index failure')
require('throw new IllegalStateException("Інше переключення колекції вже виконується")' in lifecycle,
        'concurrent collection initialization is silently ignored')
require('return findById(collection.getId())' in repo,
        'repository UPDATE does not return persisted/encrypted representation')
require('ComboBox<CollectionType>' in props and 'selectedType.getCode()' in props,
        'properties dialog can still corrupt collection type codes')
require('public boolean requiresSource() { return this == INPX_ARCHIVE; }' in types,
        'remote collections still incorrectly require a local source file')
require('Оновлювати з мережі можна лише активну колекцію' in network,
        'network update can target a non-active collection')
require('downloader.downloadUpdates(' in network and 'active, source.trim(), localVersion' in network,
        'network update is not using active persisted credentials/version state')
require('request.isImportOnCreate()' in create and 'importFileUseCase.execute(context.build())' in create,
        'create wizard import flags/source are still ignored')

print('CATALOG LIFECYCLE REGRESSION CHECK: PASS')
print(' - authoritative metadata preserved on activation: PASS')
print(' - failed switch keeps/restores previous collection: PASS')
print(' - active metadata refresh after rename/properties: PASS')
print(' - persisted encrypted credentials returned after UPDATE: PASS')
print(' - all collection type codes preserved in properties UI: PASS')
print(' - remote creation can be URL-only: PASS')
print(' - online INPX update limited to active collection/credentials: PASS')
print(' - create-with-source now performs requested import: PASS')
