#!/usr/bin/env python3
"""Machine-checkable user-action coverage registry.

Default mode validates that every current FXML action and every programmatic setOnAction
registration has an explicit evidence entry.  --write refreshes the reviewed registry/report.
The registry stores handler/callback fingerprints so changed behavior requires an explicit review.
"""
from __future__ import annotations
import argparse, hashlib, json, re, sys, xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "myhomelib-ui"
RES = UI / "src/main/resources"
JAVA = UI / "src/main/java"
WEB = ROOT / "myhomelib-web"
REGISTRY = ROOT / "docs/current/UI-ACTION-COVERAGE.json"
REPORT = ROOT / "docs/current/UI-ACTION-COVERAGE-UA.md"
FXNS = "http://javafx.com/fxml/1"

EVIDENCE_BY_CONTROLLER = {
    "MainController": [
        "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/controller/MainControllerRefreshContractTest.java",
        "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/navigation/WorkspaceManagerNavigationStateTest.java",
        "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/navigation/MainNavigationCoordinatorAnnotationTest.java",
        "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/service/MainBookCommandCoordinatorSelectionTest.java",
        "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/controller/DeferredMainFeatureActionsTest.java",
        "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/controller/DeferredMainLibraryActionsTest.java",
        "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/service/ClassicLibraryActionsServiceLifecycleTest.java",
        "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/ExportBatchCompletionContractTest.java",
    ],
    "AnnotationManagerWorkspaceController": ["myhomelib-ui/src/test/java/com/myhomelibcorp/ui/annotation/AnnotationManagerUiContractTest.java", "myhomelib-application/src/test/java/com/myhomelibcorp/application/annotation/export/AnnotationExportServiceTest.java"],
    "IntegrityCheckController": ["myhomelib-ui/src/test/java/com/myhomelibcorp/ui/controller/LibraryHealthUiContractTest.java", "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/controller/ContentIndexHealthUiContractTest.java"],
    "CollectionWorkspaceController": ["myhomelib-application/src/test/java/com/myhomelibcorp/application/service/CollectionLifecycleServiceTest.java", "myhomelib-infrastructure/src/test/java/com/myhomelibcorp/infrastructure/collection/monitor/CollectionSourceMonitorAdapterTest.java", "myhomelib-library-organizer/src/test/java/com/myhomelibcorp/organizer/analysis/OrganizerMixedLibraryAcceptanceTest.java"],
    "AuthorWorkspaceController": ["myhomelib-application/src/test/java/com/myhomelibcorp/application/usecase/author/UpdateAuthorDescriptionUseCaseCollectionIsolationTest.java", "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/ExportBatchCompletionContractTest.java"],
    "NewReaderWorkspaceController": ["myhomelib-ui/src/test/java/com/myhomelibcorp/ui/reader/ReaderAnnotationUiContractTest.java", "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/reader/NewReaderSidebarSearchFxTest.java", "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/reader/ReaderBookmarkCoordinatorTest.java"],
    "BookWorkspaceController": ["myhomelib-ui/src/test/java/com/myhomelibcorp/ui/book/BookWorkspaceControllerAsyncTest.java", "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/service/MainBookCommandCoordinatorSelectionTest.java"],
    "GroupWorkspaceController": ["myhomelib-application/src/test/java/com/myhomelibcorp/application/usecase/group/GroupOperationExecutorLifecycleTest.java", "myhomelib-infrastructure/src/test/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteGroupRepositoryTest.java"],
    "ReadingListsWorkspaceController": ["myhomelib-ui/src/test/java/com/myhomelibcorp/ui/readinglist/ReadingListsWorkspaceActionContractTest.java"],
    "CollectionWizardController": ["myhomelib-application/src/test/java/com/myhomelibcorp/application/usecase/collection/CreateCollectionUseCaseRollbackTest.java", "myhomelib-application/src/test/java/com/myhomelibcorp/application/usecase/collection/AttachHlc2CollectionUseCaseSafetyTest.java"],
    "SearchWorkspaceController": ["myhomelib-ui/src/test/java/com/myhomelibcorp/ui/search/SearchWorkspaceControllerFxTest.java", "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/search/ContentGlobalSearchUiContractTest.java"],
    "ExportController": ["myhomelib-ui/src/test/java/com/myhomelibcorp/ui/ExportControllerSendToDeviceContractTest.java", "myhomelib-application/src/test/java/com/myhomelibcorp/application/usecase/export/ExportToDeviceUseCaseCrashSafetyTest.java"],
    "UpdatesWorkspaceController": ["myhomelib-application/src/test/java/com/myhomelibcorp/application/catalog/CatalogUpdateServiceTest.java", "myhomelib-infrastructure/src/test/java/com/myhomelibcorp/infrastructure/catalog/SqliteCatalogUpdateTrackingAdapterTest.java"],
    "BookDetailsController": ["myhomelib-ui/src/test/java/com/myhomelibcorp/ui/viewmodel/BookDetailsViewModelIdentityUpdateTest.java", "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/service/MainBookCommandCoordinatorSelectionTest.java"],
    "ImportWorkspaceController": ["myhomelib-application/src/test/java/com/myhomelibcorp/application/usecase/imports/ImportDirectoryUseCaseTest.java", "myhomelib-application/src/test/java/com/myhomelibcorp/application/usecase/imports/ImportFileUseCaseCancellationOutcomeTest.java", "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/imports/ImportFileChooserFiltersTest.java"],
    "BookTableController": ["myhomelib-ui/src/test/java/com/myhomelibcorp/ui/table/BookTableActivityFxTest.java", "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/ExportBatchCompletionContractTest.java"],
    "FollowedAuthorsWorkspaceController": ["myhomelib-application/src/test/java/com/myhomelibcorp/application/catalog/CatalogUpdateServiceTest.java", "myhomelib-infrastructure/src/test/java/com/myhomelibcorp/infrastructure/catalog/SqliteCatalogUpdateTrackingAdapterTest.java"],
    "TreeBookTableController": ["myhomelib-ui/src/test/java/com/myhomelibcorp/ui/ExportBatchCompletionContractTest.java", "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/table/SeriesGroupingTest.java"],
    "SavedSearchesController": ["myhomelib-application/src/test/java/com/myhomelibcorp/application/usecase/search/SavedSearchOperationExecutorTest.java", "myhomelib-application/src/test/java/com/myhomelibcorp/application/usecase/search/SmartCollectionUseCasesTest.java"],
    "BackupController": ["myhomelib-ui/src/test/java/com/myhomelibcorp/ui/controller/BackupRestoreControllerActionTest.java", "myhomelib-e2e-tests/src/test/java/com/myhomelibcorp/e2e/BackupRestoreJourneyE2ETest.java"],
    "RestoreController": ["myhomelib-ui/src/test/java/com/myhomelibcorp/ui/controller/BackupRestoreControllerActionTest.java", "myhomelib-e2e-tests/src/test/java/com/myhomelibcorp/e2e/BackupRestoreJourneyE2ETest.java"],
    "OperationCenterController": ["myhomelib-ui/src/test/java/com/myhomelibcorp/ui/operation/OperationCenterServiceTest.java", "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/operation/CoordinatedOperationCenterBridgeTest.java"],
    "SmartCollectionDialogController": ["myhomelib-application/src/test/java/com/myhomelibcorp/application/usecase/search/SmartCollectionUseCasesTest.java", "myhomelib-domain/src/test/java/com/myhomelibcorp/domain/model/search/SmartCollectionSpecTest.java"],
    "StatisticsController": ["myhomelib-application/src/test/java/com/myhomelibcorp/application/statistics/StatisticsServiceLifecycleTest.java", "myhomelib-infrastructure/src/test/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteStatisticsRepositoryTest.java"],
    "SearchDialogController": ["myhomelib-ui/src/test/java/com/myhomelibcorp/ui/reader/SearchDialogControllerFxTest.java"],
    "NavigationPanelController": ["myhomelib-application/src/test/java/com/myhomelibcorp/application/navigation/DefaultNavigationQueryServiceTest.java", "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/navigation/WorkspaceManagerNavigationStateTest.java"],
    "StatusBarController": ["myhomelib-ui/src/test/java/com/myhomelibcorp/ui/operation/OperationCenterServiceTest.java"],
    "TOCDialogController": ["myhomelib-reader/src/test/java/com/myhomelibcorp/reader/api/TocEntryTest.java", "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/reader/ReaderWorkspaceSidebarFxTest.java"],
}

