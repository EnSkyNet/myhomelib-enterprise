#!/usr/bin/env python3
from pathlib import Path
import re, sys

ROOT = Path(__file__).resolve().parents[1]
errors=[]
def need(cond,msg):
    if not cond: errors.append(msg)
def txt(path): return (ROOT/path).read_text(encoding='utf-8')

def absent(path): return not (ROOT/path).exists()

adapter=txt('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/backup/VersionedUserDataTransferAdapter.java')
port=txt('myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/backup/UserDataTransferPort.java')
service=txt('myhomelib-application/src/main/java/com/myhomelibcorp/application/service/PortableUserDataService.java')
reader=txt('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/reader/ReaderBookPreferencesService.java')
v42=txt('myhomelib-infrastructure/src/main/resources/db/migration/V42__reader_book_preferences.sql')

need(absent('myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/exchange/UserDataExchangePort.java'), 'legacy UserDataExchangePort must stay removed')
need(absent('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/exchange/JsonUserDataExchangeAdapter.java'), 'legacy JsonUserDataExchangeAdapter must stay removed')
need('reader_book_preferences' in v42 and 'PRIMARY KEY' in v42 and 'ON DELETE CASCADE' in v42, 'V42 reader-book preference storage contract missing')
need('reader_book_preferences' in reader and 'LEGACY_MIGRATION_BATCH = 400' in reader, 'per-book reader settings must use bounded collection DB migration')
need('ImportChangeSet searchChanges' in port, 'portable import must expose bounded search changes')
need('searchIndexSynchronizer.synchronizeSafelyNow' in service and 'searchIndexer.rebuildIndex()' in service, 'direct portable restore must synchronize Lucene selectively/full fallback')
need('SELECT id FROM books WHERE lib_id=? ORDER BY id LIMIT 2' in adapter, 'LibID ambiguity lookup must inspect at least two rows')
need('!ambiguousLibId' in adapter, 'ambiguous LibID must not fall back to unrelated internal id')
need('ImportChangeAccumulator searchChanges' in adapter and 'recordUpdated(bookId)' in adapter, 'book-state restore must record bounded Lucene changes')
need('restoreCurrentSchemaStreaming(sourceFile, header)' in adapter and 'JsonParser parser = mapper.getFactory().createParser(sourceFile.toFile())' in adapter, 'current schema restore must use streaming parser')
need('readJson(Path file)' not in adapter, 'dead locked JSON reader reintroduced')

# Stale nested-type references often survive class extraction and are compile blockers.
for path in ROOT.glob('myhomelib-*/src/main/java/**/*.java'):
    s=path.read_text(encoding='utf-8')
    m=re.search(r'\b([A-Z][A-Za-z0-9_]*)\.\1\.', s)
    if m:
        errors.append(f'stale repeated nested type reference in {path.relative_to(ROOT)}: {m.group(0)}')

if errors:
    print('USER-DATA CONSISTENCY CHECK: FAILED')
    for e in errors: print(' -',e)
    sys.exit(1)
print('USER-DATA CONSISTENCY CHECK: PASS')
print(' - one versioned user-data format; legacy duplicate removed: PASS')
print(' - V42 collection-scoped Reader overrides + bounded migration: PASS')
print(' - bounded portable restore + LibID ambiguity policy: PASS')
print(' - direct restore -> Lucene selective/full synchronization: PASS')
print(' - stale repeated nested-type compile blockers: 0')
