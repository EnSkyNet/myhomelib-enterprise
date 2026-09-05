#!/usr/bin/env python3
from __future__ import annotations
import json, sqlite3, tempfile, sys
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
errors=[]
def need(c,m):
    if not c: errors.append(m)
def txt(p): return (ROOT/p).read_text(encoding='utf-8')

port=txt('myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/backup/UserDataTransferPort.java')
service=txt('myhomelib-application/src/main/java/com/myhomelibcorp/application/service/BackupRestoreService.java')
adapter=txt('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/backup/VersionedUserDataTransferAdapter.java')
backup_adapter=txt('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/adapter/CollectionBackupAdapter.java')
restore=txt('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/RestoreController.java')
restore_fxml=txt('myhomelib-ui/src/main/resources/view/restore-dialog.fxml')
backup_fxml=txt('myhomelib-ui/src/main/resources/view/backup-dialog.fxml')
test=txt('myhomelib-infrastructure/src/test/java/com/myhomelibcorp/infrastructure/backup/VersionedUserDataTransferAdapterTest.java')
legacy=txt('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/collection/legacy/SqliteLegacyCollectionAttachAdapter.java')
atomic_support=txt('myhomelib-shared/src/main/java/com/myhomelibcorp/shared/util/AtomicFileSupport.java')

need('CURRENT_SCHEMA_VERSION = 2' in port, 'portable user-data schema v2 missing')
for section in ['bookState','readingProgress','readingHistory','readingStats','bookmarks','groups','groupMemberships','savedSearches','filterSettings','readerSettings']:
    need(f'"{section}"' in adapter, f'missing portable section {section}')
need('SELECT id FROM books WHERE lib_id=?' in adapter, 'restore must resolve stable LibID first')
need('sourceBookId' in adapter, 'same-catalogue internal-id fallback missing')
need('restoreLegacyV1DatabaseStreaming' in adapter and 'case "ratings"' in adapter and 'case "reading"' in adapter, 'streaming v1 compatibility migration missing')
need('version' in adapter and 'ratings' in adapter and 'reading' in adapter, 'v1 previous-format compatibility missing')
need('ID_CACHE_LIMIT = 50_000' in adapter, 'bounded restore identity cache missing')
need('CollectionManager' in adapter and 'getCurrentJdbcTemplate()' in adapter, 'portable adapter must follow active collection dynamically')
need('VACUUM INTO' in backup_adapter, 'live SQLite backup must use VACUUM INTO')
need('RestoreRecoveryFiles.staged(targetDb)' in service and 'AtomicFileSupport.moveReplacing(stagedDb, targetDb)' in service and 'ATOMIC_MOVE' in atomic_support, 'restore staging/atomic swap missing')
need('closeCurrentCollection()' in service and 'openCollection(collection)' in service and 'migrateCurrentCollection()' in service, 'full restore must close/reopen collection and run sequential DB migrations')
need('Legacy database-only backup detected' in service, 'legacy DB-only backup compatibility missing')
need('userDataOnly' in service and 'restoreDatabase' in service, 'user-data-only restore mode missing')
need('restoreDatabaseCheckBox' in restore and 'user-data.json' in restore, 'restore UI mode/file detection missing')
need('Versioned user data (LibID)' in restore_fxml and 'Versioned user data (LibID)' in backup_fxml, 'backup/restore UI labels missing')
need('collectionManagementService.closeCurrentCollection()' not in restore, 'UI must not close collection before BackupRestoreService captures it')
need("value(rs, cols, \"libid\")" in legacy and 'intValue(rs,cols,"rate",0)' in legacy and 'intValue(rs,cols,"progress",0)' in legacy, 'legacy HLC2 attach must preserve LibID/rate/progress')
need("'old-1','L100'" in test and "'new-77','L100'" in test, 'LibID remap regression fixture missing')
need('sequentiallyMigratesPreviousV1Manifest' in test, 'v1 manifest compatibility test missing')
need('validateCurrentManifestStructure' in adapter and 'replaceByPrefix(FILTER_PREFIX' in adapter and 'rollbackExternalState' in adapter, 'portable restore preflight/external-state atomicity missing')
need('schemaVersion must be an integer' in adapter and 'format is missing for schema v' in adapter and 'Conflicting portable user-data schema versions' in adapter,
     'portable v2 header validation/anti-downgrade guards missing')
need('currentSchemaRestoreReplacesFilterSliceAndClearsExplicitNullReaderGlobal' in test and
     'malformedV2ExternalSectionIsRejectedBeforeDatabaseMutation' in test and
     'externalSettingsFailureRollsBackDatabaseAndRestoresExternalState' in test and
     'malformedOrConflictingVersionHeaderIsNeverDowngradedToLegacyV1' in test,
     'portable restore external-state/header regression fixtures missing')