EVIDENCE_BY_PROGRAMMATIC_CLASS = {
    "ActionRegistry": ["myhomelib-ui/src/test/java/com/myhomelibcorp/ui/action/BookMenuActionContextTest.java"],
    "BookActionProfilesDialog": ["myhomelib-application/src/test/java/com/myhomelibcorp/application/action/BookActionProfileServiceTest.java", "myhomelib-application/src/test/java/com/myhomelibcorp/application/action/BookActionExecutionServiceTest.java"],
    "BookActionUiService": ["myhomelib-application/src/test/java/com/myhomelibcorp/application/action/BookActionExecutionServiceTest.java"],
    "AnnotationEditorDialog": ["myhomelib-ui/src/test/java/com/myhomelibcorp/ui/reader/ReaderAnnotationUiContractTest.java"],
    "AuthorWorkspaceController": EVIDENCE_BY_CONTROLLER["AuthorWorkspaceController"],
    "CollectionWorkspaceController": EVIDENCE_BY_CONTROLLER["CollectionWorkspaceController"],
    "MainController": EVIDENCE_BY_CONTROLLER["MainController"],
    "SavedSearchesController": EVIDENCE_BY_CONTROLLER["SavedSearchesController"],
    "SmartCollectionDialogController": EVIDENCE_BY_CONTROLLER["SmartCollectionDialogController"],
    "DashboardController": ["myhomelib-application/src/test/java/com/myhomelibcorp/application/service/ReadingHistoryServiceTest.java", "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/navigation/WorkspaceManagerNavigationStateTest.java", "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/dashboard/DashboardNextInSeriesActionContractTest.java"],
    "BookDetailsController": EVIDENCE_BY_CONTROLLER["BookDetailsController"],
    "DuplicateReviewUiService": ["myhomelib-ui/src/test/java/com/myhomelibcorp/ui/duplicate/DuplicateReviewPresenterTest.java"],
    "GroupWorkspaceController": EVIDENCE_BY_CONTROLLER["GroupWorkspaceController"],
    "ReadingListBookActionUiService": ["myhomelib-ui/src/test/java/com/myhomelibcorp/ui/readinglist/ReadingListBookActionUiServiceTest.java"],
    "IntegrationCenterDialog": ["myhomelib-bootstrap/src/test/java/com/myhomelibcorp/integration/DesktopIntegrationBackendLazyInitializationTest.java", "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/integration/AiBookAssistantUiServiceUnicodeTest.java", "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/integration/IntegrationCenterKoboActionContractTest.java"],
    "BulkMetadataEditUiService": ["myhomelib-ui/src/test/java/com/myhomelibcorp/ui/metadata/BulkMetadataEditUiContractTest.java"],
    "AlphabetToolbarController": ["myhomelib-application/src/test/java/com/myhomelibcorp/application/navigation/DefaultNavigationQueryServiceTest.java"],
    "MainNavigationCoordinator": EVIDENCE_BY_CONTROLLER["MainController"],
    "OpdsUiService": ["myhomelib-ui/src/test/java/com/myhomelibcorp/ui/opds/OpdsTokenUiContractTest.java", "myhomelib-application/src/test/java/com/myhomelibcorp/application/opds/OpdsSettingsServiceTest.java"],
    "LibraryOrganizerUiService": ["myhomelib-library-organizer/src/test/java/com/myhomelibcorp/organizer/analysis/OrganizerMixedLibraryAcceptanceTest.java", "myhomelib-library-organizer/src/test/java/com/myhomelibcorp/organizer/apply/ApplyLibraryOrganizationUseCaseTest.java"],
    "ReaderAnnotationPopover": ["myhomelib-ui/src/test/java/com/myhomelibcorp/ui/reader/ReaderAnnotationUiContractTest.java"],
    "ReaderSettingsDialog": ["myhomelib-application/src/test/java/com/myhomelibcorp/application/reader/ReaderSettingsStateServiceTest.java", "myhomelib-reader/src/test/java/com/myhomelibcorp/reader/api/ReaderSettingsPresetsTest.java"],
    "SearchDialogController": EVIDENCE_BY_CONTROLLER["SearchDialogController"],
    "SearchWorkspaceController": EVIDENCE_BY_CONTROLLER["SearchWorkspaceController"],
    "ApplicationSettingsDialog": ["myhomelib-infrastructure/src/test/java/com/myhomelibcorp/infrastructure/settings/PropertiesApplicationSettingsServiceTest.java", "myhomelib-application/src/test/java/com/myhomelibcorp/application/port/out/settings/ApplicationSettingsPortTest.java"],
    "BookDownloadProgressDialog": ["myhomelib-infrastructure/src/test/java/com/myhomelibcorp/infrastructure/download/DownloadDecisionIntegrationTest.java", "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/book/BookWorkspaceControllerAsyncTest.java"],
    "CatalogUpdateProgressDialog": ["myhomelib-application/src/test/java/com/myhomelibcorp/application/catalog/CatalogUpdateServiceTest.java"],
    "CollectionPropertiesUiService": ["myhomelib-ui/src/test/java/com/myhomelibcorp/ui/controller/DeferredMainFeatureActionsTest.java", "myhomelib-application/src/test/java/com/myhomelibcorp/application/service/CollectionLifecycleServiceTest.java"],
    "BookTableController": EVIDENCE_BY_CONTROLLER["BookTableController"],
    "TreeBookTableController": EVIDENCE_BY_CONTROLLER["TreeBookTableController"],
    "AudioReaderView": ["myhomelib-reader/src/test/java/com/myhomelibcorp/reader/render/javafx/ReaderProgrammaticActionContractTest.java", "myhomelib-application/src/test/java/com/myhomelibcorp/application/reader/AudiobookTrackGroupingTest.java", "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/reader/NewReaderAudiobookPersistenceTest.java"],
    "ComicReaderView": ["myhomelib-reader/src/test/java/com/myhomelibcorp/reader/render/javafx/ReaderProgrammaticActionContractTest.java", "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/ComicReaderWorkflowContractTest.java", "myhomelib-reader/src/test/java/com/myhomelibcorp/reader/render/comic/ComicReaderSourceContractTest.java"],
    "ReaderCanvas": ["myhomelib-ui/src/test/java/com/myhomelibcorp/ui/reader/ReaderTextProviderUiContractTest.java", "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/reader/ReaderAnnotationUiContractTest.java"],
    "ReaderToolbar": ["myhomelib-reader/src/test/java/com/myhomelibcorp/reader/render/javafx/ReaderProgrammaticActionContractTest.java", "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/reader/ReaderAnnotationUiContractTest.java", "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/reader/ReaderWorkspaceSidebarFxTest.java"],
    "PdfReaderView": ["myhomelib-reader/src/test/java/com/myhomelibcorp/reader/render/javafx/ReaderProgrammaticActionContractTest.java", "myhomelib-ui/src/test/java/com/myhomelibcorp/ui/PdfReaderWorkflowContractTest.java", "myhomelib-reader/src/test/java/com/myhomelibcorp/reader/render/pdf/PdfReaderSourceContractTest.java"],
}


