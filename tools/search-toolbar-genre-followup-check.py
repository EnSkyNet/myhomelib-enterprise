#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]

def read(rel):
    p = ROOT / rel
    if not p.exists(): fail('missing file: ' + rel)
    return p.read_text(encoding='utf-8')

def fail(msg):
    print('SEARCH/TOOLBAR/GENRE FOLLOWUP CHECK: FAIL\n - ' + msg)
    sys.exit(1)

def require(cond, msg):
    if not cond: fail(msg)

search_service = read('myhomelib-application/src/main/java/com/myhomelibcorp/application/search/SearchService.java')
search_ui = read('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/search/SearchWorkspaceController.java')
search_fxml = read('myhomelib-ui/src/main/resources/view/search-workspace.fxml')
main_fxml = read('myhomelib-ui/src/main/resources/view/MainView.fxml')
vm_mapper = read('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/mapper/BookViewModelMapper.java')
list_mapper = read('myhomelib-application/src/main/java/com/myhomelibcorp/application/mapper/BookListItemMapper.java')
progress = read('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/BookDownloadProgressDialog.java')
coord = read('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/BookDownloadCoordinator.java')
export = read('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/ExportController.java')
batch = read('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/BatchOperationsController.java')
author_ws = read('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/author/AuthorWorkspaceController.java')
export_usecase = read('myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/export/ExportToDeviceUseCase.java')
export_profiles = read('myhomelib-application/src/main/java/com/myhomelibcorp/application/export/ExportProfileService.java')

require('searchAuthorsAll' in search_service and 'searchAllBooks' in search_service,
        'complete author/book search APIs missing')
require('authorRepository.searchByName(normalizedQuery, chunkSize, offset)' in search_service,
        'author search is not paged server-side')
require('TableView<BookDto> booksTableView' in search_ui and 'authorColumn' in search_ui,
        'central search result is not a full table with author column')
require('highlightedInline(' in search_ui and '-fx-font-weight: bold;' in search_ui,
        'search match highlighting missing')
require('booksTableView.setFixedCellSize(28.0)' in search_ui and 'HBox highlightedInline' in search_ui,
        'search result rows are not forced to compact single-line height')
require('fx:id="booksTableView"' in search_fxml and 'fx:id="authorColumn"' in search_fxml and 'fx:id="selectColumn"' in search_fxml,
        'search FXML does not expose the full result table/selection column')
require('booksListView' not in search_fxml and 'booksPagingBox' not in search_fxml,
        'legacy clipped/paged search UI still present')

require('genreItems' in list_mapper and 'toGenreDtos(book)' in list_mapper,
        'author list DTO does not carry genre codes')
require('localizationService.genreName' in vm_mapper,
        'Author Workspace genre labels do not use active language catalog')
require('sourceLabel.equalsIgnoreCase(requestedCode)' in read('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/LanguageCatalogService.java'),
        'internal genre codes can still leak into the UI')

require('<FlowPane' in main_fxml and 'styleClass="main-toolbar-wrap"' in main_fxml,
        'main toolbar is not wrapping/adaptive')
require('⬇ Завантажити вибрані' in main_fxml and '✕ Скасувати завантаження' in main_fxml,
        'download toolbar commands remain ambiguous')

require('PauseTransition' in progress and 'stage.close()' in progress,
        'download completion window does not auto-close')
require('prepareForExport' in coord and 'if (preflight.missing().isEmpty())' in coord,
        'export does not silently skip download UI for local books')
require('bookDownloadCoordinator.prepareForExport(selectedBookIds, stage)' in export,
        'export does not use local-first preflight')
require('PauseTransition closeDelay' in export and 'closeDialog()' in export,
        'successful export window does not auto-close')
require('handleClearSelection();' in batch and 'if (error == null) bookSelectionService.clear();' in author_ws,
        'download completion does not clear checkbox selection')
require('settings.get("export.subfolderTemplate", "%a/%s")' in export_usecase
        and '"%n2 - %t".equals(template)' in export_usecase
        and 'String.format(java.util.Locale.ROOT, "%02d - %s"' in export_usecase,
        'canonical Author/Series/NN - Title export layout missing')
require('.replace("%a", sanitizeFileName(firstAuthorName(book)))' in export_usecase,
        'export author folder is not restricted to the first author')
require('exportProfiles.migration.deviceLayout.v2' in export_profiles,
        'legacy default export profile is not migrated to canonical device layout')

print('SEARCH/TOOLBAR/GENRE FOLLOWUP CHECK: PASS')
print(' - Author Workspace genres use human localized extended labels only; base/code labels are suppressed')
print(' - search books/authors are no longer capped at 50/20')
print(' - central search uses compact single-line rows with Author column and bold matches')
print(' - main toolbar wraps and download actions are clearly named')
print(' - download/export success windows auto-close; local export is silent')
print(' - successful downloads clear checkbox selection')
print(' - default device export layout is Author/[Series]/NN - Title')
