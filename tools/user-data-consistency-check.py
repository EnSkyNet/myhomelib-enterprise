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
v65=txt('myhomelib-infrastructure/src/main/resources/db/migration/V65__reader_scoped_preferences.sql')
scoped=txt('myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/reader/ReaderScopedPreferencesPort.java')
state=txt('myhomelib-application/src/main/java/com/myhomelibcorp/application/reader/ReaderSettingsStateService.java')

need(absent('myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/exchange/UserDataExchangePort.java'), 'legacy UserDataExchangePort must stay removed')
need(absent('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/exchange/JsonUserDataExchangeAdapter.java'), 'legacy JsonUserDataExchangeAdapter must stay removed')
need('reader_book_preferences' in v42 and 'PRIMARY KEY' in v42 and 'ON DELETE CASCADE' in v42, 'V42 reader-book preference storage contract missing')
need('reader_book_preferences' in reader and 'LEGACY_MIGRATION_BATCH = 400' in reader, 'per-book reader settings must use bounded collection DB migration')
need('reader_preference_overrides' in v65 and "'BOOK'" in v65 and 'FROM reader_book_preferences' in v65, 'V65 scoped Reader migration/storage contract missing')
need('ReaderPreferenceScope scope' in scoped and 'save(ReaderPreferenceScope' in scoped, 'scoped Reader preference port missing')
need('ReaderSettingsState.Source.BOOK' in state and 'ReaderSettingsState.Source.SERIES' in state and 'ReaderSettingsState.Source.FORMAT' in state, 'Reader precedence resolver scopes missing')
need('reader_preference_overrides' in adapter and '"scoped"' in adapter, 'portable scoped Reader profile transfer missing')
need('ImportChangeSet searchChanges' in port, 'portable import must expose bounded search changes')
need('searchIndexSynchronizer.synchronizeSafelyNow' in service and 'searchIndexer.rebuildIndex()' in service, 'direct portable restore must synchronize Lucene selectively/full fallback')
need('SELECT id FROM books WHERE lib_id=? ORDER BY id LIMIT 2' in adapter, 'LibID ambiguity lookup must inspect at least two rows')
need('!ambiguousLibId' in adapter, 'ambiguous LibID must not fall back to unrelated internal id')
need('ImportChangeAccumulator searchChanges' in adapter and 'recordUpdated(bookId)' in adapter, 'book-state restore must record bounded Lucene changes')
need('restoreCurrentSchemaStreaming(stableSource, header)' in adapter and 'private ImportResult restoreCurrentSchemaStreaming(' in adapter and 'Path sourceFile, PortableUserDataManifestValidator.ManifestHeader header' in adapter and 'JsonParser parser = mapper.createParser(sourceFile)' in adapter, 'current schema restore must use the stable snapshot and streaming parser')
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
print(' - V42 compatibility + V65 BOOK/SERIES/FORMAT Reader scopes and precedence: PASS')
print(' - bounded portable restore + LibID ambiguity policy: PASS')
print(' - direct restore -> Lucene selective/full synchronization: PASS')
print(' - stale repeated nested-type compile blockers: 0')