# Canonical user-facing documentation flow ids.  These ids are intentionally coarse-grained:
# many UI controls are different entry points into the same documented user flow.
PROGRAMMATIC_FUNCTION_REF = {
    "ActionRegistry": "book-actions",
    "AlphabetToolbarController": "catalog",
    "AnnotationEditorDialog": "annotations",
    "ApplicationSettingsDialog": "settings",
    "AudioReaderView": "audio-reader",
    "AuthorWorkspaceController": "authors-updates",
    "BookActionProfilesDialog": "book-actions",
    "BookActionUiService": "book-actions",
    "BookDetailsController": "book-metadata",
    "BookDownloadProgressDialog": "online-download",
    "BookTableController": "catalog",
    "BulkMetadataEditUiService": "book-metadata",
    "CatalogUpdateProgressDialog": "authors-updates",
    "CollectionPropertiesUiService": "collections",
    "CollectionWorkspaceController": "collections",
    "ComicReaderView": "comic-reader",
    "DashboardController": "main-navigation",
    "DuplicateReviewUiService": "duplicates",
    "GroupWorkspaceController": "groups",
    "ReadingListBookActionUiService": "reading-lists",
    "IntegrationCenterDialog": "plugins-ai",
    "LibraryOrganizerUiService": "organizer",
    "MainNavigationCoordinator": "main-navigation",
    "OpdsUiService": "opds",
    "PdfReaderView": "pdf-reader",
    "ReaderAnnotationPopover": "annotations",
    "ReaderCanvas": "reader",
    "ReaderSettingsDialog": "reader",
    "ReaderToolbar": "reader",
    "SavedSearchesController": "search",
    "SearchDialogController": "reader",
    "SearchWorkspaceController": "search",
    "SmartCollectionDialogController": "search",
    "TreeBookTableController": "catalog",
}

