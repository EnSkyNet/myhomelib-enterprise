#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]

def text(rel):
    p = ROOT / rel
    if not p.exists():
        fail(f"missing file: {rel}")
    return p.read_text(encoding="utf-8")

def require(condition, msg):
    if not condition:
        fail(msg)

def fail(msg):
    print(f"FOLLOWUP NAV/DOWNLOAD/EXPORT CHECK: FAIL\n - {msg}")
    sys.exit(1)

mode = text("myhomelib-application/src/main/java/com/myhomelibcorp/application/navigation/NavigationMode.java")
nav_panel = text("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/navigation/NavigationPanelController.java")
main = text("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/MainController.java")
coord = text("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/BookDownloadCoordinator.java")
progress = text("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/BookDownloadProgressDialog.java")
update = text("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/CollectionUpdateUiService.java")
workspace = text("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/navigation/WorkspaceManager.java")
author = text("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/author/AuthorWorkspaceController.java")
facet = text("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteNavigationFacetRepository.java")
query = text("myhomelib-application/src/main/java/com/myhomelibcorp/application/navigation/DefaultNavigationQueryService.java")
nav_service = text("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/DefaultNavigationService.java")
export = text("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/ExportController.java")
layout = text("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/MainLayoutService.java")
reader = text("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java")
loader = text("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/FxmlLoaderFactory.java")

# Search state must survive storage/download refresh.
require("if (temporaryAuthorSearch) return;" in nav_panel, "author search is not protected from NavigationRefreshEvent")
start = main.find("public void handleDownloadBook()")
require(start >= 0, "MainController.handleDownloadBook missing")
end = main.find("\n    @FXML", start + 1)
chunk = main[start:end if end > start else start+350]
require("workspaceManager::refreshAfterStorageChange" in chunk, "download still does a global refresh instead of storage-only workspace refresh")
require("handleRefresh" not in chunk, "download still routes through global handleRefresh")

# One canonical batch download flow + progress UI.
require("downloadBatch(List<BookId> bookIds, Window owner)" in coord, "canonical batch download API missing")
require("BookDownloadProgressDialog" in coord, "batch download progress dialog not used")
require("Підключення" in progress and "Завантажено:" in progress and "Збережено до бібліотеки:" in progress,
        "download progress window does not expose connection/download/save states")
require("if (result.downloaded() > 0) eventPublisher.publishEvent(new NavigationRefreshEvent())" in coord,
        "batch download does not publish one post-batch navigation refresh")
require("download(book, false, showErrors, showErrors)" in coord, "batch path may still publish per-book navigation refresh")

# Automatic startup catalog update.
require("autoUpdateOnStartup" in update and 'online.autoUpdateOnStartup' in update,
        "automatic online catalog update at startup missing")
require("collectionUpdateUiService.autoUpdateOnStartup" in main, "startup auto-update is not wired into main shell")
require("executor.submit" in update[update.find("autoUpdateOnStartup"):], "startup update is not background/nonblocking")

# Downloaded books: nav -> authors -> filtered Author Workspace.
require("DOWNLOADED" in mode, "DOWNLOADED navigation mode missing")
require("case DOWNLOADED -> loadDownloadedAuthors" in query, "downloaded author query not wired")
require("findDownloadedAuthors" in facet and "COALESCE(b.local, 0) = 1" in facet, "downloaded author facet is not local-only")
require("CollectionType.REMOTE" in facet and "if (!online) return List.of();" in facet,
        "downloaded navigation is not limited to online collections")
require("case DOWNLOADED -> workspaceManager.showDownloadedAuthorWorkspace" in nav_service,
        "downloaded author selection does not open downloaded Author Workspace")
require("showDownloadedAuthorWorkspace" in workspace and "loadAuthorWorkspace(authorId, true)" in workspace,
        "WorkspaceManager downloaded-author route missing")
require("setDownloadedOnly" in author and 'downloadedOnly ? "Завантажені"' in author,
        "Author Workspace is not physically filtered to downloaded books")
require("loadAuthorWorkspace(AuthorId authorId, boolean downloadedOnly)" in loader,
        "FXML loader cannot configure downloaded-only Author Workspace")

# Column chooser belongs to active Author Workspace as well as classic table.
require("showColumnChooserForCurrentWorkspace" in workspace and "AuthorWorkspaceController" in workspace,
        "active Author Workspace column chooser routing missing")
require("public void showColumnChooser()" in author and "profileColumns.values()" in author,
        "Author Workspace column chooser missing")
require("workspaceManager.showColumnChooserForCurrentWorkspace()" in main,
        "View -> Show columns does not route through current workspace")

# Reader right sidebar must restore in-place and retain current book details.
require("node.setManaged(visible)" in layout and "Platform.runLater" in layout and "requestLayout" in layout,
        "sidebar restore does not force BorderPane relayout")
require("appState.getBookDetails().setCurrentBook(currentBook)" in reader,
        "Reader does not keep right book-details pane bound to the current book")

# Export must prepare remote books automatically before starting physical export.
export_on = export[export.find("@FXML private void onExport()"):export.find("private void startExportWorker")+50]
require("bookDownloadCoordinator.prepareForExport(selectedBookIds, stage)" in export_on,
        "export does not preflight/auto-download selected remote books")
require("startExportWorker(request)" in export_on,
        "export does not continue after successful auto-download")
require(export_on.find("prepareForExport") < export_on.find("startExportWorker(request)"),
        "physical export can start before preflight/auto-download completes")
require("if (preflight.missing().isEmpty())" in coord and "downloadBatch(preflight.missing(), owner)" in coord,
        "export preflight still opens download UI for already-local books")

print("FOLLOWUP NAV/DOWNLOAD/EXPORT CHECK: PASS")
print(" - author-search state preserved after download")
print(" - startup online catalog update wired in background")
print(" - batch download progress/summary contract present")
print(" - Downloaded Books navigation -> authors -> downloaded-only Author Workspace")
print(" - Author Workspace column chooser routed from View menu")
print(" - Reader sidebar restoration keeps current book details")
print(" - export auto-downloads remote books before device copy")
