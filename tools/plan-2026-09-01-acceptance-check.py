#!/usr/bin/env python3
from pathlib import Path
import re, sys

ROOT = Path(__file__).resolve().parents[1]
failures=[]

def text(rel):
    p=ROOT/rel
    if not p.exists():
        failures.append(f"missing file: {rel}")
        return ""
    return p.read_text(encoding='utf-8', errors='replace')

def require(rel, *needles):
    s=text(rel)
    for n in needles:
        if n not in s:
            failures.append(f"{rel}: missing marker {n!r}")
    return s

def forbid(rel, *needles):
    s=text(rel)
    for n in needles:
        if n in s:
            failures.append(f"{rel}: forbidden legacy marker {n!r}")
    return s

# P0 — INPX safety and diagnostics.
pipeline=require('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/importengine/InpxImportPipeline.java',
                 'explicit DEL', 'NormalizedBook')
if 'markTrackedBooksMissing(' in pipeline:
    failures.append('InpxImportPipeline still performs snapshot absence => deleted transition')
require('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/importengine/InpxBookNormalizer.java',
        'WITHOUT_AUTHOR_NAME', '"Без автора"', 'field("DEL")', 'withoutAuthor', 'withoutGenre', 'explicitlyDeleted')
require('myhomelib-application/src/main/java/com/myhomelibcorp/application/imports/statistics/ImportResult.java',
        'withoutAuthor', 'withoutGenre', 'explicitlyDeleted')

# P0 — one batch selection source, no cursor fallback.
sel=require('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/BookSelectionService.java',
            'Set<BookId>', 'snapshot()', 'selectedCount', 'currentLibraryCollectionProperty', 'SelectionState')
require('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/BatchOperationsController.java', 'bookSelectionService.snapshot()')
require('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/ExportController.java', 'bookSelectionService.snapshot()')
require('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/CollectionCopyUiService.java', 'selection.snapshot()')
require('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/table/TreeBookTableController.java', 'bookSelectionService.snapshot()')
require('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/table/BookTableController.java',
        'before != BookSelectionService.SelectionState.ALL', 'masterSelectionCheckBox.setIndeterminate')

# P1 — unpaged Author workspace, series, genres, physical local state, keyboard/profile state.
author=require('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/author/AuthorWorkspaceController.java',
               'loadBooksByAuthorUseCase.executeAll', 'onCollapseAll', 'onExpandAll',
               'SelectionState.PARTIAL', 'before != BookSelectionService.SelectionState.ALL',
               'visibleConcreteBooks()', 'genresColumn',
               'resolveBookLocalAvailabilityUseCase.execute', 'KeyCode.SPACE', 'KeyCode.A',
               'tableProfileService.apply', 'ensureSeriesGroupingAndRefresh')
for legacy in ('PAGE_SIZE', 'onNextPage', 'onPreviousPage', 'hasNextPage'):
    if legacy in author:
        failures.append(f'AuthorWorkspaceController: legacy paging marker {legacy!r}')
fxml=require('myhomelib-ui/src/main/resources/view/author-workspace.fxml', 'fx:id="genresColumn"', 'fx:id="localFilterComboBox"')
for legacy in ('onNextPage', 'onPreviousPage'):
    if legacy in fxml:
        failures.append(f'AuthorWorkspace.fxml: legacy paging handler {legacy!r}')
require('myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/book/ResolveBookLocalAvailabilityUseCase.java',
        'BookResourcePort', 'locateBookFile')

# P1 — whole application theme and persistence.
require('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/ApplicationThemeService.java',
        'SYSTEM', 'LIGHT', 'DARK', 'CUSTOM', 'Window.getWindows()', 'public void apply(ThemeConfig config)')
require('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/ApplicationSettingsDialog.java',
        'Відновити стандартні', 'private void preview()', 'Theme')
require('myhomelib-ui/src/main/resources/css/app-theme-base.css', '-mhl-background', '-mhl-panel', '-mhl-accent')
require('myhomelib-bootstrap/src/main/java/com/myhomelibcorp/MyHomeLibApp.java', 'ApplicationThemeService')

# Theme debt ratchet: hard-coded six-digit colours belong only to central theme service.
for p in (ROOT/'myhomelib-ui/src/main').rglob('*'):
    if not p.is_file() or p.suffix.lower() not in {'.java','.css','.fxml'}:
        continue
    if p.name in {'ApplicationThemeService.java', 'app-theme-base.css'}:
        continue
    s=p.read_text(encoding='utf-8',errors='replace')
    if re.search(r'#[0-9A-Fa-f]{6}(?![0-9A-Fa-f])',s):
        failures.append(f'hard-coded UI colour outside theme service: {p.relative_to(ROOT)}')


# Follow-up 01.09 — genre labels, SQLite contention and production logging.
require('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/LanguageCatalogService.java',
        'canonicalGenreCode(primary, fallbackCatalog, requestedCode)',
        'genreParents()', 'legacyBaseAliases()', 'shouldDisplayGenre')
require('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/service/GenreServiceImpl.java',
        'Lang/<language>.json', 'return loadCollectionGenres();')
require('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteBusyRetryExecutor.java',
        'ReentrantLock', 'MAX_ATTEMPTS', 'database is locked')
require('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteBookCommandRepository.java',
        'busyRetry.run("book storage update"')
require('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/config/DataSourceConfig.java',
        'PRAGMA busy_timeout=15000', 'initializeWalOnce(dataSource)')
require('myhomelib-bootstrap/src/main/resources/application.yml',
        'root: WARN', 'com.zaxxer.hikari: WARN', 'com.myhomelibcorp.infrastructure.download: WARN')
require('myhomelib-ui/src/main/resources/css/app-theme-base.css',
        '-fx-base: #ffffff', '-mhl-on-accent: #ffffff')

# P1 — export uses selection, physical resource, staging commit and final verification.
exp=require('myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/export/ExportToDeviceUseCase.java',
            'locateBookFile', 'createTempFile', 'ATOMIC_MOVE', 'getUsableSpace', 'verifyExportedFile', 'readBookData')
require('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/ExportController.java', 'bookSelectionService.snapshot()')

if failures:
    print('PLAN 2026-09-01 ACCEPTANCE CHECK: FAIL')
    for f in failures:
        print(' -',f)
    sys.exit(1)
print('PLAN 2026-09-01 ACCEPTANCE CHECK: PASS')
print('Covered: INPX safety, batch selection, Author Workspace, local state, theme, export staging.')