FXML_FUNCTION_REF = {
    "AnnotationManagerWorkspaceController": "annotations",
    "IntegrityCheckController": "health-stats",
    "CollectionWorkspaceController": "collections",
    "AuthorWorkspaceController": "authors-updates",
    "NewReaderWorkspaceController": "reader",
    "BookWorkspaceController": "book-metadata",
    "GroupWorkspaceController": "groups",
    "ReadingListsWorkspaceController": "reading-lists",
    "CollectionWizardController": "collections",
    "SearchWorkspaceController": "search",
    "ExportController": "export-device",
    "UpdatesWorkspaceController": "authors-updates",
    "BookDetailsController": "book-metadata",
    "ImportWorkspaceController": "import",
    "BookTableController": "catalog",
    "FollowedAuthorsWorkspaceController": "authors-updates",
    "TreeBookTableController": "catalog",
    "SavedSearchesController": "search",
    "BackupController": "backup-restore",
    "RestoreController": "backup-restore",
    "OperationCenterController": "operations",
    "SmartCollectionDialogController": "search",
    "StatisticsController": "health-stats",
    "SearchDialogController": "reader",
    "NavigationPanelController": "main-navigation",
    "StatusBarController": "operations",
    "TOCDialogController": "reader",
}


def main_controller_function_ref(action: str) -> str:
    a = action.lower()
    checks = [
        (("about", "help"), "localization-help"),
        (("ai", "integrations"), "plugins-ai"),
        (("organize",), "organizer"),
        (("convert",), "conversion"),
        (("backup", "restore", "userdata"), "backup-restore"),
        (("syncfolder",), "sync"),
        (("opds",), "opds"),
        (("duplicate",), "duplicates"),
        (("integrity", "rebuildindex", "vacuum", "statistics"), "health-stats"),
        (("annotation",), "annotations"),
        (("followedauthor", "updates"), "authors-updates"),
        (("readinglist",), "reading-lists"),
        (("group",), "groups"),
        (("collection",), "collections"),
        (("import", "addbook", "inpxhelp"), "import"),
        (("download", "removelocalcopy"), "online-download"),
        (("export",), "export-device"),
        (("metadata", "customfield"), "book-metadata"),
        (("batch", "clearselection"), "batch"),
        (("bookactions", "customizeactions"), "book-actions"),
        (("opennewreader", "openexternalreader", "closereader"), "reader"),
        (("search",), "search"),
        (("operation", "undolastlibraryoperation"), "operations"),
        (("settings", "theme"), "settings"),
        (("allbooks", "alreadyread", "authors", "genres", "history", "newbooks", "series", "deletebook"), "catalog"),
        (("columns", "sidebar", "toggleview", "back", "forward", "home", "refresh", "resetnavigation", "exit"), "main-navigation"),
    ]
    for needles, ref in checks:
        if any(n in a for n in needles):
            return ref
    raise AssertionError(f"MainController action has no user-function mapping: {action}")


