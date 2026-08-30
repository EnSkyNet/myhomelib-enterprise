#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
usecase = (root / 'myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/imports/ImportFileUseCase.java').read_text()
start = usecase.index('private ImportResult executeInpx')
end = usecase.index('private static boolean requiresSearchFinalization', start)
block = usecase[start:end]
failures = []
if 'applyIncrementalIndex(result.changes())' not in block:
    failures.append('fast INPX complete change sets are not applied selectively to Lucene')
if 'searchIndexer.rebuildIndex()' not in block:
    failures.append('fast INPX incomplete/bounded change sets do not rebuild Lucene')
if 'context.isIndexAfterSave()' not in block:
    failures.append('fast INPX ignores indexAfterSave ownership')
if 'searchIndexer.commit()' in block:
    failures.append('fast INPX still uses commit-only pseudo-indexing')
if 'requiresSearchFinalization(result)' not in block:
    failures.append('fast INPX does not distinguish no-op from required search finalization')

if failures:
    for failure in failures:
        print('FAIL:', failure)
    raise SystemExit(1)
print('PASS: fast INPX DB changes are finalized into Lucene selectively or by bounded fallback rebuild')
