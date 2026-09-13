#!/usr/bin/env python3
from pathlib import Path
import sys
ROOT = Path(__file__).resolve().parents[1]
checks=[]
def text(rel): return (ROOT/rel).read_text(encoding='utf-8')
def check(name, cond): checks.append((name, bool(cond)))

record=text('myhomelib-domain/src/main/java/com/myhomelibcorp/domain/model/sync/SyncRecord.java')
entity=text('myhomelib-domain/src/main/java/com/myhomelibcorp/domain/model/sync/SyncEntityType.java')
changes=text('myhomelib-domain/src/main/java/com/myhomelibcorp/domain/model/sync/ChangeSet.java')
factory=text('myhomelib-application/src/main/java/com/myhomelibcorp/application/sync/UserDataSyncRecordFactory.java')
transport=text('myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/sync/SyncTransportPort.java')
folder=text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/sync/folder/LocalFolderSyncAdapter.java')
codec=text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/sync/folder/SyncBundleJsonCodec.java')

for typ in ('READING_PROGRESS','BOOKMARK','ANNOTATION','RATING','GROUP','FAVORITE','SETTING'):
    check('entity type '+typ, typ in entity)
check('version/conflict metadata', all(x in record for x in ('baseVersion','version','updatedAt','deviceId','schemaVersion')))
check('delete tombstones', 'tombstone' in record and 'tombstone payload must be empty' in record)
check('schema versioned', 'SyncSchema.requireSupported' in record and 'SyncSchema.requireSupported' in changes)
check('typed user-data mapping', all(x in factory for x in ('readingProgress(','bookmark(','annotation(','rating(','group(','favorite(','setting(')))
check('transport is change-set only', 'ChangeSet' in transport and '.db' not in transport and 'DataSource' not in transport)
check('folder final/partial split', 'FINAL_SUFFIX' in folder and 'PART_SUFFIX' in folder and 'newDirectoryStream(folder, "*" + FINAL_SUFFIX)' in folder)
check('atomic publish', 'StandardCopyOption.ATOMIC_MOVE' in folder and 'AtomicMoveNotSupportedException' in folder)
check('folder locking', 'tryLock()' in folder and 'OverlappingFileLockException' in folder)
check('cursor conflict detection', 'Conflicting sync bundles for cursor' in folder)
check('bounded bundle', 'MAX_BUNDLE_BYTES' in codec and 'MAX_RECORDS' in codec and 'MAX_PAYLOAD_FIELDS' in codec)
check('no raw database sync', all(x not in (folder+codec) for x in ('jdbc:sqlite', 'sqlite_master', 'VACUUM INTO')))

failed=[name for name,ok in checks if not ok]
for name,ok in checks: print(('PASS' if ok else 'FAIL')+': '+name)
print(f'RESULT: {len(checks)-len(failed)}/{len(checks)} PASS')
sys.exit(1 if failed else 0)