def user_function_ref(controller: str, action: str, kind: str) -> str:
    cname = controller.split(".")[-1]
    if cname == "MainController":
        return main_controller_function_ref(action)
    if cname == "IntegrationCenterDialog" and kind == "PROGRAMMATIC":
        receiver = action.split(".setOnAction", 1)[0]
        if receiver in {"enableRotate", "copy", "syncNow", "revoke"}:
            return "kobo-sync"
    mapping = FXML_FUNCTION_REF if kind == "FXML" else PROGRAMMATIC_FUNCTION_REF
    ref = mapping.get(cname)
    if not ref:
        raise AssertionError(f"no user-function mapping for {kind} controller/class {cname}: {action}")
    return ref


def sha(text: str) -> str:
    return hashlib.sha256(re.sub(r"\s+", " ", text).strip().encode("utf-8")).hexdigest()


def method_body(source: str, name: str) -> str:
    m = re.search(r"\b" + re.escape(name) + r"\s*\([^)]*\)\s*\{", source)
    if not m:
        raise AssertionError(f"cannot locate handler body: {name}")
    start = source.find("{", m.start()); depth = 0
    for i in range(start, len(source)):
        if source[i] == "{": depth += 1
        elif source[i] == "}":
            depth -= 1
            if depth == 0: return source[start + 1:i]
    raise AssertionError(f"unterminated handler body: {name}")


def ui_label(element: ET.Element) -> str:
    for key in ("text", "promptText", "accessibleText"):
        value = element.attrib.get(key, "").strip()
        if value: return value
    return element.tag.split("}")[-1]


def expectation(name: str, label: str) -> str:
    low = name.lower()
    pairs = [
        (("cancel",), "скасовує активну операцію або закриває незбережений flow"),
        (("close",), "закриває поточний діалог/workspace відповідно до контракту"),
        (("delete", "remove"), "виконує контрольоване видалення/прибирання без прихованого overwrite"),
        (("export",), "запускає відповідний export flow і публікує результат через перевірений boundary"),
        (("import",), "запускає відповідний import flow з validation/cancellation contract"),
        (("search", "filter"), "оновлює пошук/фільтрацію та видимий результат"),
        (("refresh", "reload", "rebuild"), "оновлює відповідний стан/індекс без дублювання операцій"),
        (("open", "read", "show"), "відкриває відповідний workspace/dialog/reader для поточного context"),
        (("next", "forward"), "переходить до наступного кроку/сторінки за валідних передумов"),
        (("previous", "back"), "повертає до попереднього кроку/стану"),
        (("save", "finish", "apply", "update"), "валідує та застосовує/зберігає зміну"),
        (("add", "create", "new"), "створює/додає сутність через відповідний use-case"),
        (("choose", "browse", "select"), "відкриває контрольований вибір та переносить вибране значення в UI state"),
        (("toggle", "cycle"), "детерміновано перемикає заявлений UI/state параметр"),
        (("clear", "reset"), "очищує/скидає заявлений стан без побічної мутації інших контекстів"),
    ]
    for keys, result in pairs:
        if any(k in low for k in keys): return result
    return f"виконує заявлений UI flow «{label}» через перевірений controller/delegate contract"


