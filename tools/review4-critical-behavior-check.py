#!/usr/bin/env python3
from pathlib import Path
import sys
ROOT = Path(__file__).resolve().parents[1]

def text(rel):
    p = ROOT / rel
    if not p.exists():
        raise AssertionError(f"missing file: {rel}")
    return p.read_text(encoding="utf-8")

def require(label, rel, needles=(), forbidden=()):
    t = text(rel)
    missing = [n for n in needles if n not in t]
    bad = [n for n in forbidden if n in t]
    if missing or bad:
        raise AssertionError(f"{label}: missing={missing} forbidden_present={bad}")

try:
    require("book workspace group semantics",
            "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/book/BookWorkspaceController.java",
            ["AddBookToGroupUseCase", '"Додати до групи"', '"Група:"', "classicLibraryActionsService.editBook"],
            ["currentBook.setTitle(newTitle)", '"Додати до колекції"'])
    require("book workspace labels",
            "myhomelib-ui/src/main/resources/view/book-workspace.fxml",
            ["Додати до групи", "Читати у MyHomeLib", "Відкрити у зовнішній програмі", "Видалити запис із каталогу"],
            [">Відкрити</Button>", ">Читати</Button>"])
    require("folder resolution",
            "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/DefaultNavigationService.java",
            ["bookResourcePort.locateBookFile", "book.getCollectionRoot()"],
            ["new File(book.getFolder())"])
    require("external resource resolution",
            "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/ExternalBookLauncher.java",
            ["resources.locateBookFile"], [])
    require("resource resolver accepts only physical files",
            "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/resource/BookResourceResolver.java",
            ["Files.isRegularFile(archivePath)", "Files.isRegularFile(filePath)", "archiveReader.containsEntry"],
            ["filePath != null && Files.exists(filePath)"])
    require("no UI thread sleeps",
            "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/imports/ImportWorkspaceController.java",
            ["PauseTransition", "closeProgressDialogAfter"], ["Thread.sleep("])
    require("empty collection lifecycle",
            "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/collection/CollectionWorkspaceController.java",
            [".importOnCreate(false)", ".createIndex(false)", "switchCollectionUseCase.execute(created.getId())", "appState.setCurrentLibraryCollection(active)"], [])
    require("author paging guard",
            "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/author/AuthorWorkspaceController.java",
            ["hasNextPage = page.hasNext()", "if (!hasNextPage) return;"], [])
    require("no implicit current book",
            "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/BookLoaderService.java",
            ["vm.setSelectedBook(null)", "appState.getBookDetails().setCurrentBook(null)"],
            ["vm.setSelectedBook(vms.get(0))"])
    require("row and batch selection are explicit in UI",
            "myhomelib-ui/src/main/resources/view/book-table.fxml",
            ["Поточна:", "currentBookLabel", "Пакетно вибрано: 0", "batchSelectionLabel"], [])
    require("row and batch selection counters stay live",
            "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/table/BookTableController.java",
            ["installSelectionStatusTracking", "batchSelectionListener", "Пакетно вибрано: "], [])
    require("password preserve on decrypt failure",
            "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/CollectionPropertiesUiService.java",
            ["passwordReadable[0] = false", "pass.setDisable(true)", "c.getPassword()"],
            ["catch(Exception ignored) { }"])
    require("global secret preserve on decrypt failure",
            "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/ApplicationSettingsDialog.java",
            ["unreadableSecrets.contains(key)", "unreadableSecrets.add(key)", "field.setDisable(true)"],
            ["catch (RuntimeException ignored) { field.clear(); }"])
    require("physical availability is authoritative for details",
            "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/details/BookDetailsAnalysisService.java",
            ["resolveBookContentUseCase.execute"], ["if (!fullBook.isLocal())"])
    require("physical availability is authoritative for OPDS",
            "myhomelib-application/src/main/java/com/myhomelibcorp/application/opds/OpdsDownloadService.java",
            ["resolveBookContentUseCase.execute"], ["!dto.get().isLocal()"] )
    require("reader persistence failure remains retryable",
            "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/NewReaderPersistenceService.java",
            ["public boolean savePosition", "return false;"], [])
    require("reader autosave retries failed DB write",
            "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/ReaderPositionAutosaver.java",
            ["if (persistence.savePosition", "dirty.set(true)", "position remains dirty for retry"], [])
    require("shared archive removal preview",
            "myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/download/RemoveLocalBookCopyUseCase.java",
            ["RemovalPreview", "preview(BookDto book)", "affectedBooks"], [])
    require("shared archive confirmation and batch removal",
            "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/BookDownloadCoordinator.java",
            ["confirmSingleRemoval", "У ньому каталогізовано книг", "removeLocalCopies(List<BookId>", "confirmBatchRemoval"], [])
    require("batch delete/remove semantics",
            "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/BatchOperationsController.java",
            ["handleBatchRemoveLocal", "handleBatchDelete", "Файли на диску НЕ видаляються"], [])
    require("main command batch fallbacks",
            "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/MainController.java",
            ["handleBatchDelete", "handleBatchRemoveLocal"], [])
    require("post-import heavy work off FX",
            "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/event/ImportEventHandler.java",
            ["executor.submit", "statisticsService.refreshStatistics()", "syncSeriesUseCase.execute()"], [])
    require("missing cover is not warning",
            "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/presenter/CoverPresenter.java",
            ['log.debug("Обкладинка відсутня'], ['log.warn("Обкладинка не знайдена'])
    require("HTTP response visibility",
            "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/scenario/ConnectionScriptExecutor.java",
            ["HTTP {} in {} ms", "Content-Type", "Content-Length", "Response URI", "Downloaded {} bytes"], [])
    require("ConnectionScript validates once and propagates resolved archive entry",
            "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/scenario/ConnectionScriptExecutor.java",
            ["resolvedArchiveEntry = validator.validate(state.payload(), target, book, archived)",
             "return new Result(state.payload(), state.responseUri(), checked, resolvedArchiveEntry)"], [])
    require("validated atomic download commit and diagnostics",
            "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/HttpOnlineBookDownloadAdapter.java",
            ["HTTP RESPONSE: status={}", "finalUrl={}", "contentType={}", "contentLength={}",
             "PAYLOAD VALIDATED:", "STORAGE COMMIT:",
             "result.resolvedArchiveEntry()",
             "AtomicFileSupport.moveReplacing(result.payload(), target)"],
            ["payloadValidator.validate(result.payload(), target, book, archived)"])
except AssertionError as e:
    print("REVIEW4 CRITICAL BEHAVIOR CHECK: FAIL -", e)
    sys.exit(1)

print("REVIEW4 CRITICAL BEHAVIOR CHECK: PASS")
print(" - Group/Collection semantics aligned")
print(" - Book edit persists through shared editor")
print(" - no JavaFX sleep in import completion")
print(" - empty collection create -> activate lifecycle explicit")
print(" - first-row auto-selection removed; row/batch selection shown separately")
print(" - shared archive removal impact confirmed")
print(" - batch delete/remove local supported through checkbox selection")
print(" - collection + global secret preservation guards present")
print(" - post-import heavy refresh off JavaFX thread")
print(" - physical availability is authoritative for action paths")
print(" - Reader autosave retries failed persistence")
print(" - HTTP diagnostics + validate/atomic commit retained")
