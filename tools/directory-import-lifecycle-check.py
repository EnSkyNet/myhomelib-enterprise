#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
USECASE = ROOT / 'myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/imports/ImportDirectoryUseCase.java'
FILE_USECASE = ROOT / 'myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/imports/ImportFileUseCase.java'
TEST = ROOT / 'myhomelib-application/src/test/java/com/myhomelibcorp/application/usecase/imports/ImportDirectoryUseCaseTest.java'
SCANNER = ROOT / 'myhomelib-application/src/main/java/com/myhomelibcorp/application/imports/scanner/LibraryScanner.java'

errors = []

def need(cond, message):
    if not cond:
        errors.append(message)

src = USECASE.read_text(encoding='utf-8')
file_src = FILE_USECASE.read_text(encoding='utf-8')
scanner = SCANNER.read_text(encoding='utf-8')
test = TEST.read_text(encoding='utf-8') if TEST.exists() else ''

need('Files.walk(directory, depth)' in scanner, 'LibraryScanner must keep a lazy Files.walk traversal')
need(src.count('libraryScanner.streamSupportedFiles(directory)') == 1,
     'directory import must perform exactly one supported-file stream traversal')
need('.progressListener(null)' in src and '.indexAfterSave(false)' in src and '.publishFinishedEvent(false)' in src,
     'child imports must not reset aggregate progress/index/event orchestration')
need('result.status() == ImportStatus.CANCELLED' in src and 'cancelled ? ImportStatus.CANCELLED' in src,
     'child/external cancellation must propagate to the directory ImportResult')
need('if (!cancelled && context.getProgressListener() != null) context.getProgressListener().accept(1.0);' in src,
     'directory cancellation must never emit 100% completion progress')
need('requiresSearchFinalization(finalResult)' in src and 'changes.deletedCount()' in src,
     'directory Lucene finalization must cover changed/deleted-only results')
need('changedIds.isEmpty()' in src and 'indexRebuilder.rebuildIndex()' in src,
     'positive imports without exact IDs must safely fall back to full Lucene rebuild')
need('if (!isCancelled(context)) {' in file_src and 'reportProgress(progressListener, 1.0' in file_src,
     'legacy file import must not emit 100% after cancellation')

for marker in (
    'streamsDirectoryOnceAndDoesNotForwardAggregateProgressToChildImports',
    'cancelledDirectorySynchronizesCommittedChangesButNeverReportsHundredPercent',
    'childCancelledStatusStopsDirectoryEvenIfExternalFlagWasNotSet',
    'positiveImportWithoutExactIdsFallsBackToFullRebuild',
    'deletedOnlyChangesAreSynchronizedEvenWhenImportedCountIsZero',
    'completionProgressIsEmittedOnlyAfterRequestedSearchSynchronization',
):
    need(marker in test, f'missing directory-import regression fixture: {marker}')

if errors:
    print('DIRECTORY IMPORT LIFECYCLE CHECK: FAILED')
    for error in errors:
        print(' -', error)
    sys.exit(1)

print('DIRECTORY IMPORT LIFECYCLE CHECK: PASS')
print(' - one lazy filesystem traversal, no child progress reset')
print(' - cancellation status/progress semantics guarded')
print(' - selective/full Lucene finalization covers missing IDs and deleted-only changes')