def direct_tests(controller: str, handler: str) -> list[str]:
    result = []
    cname = controller.split(".")[-1]
    for base in (ROOT / "myhomelib-ui/src/test/java", ROOT / "myhomelib-e2e-tests/src/test/java"):
        if not base.exists(): continue
        for path in base.rglob("*.java"):
            text = path.read_text(encoding="utf-8", errors="ignore")
            if cname in text and (re.search(r"\b" + re.escape(handler) + r"\s*\(", text) or f'"{handler}"' in text):
                result.append(str(path.relative_to(ROOT)).replace("\\", "/"))
    return sorted(result)


def fxml_inventory() -> list[dict]:
    entries = []
    for fxml in sorted(RES.rglob("*.fxml")):
        root = ET.parse(fxml).getroot()
        controller = root.attrib.get(f"{{{FXNS}}}controller", "").strip()
        if not controller: continue
        controller_file = JAVA / Path(*controller.split(".")).with_suffix(".java")
        source = controller_file.read_text(encoding="utf-8")
        cname = controller.split(".")[-1]
        action_elements = []
        for element in root.iter():
            for event in ("onAction", "onMouseClicked", "onKeyPressed"):
                value = element.attrib.get(event, "")
                if value.startswith("#"):
                    action_elements.append((element, event, value[1:]))
        if action_elements and cname not in EVIDENCE_BY_CONTROLLER:
            raise AssertionError(f"no reviewed evidence mapping for FXML controller {cname}")
        for binding_index, (element, event, handler) in enumerate(action_elements, start=1):
            label = ui_label(element)
            body = method_body(source, handler)
            direct = direct_tests(controller, handler)
            evidence = direct or EVIDENCE_BY_CONTROLLER[cname]
            key = f"FXML|{fxml.relative_to(RES).as_posix()}|{controller}|{event}|{handler}|{binding_index}"
            entries.append({
                "key": key, "kind": "FXML", "surface": fxml.relative_to(RES).as_posix(),
                "controller": controller, "action": handler, "control": element.tag.split("}")[-1],
                "label": label, "preconditions": "відповідний context/selection; disabled-state перевіряється UI/feature contract",
                "expected_result": expectation(handler, label),
                "user_function_ref": user_function_ref(controller, handler, "FXML"),
                "coverage": "AUTOMATED_DIRECT" if direct else "AUTOMATED_DELEGATE_CONTRACT",
                "evidence": evidence, "handler_body_sha256": sha(body),
            })
    return entries


def callback_text(source: str, match_end: int) -> str:
    start = source.find("(", match_end - 1); depth = 0
    if start < 0: return ""
    quote = None; escape = False
    for i in range(start, len(source)):
        ch = source[i]
        if quote:
            if escape: escape = False
            elif ch == "\\": escape = True
            elif ch == quote: quote = None
            continue
        if ch in ('"', "'"): quote = ch; continue
        if ch == "(": depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0: return source[start + 1:i]
    return source[start + 1:]


def programmatic_inventory() -> list[dict]:
    entries = []
    for base in (ROOT / "myhomelib-ui/src/main/java", ROOT / "myhomelib-reader/src/main/java"):
        for path in sorted(base.rglob("*.java")):
            source = path.read_text(encoding="utf-8", errors="ignore")
            matches = list(re.finditer(r"([A-Za-z_][A-Za-z0-9_]*)\.setOnAction\s*\(", source))
            if not matches: continue
            cname = path.stem
            if cname not in EVIDENCE_BY_PROGRAMMATIC_CLASS:
                raise AssertionError(f"no reviewed evidence mapping for programmatic action class {cname}")
            receiver_counts: dict[str, int] = {}
            for match in matches:
                receiver = match.group(1); receiver_counts[receiver] = receiver_counts.get(receiver, 0) + 1
                ordinal = receiver_counts[receiver]
                callback = callback_text(source, match.end())
                rel = path.relative_to(ROOT).as_posix()
                key = f"PROGRAMMATIC|{rel}|{receiver}|{ordinal}"
                entries.append({
                    "key": key, "kind": "PROGRAMMATIC", "surface": rel, "controller": cname,
                    "action": f"{receiver}.setOnAction[{ordinal}]", "control": receiver, "label": receiver,
                    "preconditions": "контекст, заданий діалогом/reader/service; boundary тестується без реальної зовнішньої мутації",
                    "expected_result": f"callback елемента «{receiver}» виконує заявлений flow класу {cname}",
                    "user_function_ref": user_function_ref(cname, f"{receiver}.setOnAction[{ordinal}]", "PROGRAMMATIC"),
                    "coverage": "AUTOMATED_DELEGATE_CONTRACT", "evidence": EVIDENCE_BY_PROGRAMMATIC_CLASS[cname],
                    "callback_sha256": sha(callback),
                })
    return entries


