#!/usr/bin/env python3
from pathlib import Path
import json,re,sys
ROOT=Path(__file__).resolve().parents[1]
BASE=json.loads((ROOT/'docs/release/FUNCTIONAL-UI-BASELINE-v7.1.json').read_text(encoding='utf-8'))
RES=ROOT/'myhomelib-ui/src/main/resources'

def fail(msg):
    print('FUNCTIONAL REGRESSION CHECK: FAIL -',msg); sys.exit(1)

files=[]; bindings=[]; ids=[]
for f in sorted(RES.rglob('*.fxml')):
    rel=str(f.relative_to(RES)).replace('\\','/')
    files.append(rel); t=f.read_text(encoding='utf-8')
    ids += [f'{rel}#{x}' for x in re.findall(r'fx:id="([A-Za-z_][A-Za-z0-9_]*)"',t)]
    for attr in ('onAction','onMouseClicked','onKeyPressed'):
        bindings += [f'{rel}#{attr}#{x}' for x in re.findall(attr+r'="#([A-Za-z_][A-Za-z0-9_]*)"',t)]
for label,expected,actual in [
    ('FXML files',BASE['fxml_files'],files),
    ('FXML action bindings',BASE['fxml_action_bindings'],sorted(set(bindings))),
    ('FXML ids',BASE['fxml_ids'],sorted(set(ids)))]:
    missing=sorted(set(expected)-set(actual))
    if missing: fail(f'{label} removed: {missing[:12]}')

checks={
'Online open confirmation': (
 ROOT/'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/BookDownloadCoordinator.java',
 ['ensureLocalForOpen(', 'Книга фізично відсутня на комп’ютері', 'showConfirmation(']),
'Open flows use confirmation': (
 ROOT/'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/MainBookCommandCoordinator.java',
 ['ensureLocalForOpen(selected)']),
'External open uses authoritative confirmation': (
 ROOT/'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/DefaultNavigationService.java',
 ['bookDownloadCoordinator.ensureLocalForOpen(book)', 'externalBookLauncher.open(book)']),
'Navigation read uses the single Reader guard': (
 ROOT/'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/DefaultNavigationService.java',
 ['workspaceManager.showNewReaderWorkspace(BookId.fromString(book.getId()))']),
'Reader entry point is centrally guarded': (
 ROOT/'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/navigation/WorkspaceManager.java',
 ['bookDownloadCoordinator.ensureLocalForOpen(bookId)', 'openNewReaderWorkspaceLocal(bookId)']),
'Author series grouping': (
 ROOT/'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/author/AuthorWorkspaceController.java',
 ['SortBy.SERIES', 'SeriesGrouping.groupPreservingOrder', 'onSortBySeries']),
'Series SQL sequence order': (
 ROOT/'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/helper/BookQueryBuilder.java',
 ['sortBy == SortBy.SERIES', 'b.sequence_number', "TRIM(COALESCE(b.series, ''))"]),
'ConnectionScript wizard': (
 ROOT/'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/CollectionWizardController.java',
 ['connectionScript']),
'Flibusta current protocol': (
 ROOT/'myhomelib-application/src/main/java/com/myhomelibcorp/application/catalog/CatalogSourceProfile.java',
 ['flibusta_online_fb2.info','flibusta_online_fb2.zip','extra_flibusta_online_fb2.info','extra_flibusta_online_fb2.zip']),
'Legacy ConnectionScript URL preamble': (
 ROOT/'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/scenario/DownloadScenarioParser.java',
 ['isLegacyUrlPreamble(raw)', 'scheme.equalsIgnoreCase("http")', 'scheme.equalsIgnoreCase("https")']),
'Remote catalog uses permanent book root': (
 ROOT/'myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/UpdateCollectionFromNetworkUseCase.java',
 ['onlineBookStorageRoot(active)', 'AppPaths.downloadsDir().resolve(collection.getId())']),
'Existing transient remote roots are repaired': (
 ROOT/'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/BookDownloadCoordinator.java',
 ['normalizeLegacyRemoteStorage(book)', 'isTransientCatalogRoot', '.myhomelibcorp', 'downloads']),
'Remote root repair is bounded SQL': (
 ROOT/'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteBookCommandRepository.java',
 ['repairTransientRemoteStorageRoots(String permanentRoot)', "WHERE local = 0", "LIKE '%/.myhomelibcorp/cache/catalog-updates%'"]),
'Lucene startup reuse without catalog-wide normalization': (
 ROOT/'myhomelib-application/src/main/java/com/myhomelibcorp/application/service/CollectionLifecycleService.java',
 ['searchIndexLifecycle.activateCollectionIndex(collection)', 'boolean shouldRebuild = rebuildIndex && !reusableIndex']),
'Collection folder chooser fallback': (
 ROOT/'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/CollectionPropertiesUiService.java',
 ['catch (IllegalArgumentException invalidInitialDirectory)', 'DirectoryChooser fallback=new DirectoryChooser()']),
'Reader quick-setting persistence': (
 ROOT/'myhomelib-reader/src/main/java/com/myhomelibcorp/reader/render/javafx/ReaderCanvas.java',
 ['cycleTheme()', 'notifySettingsChanged()', 'toggleTwoPageMode()', 'toggleAutoScroll()']),
'Reader nested TOC': (
 ROOT/'myhomelib-reader/src/main/java/com/myhomelibcorp/reader/format/fb2/Fb2StreamingParser.java',
 ['sections.size() + 1','addTocIfNeeded']),
'Table file size': (
 ROOT/'myhomelib-ui/src/main/resources/view/author-workspace.fxml',
 ['fx:id="fileSizeColumn"','text="Розмір"']),
'Downloaded-author update tracking': (
 ROOT/'myhomelib-application/src/main/java/com/myhomelibcorp/application/catalog/CatalogUpdateService.java',
 ['UPDATED_DOWNLOADED_BOOK', 'NEW_BY_FOLLOWED_AUTHOR']),
'Language text config': (
 ROOT/'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/LocalizationService.java',
 ['resolve("language.txt")'])
}
for label,(path,needles) in checks.items():
    if not path.exists(): fail(f'{label}: missing file {path.relative_to(ROOT)}')
    text=path.read_text(encoding='utf-8')
    missing=[n for n in needles if n not in text]
    if missing: fail(f'{label}: missing markers {missing}')
print('FUNCTIONAL REGRESSION CHECK: PASS')
print(f" - FXML files retained: {len(files)}")
print(f" - FXML bindings retained: {len(set(bindings))}")
print(f" - FXML ids retained: {len(set(ids))}")
print(f" - critical behavior ratchets: {len(checks)}")