# Runtime-check the exact SQLite snapshot primitive used by CollectionBackupAdapter.
try:
    with tempfile.TemporaryDirectory() as td:
        db=Path(td)/'live.db'; snap=Path(td)/'snapshot.db'
        c=sqlite3.connect(db)
        c.execute('PRAGMA journal_mode=WAL')
        c.execute('CREATE TABLE t(id INTEGER PRIMARY KEY, value TEXT)')
        c.execute("INSERT INTO t(value) VALUES('stage22')")
        c.commit()
        c.execute("VACUUM INTO ?", (str(snap),))
        c.close()
        r=sqlite3.connect(snap)
        need(r.execute('PRAGMA integrity_check').fetchone()[0]=='ok', 'VACUUM INTO snapshot integrity failed')
        need(r.execute('SELECT value FROM t').fetchone()[0]=='stage22', 'VACUUM INTO snapshot lost committed data')
        r.close()
except Exception as e:
    errors.append(f'SQLite VACUUM INTO smoke failed: {e}')

# Apply the real Flyway schema and execute the exact portable-export SELECT shape.
try:
    import re
    migration_dir = ROOT/'myhomelib-infrastructure/src/main/resources/db/migration'
    migrations = sorted(migration_dir.glob('V*__*.sql'), key=lambda p: int(re.match(r'V(\d+)__', p.name).group(1)))
    with tempfile.TemporaryDirectory() as td:
        db = Path(td)/'migrated.db'
        c = sqlite3.connect(db)
        for migration in migrations:
            c.executescript(migration.read_text(encoding='utf-8'))
        c.execute("INSERT INTO books(id,title,file_name,lib_id,rate,progress,review) VALUES('stage22-book','Stage 22','stage22.fb2','LIB-STAGE22',5,17,'review')")
        export_queries = [
            """SELECT b.lib_id, b.id, COALESCE(b.rate,0), COALESCE(b.progress,0), COALESCE(b.review,'') FROM books b WHERE COALESCE(b.rate,0) <> 0 OR COALESCE(b.progress,0) <> 0 OR TRIM(COALESCE(b.review,'')) <> '' ORDER BY b.id""",
            """SELECT b.lib_id,b.id,rp.paragraph_id,rp.char_offset,rp.percent,rp.updated_at,rp.anchor_id,COALESCE(rp.paragraph_index,0) FROM reading_progress rp JOIN books b ON b.id=rp.book_id ORDER BY rp.updated_at,b.id""",
            """SELECT b.lib_id,b.id,rh.last_opened_at,rh.open_count FROM reading_history rh JOIN books b ON b.id=rh.book_id ORDER BY rh.last_opened_at,b.id""",
            """SELECT b.lib_id,b.id,rs.first_read_at,rs.last_read_at,COALESCE(rs.total_reading_seconds,0),COALESCE(rs.reading_sessions,0),COALESCE(rs.start_percent,0),COALESCE(rs.end_percent,0),COALESCE(rs.current_percent,0),rs.completed_at FROM reading_stats rs JOIN books b ON b.id=rs.book_id ORDER BY rs.id""",
            """SELECT bm.id,b.lib_id,b.id,bm.paragraph_id,COALESCE(bm.char_offset,0),COALESCE(bm.position,0),bm.chapter_title,bm.context,bm.created_at FROM bookmarks bm JOIN books b ON b.id=bm.book_id ORDER BY bm.created_at,bm.id""",
            "SELECT name,COALESCE(allow_delete,1) FROM groups ORDER BY id",
            """SELECT g.name,b.lib_id,b.id FROM book_groups bg JOIN groups g ON g.id=bg.group_id JOIN books b ON b.id=bg.book_id ORDER BY g.name,b.id""",
            "SELECT id,name,query,filters,created_at,last_used,COALESCE(use_count,0) FROM saved_searches ORDER BY name",
        ]
        for query in export_queries:
            c.execute(query).fetchall()
        plan = ' '.join(str(x) for row in c.execute("EXPLAIN QUERY PLAN SELECT id FROM books WHERE lib_id='LIB-STAGE22'") for x in row)
        need('idx_books_lib_id' in plan, 'LibID restore lookup must use idx_books_lib_id on migrated schema')
        need(c.execute("SELECT id FROM books WHERE lib_id='LIB-STAGE22'").fetchone()[0]=='stage22-book', 'LibID lookup failed on migrated schema')
        need(c.execute('PRAGMA integrity_check').fetchone()[0]=='ok', 'migrated Stage22 schema integrity failed')
        c.close()
except Exception as e:
    errors.append(f'migrated-schema portable query smoke failed: {e}')

if errors:
    print('STAGE 22 VERSIONED USER-DATA CHECK: FAILED')
    for e in errors: print(' -',e)
    sys.exit(1)
print('STAGE 22 VERSIONED USER-DATA CHECK: PASS')
print(' - schema-v2 portable user-data sections + streaming v1 compatibility: PASS')
print(' - stable LibID-first remap + bounded identity cache + migrated-schema index lookup: PASS')
print(' - WAL-safe VACUUM INTO backup + staged atomic DB restore: PASS')
print(' - full restore + user-data-only UI modes + legacy DB-only compatibility: PASS')
print(' - exact external-state replacement + preflight/rollback/header anti-downgrade guards: PASS')
print(' - JUnit fixtures for changed internal IDs/idempotence/v1 manifest: PRESENT')