WEB_ACTION_EVIDENCE = {
    "offline-save": [
        "myhomelib-web/src/test/java/com/myhomelibcorp/web/WebPwaAssetsTest.java",
        "myhomelib-opds/src/test/java/com/myhomelibcorp/opds/JdkOpdsServerTest.java",
    ],
    "offline-remove": [
        "myhomelib-web/src/test/java/com/myhomelibcorp/web/WebPwaAssetsTest.java",
        "myhomelib-web/src/test/java/com/myhomelibcorp/web/WebLibraryRendererTest.java",
        "myhomelib-web/src/test/java/com/myhomelibcorp/web/WebReaderRendererTest.java",
    ],
    "offline-clear": [
        "myhomelib-web/src/test/java/com/myhomelibcorp/web/WebPwaAssetsTest.java",
        "myhomelib-web/src/test/java/com/myhomelibcorp/web/WebLibraryRendererTest.java",
        "myhomelib-web/src/test/java/com/myhomelibcorp/web/WebReaderRendererTest.java",
    ],
}

WEB_MARKERS = {
    "data-pwa-save-book": ("offline-save", "web.offline.save", "кешує підтримувану книгу лише після явної browser-дії"),
    "data-pwa-remove-book": ("offline-remove", "web.offline.remove", "видаляє explicit offline cache лише поточної книги"),
    "data-pwa-clear-books": ("offline-clear", "web.offline.clear", "очищує всі явно збережені offline book caches"),
}

def web_inventory() -> list[dict]:
    entries = []
    pwa_source = (WEB / "src/main/java/com/myhomelibcorp/web/WebPwaAssets.java").read_text(encoding="utf-8")
    fingerprint = sha(pwa_source)
    for path in [
        WEB / "src/main/java/com/myhomelibcorp/web/WebLibraryRenderer.java",
        WEB / "src/main/java/com/myhomelibcorp/web/WebReaderRenderer.java",
    ]:
        source = path.read_text(encoding="utf-8")
        rel = path.relative_to(ROOT).as_posix()
        cname = path.stem
        for marker, (action, label, expected) in WEB_MARKERS.items():
            for ordinal, _ in enumerate(re.finditer(re.escape(marker), source), start=1):
                entries.append({
                    "key": f"WEB|{rel}|{action}|{ordinal}",
                    "kind": "WEB",
                    "surface": rel,
                    "controller": cname,
                    "action": action,
                    "control": marker,
                    "label": label,
                    "preconditions": "authenticated Web Library; secure context/loopback PWA; supported Web Reader book для save",
                    "expected_result": expected,
                    "user_function_ref": "web-offline",
                    "coverage": "AUTOMATED_DIRECT",
                    "evidence": WEB_ACTION_EVIDENCE[action],
                    "callback_sha256": sha(action + "|" + marker + "|" + fingerprint),
                })
    return entries

ASSERTION_RE = re.compile(
    r"\b(?:assertThat|assertEquals|assertNotEquals|assertTrue|assertFalse|assertNull|assertNotNull|"
    r"assertThrows|assertDoesNotThrow|assertTimeout|verify|verifyNoInteractions|verifyNoMoreInteractions|fail)\s*\("
)

def behavioral_evidence(path: Path) -> bool:
    text = path.read_text(encoding="utf-8", errors="ignore")
    return "@Test" in text and ASSERTION_RE.search(text) is not None

def collect() -> list[dict]:
    entries = fxml_inventory() + programmatic_inventory() + web_inventory()
    keys = [e["key"] for e in entries]
    if len(keys) != len(set(keys)): raise AssertionError("duplicate action coverage keys")
    for e in entries:
        if not e["evidence"]: raise AssertionError(f"missing evidence: {e['key']}")
        evidence_paths = []
        for evidence in e["evidence"]:
            evidence_path = ROOT / evidence
            if not evidence_path.exists(): raise AssertionError(f"missing evidence file {evidence} for {e['key']}")
            evidence_paths.append(evidence_path)
        if not any(behavioral_evidence(path) for path in evidence_paths):
            raise AssertionError(f"evidence has no observable assertion/verification: {e['key']}")
        fingerprint = e.get("handler_body_sha256") or e.get("callback_sha256")
        if not fingerprint or fingerprint == sha(""):
            raise AssertionError(f"empty/unfingerprinted action body: {e['key']}")
        e["behavioral_evidence"] = True
    return sorted(entries, key=lambda e: e["key"])


def write(entries: list[dict]) -> None:
    REGISTRY.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "schema": 2, "checkpoint": "V26", "product_version": "8.0.0",
        "contract": "Every current user-triggerable FXML/programmatic JavaFX or Web Library action has explicit reviewed automated evidence; handler/callback fingerprints force review after behavioral code changes.",
        "summary": {
            "actions": len(entries), "fxml_actions": sum(e["kind"] == "FXML" for e in entries),
            "programmatic_actions": sum(e["kind"] == "PROGRAMMATIC" for e in entries),
            "web_actions": sum(e["kind"] == "WEB" for e in entries),
            "direct_fxml_actions": sum(e["coverage"] == "AUTOMATED_DIRECT" for e in entries),
            "delegate_contract_actions": sum(e["coverage"] == "AUTOMATED_DELEGATE_CONTRACT" for e in entries),
            "behavioral_evidence_actions": sum(bool(e.get("behavioral_evidence")) for e in entries),
            "unmapped": 0,
        },
        "entries": entries,
    }
    REGISTRY.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    lines = [
        "# MyHomeLib — покриття користувацьких UI-дій", "", "Дата актуалізації: 2026-09-29", "",
        "Цей звіт є machine-checkable реєстром інтерактивних точок входу. FXML wiring перевіряється структурно; для кожної дії зафіксовано конкретний behavioral/delegate test evidence. Зміна тіла handler/callback змінює fingerprint і вимагає повторного review — нова дія не може з'явитися непомітно.", "",
        f"Поточний inventory: **{len(entries)}** дій: **{sum(e['kind']=='FXML' for e in entries)} FXML** + **{sum(e['kind']=='PROGRAMMATIC' for e in entries)} programmatic `setOnAction`** + **{sum(e['kind']=='WEB' for e in entries)} Web Library actions**; unmapped: **0**; entries із behavioral assertion/verification evidence: **{sum(bool(e.get('behavioral_evidence')) for e in entries)}/{len(entries)}**.", "",
        "`AUTOMATED_DIRECT` означає пряме викликання handler у тесті. `AUTOMATED_DELEGATE_CONTRACT` означає: FXML/callback binding + незмінний fingerprint + observable contract делегованого controller/service/use-case. Для кожної entry gate додатково вимагає щонайменше один mapped test з реальною assertion/Mockito verification. Це навмисно уникає сотень дублюючих shallow-click тестів, але не видає structural wiring за live platform acceptance.", "",
        "| Surface | Action | Control | Preconditions | Expected result | Evidence | Status |", "|---|---|---|---|---|---|---|"
    ]
    for e in entries:
        ev = "<br>".join(f"`{Path(x).name}`" for x in e["evidence"][:3])
        esc = lambda x: str(x).replace("|", "\\|").replace("\n", " ")
        lines.append(f"| `{esc(e['surface'])}` | `{esc(e['action'])}` | `{esc(e['control'])}` | {esc(e['preconditions'])} | {esc(e['expected_result'])} | {ev} | `{e['coverage']}` |")
    REPORT.write_text("\n".join(lines) + "\n", encoding="utf-8")


def validate(current: list[dict]) -> None:
    if not REGISTRY.exists(): raise AssertionError(f"coverage registry missing: {REGISTRY.relative_to(ROOT)}")
    saved = json.loads(REGISTRY.read_text(encoding="utf-8"))
    old = {e["key"]: e for e in saved.get("entries", [])}; now = {e["key"]: e for e in current}
    missing = sorted(set(now) - set(old)); stale = sorted(set(old) - set(now))
    if missing or stale: raise AssertionError(f"UI action inventory drift: missing coverage={missing[:10]}, stale coverage={stale[:10]}")
    for key, entry in now.items():
        saved_entry = old[key]
        fingerprint = "handler_body_sha256" if entry["kind"] == "FXML" else "callback_sha256"
        if saved_entry.get(fingerprint) != entry.get(fingerprint):
            raise AssertionError(f"UI action behavior changed without coverage review: {key}")
        if saved_entry.get("user_function_ref") != entry.get("user_function_ref"):
            raise AssertionError(f"UI action documentation mapping changed without coverage review: {key}")
        if not saved_entry.get("evidence"): raise AssertionError(f"coverage evidence missing: {key}")
        for evidence in saved_entry["evidence"]:
            if not (ROOT / evidence).exists(): raise AssertionError(f"coverage evidence file missing: {evidence}")
    summary = saved.get("summary", {})
    if (summary.get("actions") != len(current) or summary.get("unmapped") != 0
            or summary.get("behavioral_evidence_actions") != len(current)):
        raise AssertionError("coverage summary is stale")


def main() -> int:
    parser = argparse.ArgumentParser(); parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    try:
        entries = collect()
        if args.write:
            write(entries); print(f"WROTE UI ACTION COVERAGE: {len(entries)} actions")
        validate(entries)
        print(f"PASS: UI action coverage {len(entries)}/{len(entries)} mapped; FXML={sum(e['kind']=='FXML' for e in entries)}, programmatic={sum(e['kind']=='PROGRAMMATIC' for e in entries)}, web={sum(e['kind']=='WEB' for e in entries)}")
        return 0
    except Exception as exc:
        print(f"FAIL: {exc}")
        return 1

if __name__ == "__main__": raise SystemExit(main())
