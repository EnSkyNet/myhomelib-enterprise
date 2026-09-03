#!/usr/bin/env python3
"""Offline architecture guard for MyHomeLib Enterprise.

This script intentionally uses only the Python standard library so it can run
before Maven dependencies are available. It checks the declared Maven module
graph, source-level cross-module references, hard framework boundaries and two
known UI architecture-debt ratchets.
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}

MODULES = {
    "myhomelib-shared": "shared",
    "myhomelib-domain": "domain",
    "myhomelib-application": "application",
    "myhomelib-infrastructure": "infrastructure",
    "myhomelib-reader": "reader",
    "myhomelib-ui": "ui",
    "myhomelib-bootstrap": "bootstrap",
    "myhomelib-mcp": "mcp",
    "myhomelib-opds": "opds",
}

PACKAGE_TO_MODULE = {
    "shared": "myhomelib-shared",
    "domain": "myhomelib-domain",
    "application": "myhomelib-application",
    "infrastructure": "myhomelib-infrastructure",
    "reader": "myhomelib-reader",
    "ui": "myhomelib-ui",
    "mcp": "myhomelib-mcp",
    "opds": "myhomelib-opds",
}

# Direct production dependencies that are allowed and expected after Stage 1.
EXPECTED_INTERNAL_DEPS = {
    "myhomelib-shared": set(),
    "myhomelib-domain": {"myhomelib-shared"},
    "myhomelib-application": {"myhomelib-shared", "myhomelib-domain"},
    "myhomelib-infrastructure": {"myhomelib-shared", "myhomelib-domain", "myhomelib-application"},
    "myhomelib-reader": {"myhomelib-shared"},
    "myhomelib-ui": {"myhomelib-shared", "myhomelib-domain", "myhomelib-application", "myhomelib-reader"},
    "myhomelib-bootstrap": {
        "myhomelib-shared",
        "myhomelib-domain",
        "myhomelib-application",
        "myhomelib-infrastructure",
        "myhomelib-ui",
        "myhomelib-opds",
    },
    "myhomelib-mcp": {"myhomelib-shared"},
    "myhomelib-opds": {"myhomelib-application"},
}

# Existing debt is a ceiling, not a requirement. Removing an item is always OK;
# adding a new item fails the check until architecture is intentionally revised.
UI_OUTPUT_PORT_BASELINE = {
    "com.myhomelibcorp.ui.controller.CollectionWizardController",
    "com.myhomelibcorp.ui.controller.GroupController",
    "com.myhomelibcorp.ui.presenter.CoverPresenter",
    "com.myhomelibcorp.ui.reader.NewReaderPersistenceService",
    "com.myhomelibcorp.ui.reader.NewReaderWorkspaceController",
    "com.myhomelibcorp.ui.service.ApplicationSettingsDialog",
    "com.myhomelibcorp.ui.service.BookDownloadCoordinator",
    "com.myhomelibcorp.ui.service.ClassicLibraryActionsService",
    "com.myhomelibcorp.ui.service.CollectionAttachUiService",
    "com.myhomelibcorp.ui.service.CollectionCopyUiService",
    "com.myhomelibcorp.ui.service.CollectionPropertiesUiService",
    "com.myhomelibcorp.ui.service.CollectionUpdateUiService",
    "com.myhomelibcorp.ui.service.DefaultNavigationService",
    "com.myhomelibcorp.ui.service.ExternalBookLauncher",
    "com.myhomelibcorp.ui.service.LocalizationService",
    "com.myhomelibcorp.ui.service.SupportBundleService",
    "com.myhomelibcorp.ui.service.UserDataUiService",
    "com.myhomelibcorp.ui.table.TreeBookTableController",
}

UI_DOMAIN_MODEL_BASELINE = {
    "com.myhomelibcorp.ui.controller.BackupController",
    "com.myhomelibcorp.ui.controller.CollectionController",
    "com.myhomelibcorp.ui.controller.CollectionWizardController",
    "com.myhomelibcorp.ui.controller.DatabaseToolsController",
    "com.myhomelibcorp.ui.controller.GroupController",
    "com.myhomelibcorp.ui.controller.ImportController",
    "com.myhomelibcorp.ui.controller.MainController",
    "com.myhomelibcorp.ui.controller.SavedSearchesController",
    "com.myhomelibcorp.ui.collection.CollectionWorkspaceController",
    "com.myhomelibcorp.ui.event.CollectionChangedEvent",
    "com.myhomelibcorp.ui.group.GroupWorkspaceController",
    "com.myhomelibcorp.ui.navigation.WorkspaceManager",
    "com.myhomelibcorp.ui.presenter.CollectionPresenter",
    "com.myhomelibcorp.ui.presenter.GroupPresenter",
    "com.myhomelibcorp.ui.reader.NewReaderPersistenceService",
    "com.myhomelibcorp.ui.reader.NewReaderWorkspaceController",
    "com.myhomelibcorp.ui.reader.ReaderSettingsMapper",
    "com.myhomelibcorp.ui.service.BookDownloadCoordinator",
    "com.myhomelibcorp.ui.service.ClassicLibraryActionsService",
    "com.myhomelibcorp.ui.service.CollectionCopyUiService",
    "com.myhomelibcorp.ui.service.CollectionPropertiesUiService",
    "com.myhomelibcorp.ui.service.CollectionUpdateUiService",
    "com.myhomelibcorp.ui.service.DefaultNavigationService",
    "com.myhomelibcorp.ui.service.DialogService",
    "com.myhomelibcorp.ui.service.FxmlLoaderFactory",
    "com.myhomelibcorp.ui.table.TreeBookTableController",
    "com.myhomelibcorp.ui.viewmodel.ApplicationState",
    "com.myhomelibcorp.ui.viewmodel.CollectionWizardViewModel",
}

errors: list[str] = []
notes: list[str] = []


def fail(message: str) -> None:
    errors.append(message)


def production_internal_dependencies(module_dir: str) -> set[str]:
    pom = ET.parse(ROOT / module_dir / "pom.xml").getroot()
    result: set[str] = set()
    for dep in pom.findall("./m:dependencies/m:dependency", NS):
        group = dep.findtext("m:groupId", default="", namespaces=NS)
        artifact = dep.findtext("m:artifactId", default="", namespaces=NS)
        scope = dep.findtext("m:scope", default="compile", namespaces=NS)
        if group == "com.myhomelibcorp" and artifact in MODULES and scope not in {"test", "provided"}:
            result.add(artifact)
    return result


def production_dependencies(module_dir: str) -> set[tuple[str, str]]:
    pom = ET.parse(ROOT / module_dir / "pom.xml").getroot()
    result: set[tuple[str, str]] = set()
    for dep in pom.findall("./m:dependencies/m:dependency", NS):
        group = dep.findtext("m:groupId", default="", namespaces=NS)
        artifact = dep.findtext("m:artifactId", default="", namespaces=NS)
        scope = dep.findtext("m:scope", default="compile", namespaces=NS)
        if scope not in {"test", "provided"}:
            result.add((group, artifact))
    return result


def java_files(module_dir: str):
    yield from (ROOT / module_dir / "src" / "main" / "java").rglob("*.java")


def source_class_name(path: Path, text: str) -> str:
    match = re.search(r"^package\s+([\w.]+);", text, re.MULTILINE)
    package = match.group(1) if match else ""
    return f"{package}.{path.stem}" if package else path.stem


def referenced_internal_modules(text: str) -> set[str]:
    refs: set[str] = set()
    for prefix in re.findall(r"com\.myhomelibcorp\.([A-Za-z_][A-Za-z0-9_]*)", text):
        module = PACKAGE_TO_MODULE.get(prefix)
        if module:
            refs.add(module)
    return refs


def check_module_graph() -> None:
    graph: dict[str, set[str]] = {}
    for module in MODULES:
        actual = production_internal_dependencies(module)
        expected = EXPECTED_INTERNAL_DEPS[module]
        graph[module] = actual
        if actual != expected:
            fail(f"{module}: direct internal POM deps {sorted(actual)} != expected {sorted(expected)}")

    # DFS cycle check.
    visiting: set[str] = set()
    visited: set[str] = set()

    def visit(node: str, stack: list[str]) -> None:
        if node in visiting:
            cycle = stack[stack.index(node):] + [node]
            fail("module dependency cycle: " + " -> ".join(cycle))
            return
        if node in visited:
            return
        visiting.add(node)
        stack.append(node)
        for dep in sorted(graph.get(node, ())):
            visit(dep, stack)
        stack.pop()
        visiting.remove(node)
        visited.add(node)

    for module in MODULES:
        visit(module, [])


def check_direct_source_dependencies() -> None:
    for module in MODULES:
        declared = production_internal_dependencies(module)
        for file in java_files(module):
            text = file.read_text(encoding="utf-8")
            refs = referenced_internal_modules(text) - {module}
            missing = refs - declared
            if missing:
                rel = file.relative_to(ROOT)
                fail(f"{rel}: references {sorted(missing)} without direct POM dependency")


def check_forbidden_source_boundaries() -> None:
    rules: dict[str, tuple[str, ...]] = {
        "myhomelib-shared": (
            "com.myhomelibcorp.domain.", "com.myhomelibcorp.application.",
            "com.myhomelibcorp.infrastructure.", "com.myhomelibcorp.ui.",
            "com.myhomelibcorp.reader.", "com.myhomelibcorp.mcp.", "com.myhomelibcorp.opds.",
            "org.springframework.", "javafx.", "java.sql.", "org.apache.lucene.",
        ),
        "myhomelib-domain": (
            "com.myhomelibcorp.application.", "com.myhomelibcorp.infrastructure.",
            "com.myhomelibcorp.ui.", "com.myhomelibcorp.reader.", "com.myhomelibcorp.mcp.", "com.myhomelibcorp.opds.",
            "org.springframework.", "javafx.", "java.sql.", "javax.sql.", "org.apache.lucene.",
        ),
        "myhomelib-application": (
            "com.myhomelibcorp.infrastructure.", "com.myhomelibcorp.ui.",
            "com.myhomelibcorp.reader.", "com.myhomelibcorp.mcp.", "com.myhomelibcorp.opds.",
            "javafx.", "java.sql.", "javax.sql.", "org.springframework.jdbc.", "org.apache.lucene.",
        ),
        "myhomelib-infrastructure": (
            "com.myhomelibcorp.ui.", "com.myhomelibcorp.reader.", "com.myhomelibcorp.opds.", "javafx.",
        ),
        "myhomelib-ui": (
            "com.myhomelibcorp.infrastructure.", "com.myhomelibcorp.opds.", "java.sql.", "javax.sql.",
            "org.springframework.jdbc.", "org.apache.lucene.",
        ),
        "myhomelib-reader": (
            "com.myhomelibcorp.domain.", "com.myhomelibcorp.application.",
            "com.myhomelibcorp.infrastructure.", "com.myhomelibcorp.ui.", "com.myhomelibcorp.mcp.", "com.myhomelibcorp.opds.",
            "org.springframework.", "java.sql.", "javax.sql.", "org.apache.lucene.",
        ),
        "myhomelib-mcp": (
            "com.myhomelibcorp.domain.", "com.myhomelibcorp.application.",
            "com.myhomelibcorp.infrastructure.", "com.myhomelibcorp.ui.", "com.myhomelibcorp.reader.", "com.myhomelibcorp.opds.",
            "org.springframework.", "javafx.",
        ),
        "myhomelib-opds": (
            "com.myhomelibcorp.infrastructure.", "com.myhomelibcorp.ui.",
            "com.myhomelibcorp.reader.", "com.myhomelibcorp.mcp.", "javafx.",
            "java.sql.", "javax.sql.", "org.springframework.jdbc.", "org.apache.lucene.",
        ),
    }

    for module, forbidden in rules.items():
        for file in java_files(module):
            text = file.read_text(encoding="utf-8")
            for prefix in forbidden:
                if prefix in text:
                    rel = file.relative_to(ROOT)
                    fail(f"{rel}: forbidden dependency/reference contains '{prefix}'")

    portable_reader_dirs = ("api", "core", "format", "layout", "model", "service")
    reader_base = ROOT / "myhomelib-reader" / "src" / "main" / "java" / "com" / "myhomelibcorp" / "reader"
    for directory in portable_reader_dirs:
        for file in (reader_base / directory).rglob("*.java") if (reader_base / directory).exists() else ():
            text = file.read_text(encoding="utf-8")
            if "javafx." in text:
                fail(f"{file.relative_to(ROOT)}: JavaFX is forbidden in portable reader package '{directory}'")
    render_api = reader_base / "render" / "api"
    if render_api.exists():
        for file in render_api.rglob("*.java"):
            if "javafx." in file.read_text(encoding="utf-8"):
                fail(f"{file.relative_to(ROOT)}: JavaFX is forbidden in reader.render.api")


def check_dependency_cleanup() -> None:
    app = production_dependencies("myhomelib-application")
    for forbidden in {
        ("org.apache.lucene", "lucene-core"),
        ("org.springframework.modulith", "spring-modulith-core"),
        ("org.springframework.boot", "spring-boot"),
        ("org.springframework.boot", "spring-boot-autoconfigure"),
        ("jakarta.annotation", "jakarta.annotation-api"),
    }:
        if forbidden in app:
            fail(f"myhomelib-application still declares unused/forbidden production dependency {forbidden[0]}:{forbidden[1]}")

    infra = production_dependencies("myhomelib-infrastructure")
    if ("org.openjfx", "javafx-graphics") in infra:
        fail("myhomelib-infrastructure must not declare JavaFX")

    ui_internal = production_internal_dependencies("myhomelib-ui")
    if "myhomelib-infrastructure" in ui_internal:
        fail("myhomelib-ui must not declare myhomelib-infrastructure")


def check_ui_debt_ratchets() -> None:
    current_ports: set[str] = set()
    current_domain_models: set[str] = set()
    for file in java_files("myhomelib-ui"):
        text = file.read_text(encoding="utf-8")
        class_name = source_class_name(file, text)
        if re.search(r"com\.myhomelibcorp\.application\.port\.out\.", text):
            current_ports.add(class_name)
        if re.search(r"com\.myhomelibcorp\.domain\.model\.(?!valueobject\.)", text):
            current_domain_models.add(class_name)

    new_ports = current_ports - UI_OUTPUT_PORT_BASELINE
    new_domain = current_domain_models - UI_DOMAIN_MODEL_BASELINE
    if new_ports:
        fail("new UI -> application.port.out debt: " + ", ".join(sorted(new_ports)))
    if new_domain:
        fail("new UI -> non-value domain model debt: " + ", ".join(sorted(new_domain)))

    notes.append(
        f"UI debt ratchet: output-port users {len(current_ports)}/{len(UI_OUTPUT_PORT_BASELINE)} baseline; "
        f"non-value domain-model users {len(current_domain_models)}/{len(UI_DOMAIN_MODEL_BASELINE)} baseline"
    )


def check_navigation_core() -> None:
    required = [
        ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/navigation/NavigationMode.java",
        ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/navigation/NavigationNodeDto.java",
        ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/navigation/NavigationQueryService.java",
        ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/navigation/DefaultNavigationQueryService.java",
    ]
    for file in required:
        if not file.exists():
            fail(f"Stage 2 navigation core is missing {file.relative_to(ROOT)}")

    panel = ROOT / "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/navigation/NavigationPanelController.java"
    if panel.exists():
        text = panel.read_text(encoding="utf-8")
        if re.search(r"\benum\s+NavigationMode\b", text):
            fail("NavigationPanelController must not declare its own NavigationMode")
        if "com.myhomelibcorp.application.navigation.NavigationQueryService" not in text:
            fail("NavigationPanelController must use application NavigationQueryService")
        if "SeriesId.generate()" in text:
            fail("NavigationPanelController must never manufacture SeriesId values")

    obsolete = [
        ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/navigation/LoadNavigationDataUseCase.java",
        ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/dto/NavigationDataDto.java",
    ]
    for file in obsolete:
        if file.exists():
            fail(f"obsolete parallel navigation API still exists: {file.relative_to(ROOT)}")



def check_navigation_stage3() -> None:
    mode = ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/navigation/NavigationMode.java"
    mode_text = mode.read_text(encoding="utf-8") if mode.exists() else ""
    for name in ("YEARS", "LANGUAGES", "ARCHIVES"):
        if not re.search(rf"\b{name}\b", mode_text):
            fail(f"Stage 3 navigation mode is missing: {name}")

    required = [
        ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/navigation/ArchiveNavigationKey.java",
        ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/repository/NavigationFacetRepository.java",
        ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteNavigationFacetRepository.java",
        ROOT / "tools/stage3-navigation-check.py",
    ]
    for file in required:
        if not file.exists():
            fail(f"Stage 3 navigation facet file is missing: {file.relative_to(ROOT)}")

    service = ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/navigation/DefaultNavigationQueryService.java"
    if service.exists():
        text = service.read_text(encoding="utf-8")
        if "NavigationFacetRepository" not in text:
            fail("DefaultNavigationQueryService must use NavigationFacetRepository for Stage 3 facets")
        if "streamAll()" in text:
            fail("Stage 3 navigation facets must not materialize the whole catalogue through streamAll()")

    query = ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/query/book/BookQuery.java"
    if query.exists():
        text = query.read_text(encoding="utf-8")
        for field in ("Integer year", "String archiveCollectionRoot", "String archivePath"):
            if field not in text:
                fail(f"BookQuery Stage 3 filter missing: {field}")

    panel = ROOT / "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/navigation/NavigationPanelController.java"
    if panel.exists():
        text = panel.read_text(encoding="utf-8")
        for label in ("case YEARS", "case LANGUAGES", "case ARCHIVES"):
            if label not in text:
                fail(f"NavigationPanelController Stage 3 presentation missing: {label}")



def check_navigation_stage4() -> None:
    mode = ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/navigation/NavigationMode.java"
    mode_text = mode.read_text(encoding="utf-8") if mode.exists() else ""
    for name in ("KEYWORDS", "GROUPS", "REVIEWS"):
        if not re.search(rf"\b{name}\b", mode_text):
            fail(f"Stage 4 navigation mode is missing: {name}")

    required = [
        ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/navigation/ReviewNavigationFilter.java",
        ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/group/LoadBookGroupsUseCase.java",
        ROOT / "tools/stage4-navigation-check.py",
    ]
    for file in required:
        if not file.exists():
            fail(f"Stage 4 navigation file is missing: {file.relative_to(ROOT)}")

    facets = ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/repository/NavigationFacetRepository.java"
    if facets.exists():
        text = facets.read_text(encoding="utf-8")
        for method in ("findKeywords()", "findGroups()", "findReviewSubsets()"):
            if method not in text:
                fail(f"NavigationFacetRepository Stage 4 facet missing: {method}")

    query = ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/query/book/BookQuery.java"
    if query.exists():
        text = query.read_text(encoding="utf-8")
        for field in ("String keyword", "boolean onlyRated", "boolean onlyReviewed"):
            if field not in text:
                fail(f"BookQuery Stage 4 filter missing: {field}")

    builder = ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/helper/BookQueryBuilder.java"
    if builder.exists():
        text = builder.read_text(encoding="utf-8")
        if "FROM keyword_books kb" not in text or "kb.normalized_name = ?" not in text:
            fail("Stage 4 keyword filter must use the normalized exact-token projection")
        if "WITH RECURSIVE split" in text:
            fail("Stage 4 keyword filter must not split books.keywords recursively per row")
        for clause in ("COALESCE(b.rate, 0) > 0", "b.review IS NOT NULL AND TRIM(b.review) <> ''"):
            if clause not in text:
                fail(f"Stage 4 review filter clause missing: {clause}")

    details = ROOT / "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/details/BookDetailsController.java"
    if details.exists():
        text = details.read_text(encoding="utf-8")
        for call in ("navigateToKeyword", "navigateToGroup", "navigateToReviews"):
            if call not in text:
                fail(f"Stage 4 details deep link missing: {call}")



def check_navigation_stage5() -> None:
    mode = ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/navigation/NavigationMode.java"
    mode_text = mode.read_text(encoding="utf-8") if mode.exists() else ""
    for name in ("ALREADY_READ", "HISTORY"):
        if not re.search(rf"\b{name}\b", mode_text):
            fail(f"Stage 5 navigation mode is missing: {name}")

    required = [
        ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/service/ReadingHistoryService.java",
        ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/exchange/ReadingHistoryPort.java",
        ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/exchange/SqliteReadingHistoryAdapter.java",
        ROOT / "myhomelib-infrastructure/src/main/resources/db/migration/V30__create_reading_history.sql",
        ROOT / "tools/stage5-history-check.py",
    ]
    for file in required:
        if not file.exists():
            fail(f"Stage 5 history file is missing: {file.relative_to(ROOT)}")

    query = ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/query/book/BookQuery.java"
    if query.exists() and "boolean onlyInHistory" not in query.read_text(encoding="utf-8"):
        fail("BookQuery Stage 5 history filter missing: boolean onlyInHistory")

    builder = ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/helper/BookQueryBuilder.java"
    if builder.exists():
        text = builder.read_text(encoding="utf-8")
        for clause in (
            "JOIN reading_history rh ON rh.book_id = b.id",
            "ORDER BY rh.last_opened_at DESC, b.id ASC",
            "b.progress = 100",
        ):
            if clause not in text:
                fail(f"Stage 5 query semantics missing: {clause}")

    workspace = ROOT / "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/navigation/WorkspaceManager.java"
    if workspace.exists():
        text = workspace.read_text(encoding="utf-8")
        for marker in ('case "already-read"', 'case "history"', "showAlreadyReadWorkspace", "showHistoryWorkspace"):
            if marker not in text:
                fail(f"Stage 5 workspace history integration missing: {marker}")

    main_view = ROOT / "myhomelib-ui/src/main/resources/view/MainView.fxml"
    if main_view.exists():
        text = main_view.read_text(encoding="utf-8")
        for marker in ('fx:id="recentBooksMenu"', '#onAlreadyRead', '#onHistory', '#onClearHistory'):
            if marker not in text:
                fail(f"Stage 5 MainView action missing: {marker}")

    reader = ROOT / "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java"
    if reader.exists() and "readingHistoryService.recordOpened(openedId)" not in reader.read_text(encoding="utf-8"):
        fail("Stage 5 Reader must record successful opens in reading history")



def check_online_update_stage6() -> None:
    required = [
        ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/catalog/CatalogSyncSession.java",
        ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/catalog/CatalogBookSnapshot.java",
        ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/catalog/CatalogUpdateService.java",
        ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/catalog/CatalogUpdateTrackingPort.java",
        ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/catalog/SqliteCatalogUpdateTrackingAdapter.java",
        ROOT / "myhomelib-infrastructure/src/main/resources/db/migration/V31__catalog_update_revision_model.sql",
        ROOT / "tools/stage6-online-update-check.py",
    ]
    for file in required:
        if not file.exists():
            fail(f"Stage 6 online update file is missing: {file.relative_to(ROOT)}")

    migration = ROOT / "myhomelib-infrastructure/src/main/resources/db/migration/V31__catalog_update_revision_model.sql"
    if migration.exists():
        text = migration.read_text(encoding="utf-8")
        for marker in (
            "catalog_sources", "source_revision", "source_fingerprint",
            "catalog_book_state", "catalog_fingerprint", "downloaded_revision",
            "downloaded_fingerprint", "followed_authors", "catalog_update_events",
            "NEW_BY_FOLLOWED_AUTHOR", "UPDATED_DOWNLOADED_BOOK",
        ):
            if marker not in text:
                fail(f"Stage 6 migration semantic missing: {marker}")

    update_usecase = ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/UpdateCollectionFromNetworkUseCase.java"
    if update_usecase.exists():
        text = update_usecase.read_text(encoding="utf-8")
        if "CatalogSourceIdentity.remoteCollection(collection.getId())" not in text:
            fail("Stage 6 remote INPX must use stable collection identity instead of temp download path")

    writer = ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/importengine/JdbcBatchWriter.java"
    if writer.exists():
        text = writer.read_text(encoding="utf-8")
        for marker in (
            "CASE WHEN books.local = 1 THEN books.file_name ELSE excluded.file_name END",
            "rate = books.rate", "progress = books.progress", "review = books.review",
            "CASE WHEN books.local = 1 THEN books.collection_root ELSE excluded.collection_root END",
        ):
            if marker not in text:
                fail(f"Stage 6 remote UPSERT preservation guard missing: {marker}")

    download = ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/download/DownloadBookUseCase.java"
    if download.exists() and "catalogUpdateTrackingPort.markDownloadedBaseline(bookId)" not in download.read_text(encoding="utf-8"):
        fail("Stage 6 successful download must capture the current catalog baseline")

def main() -> int:
    check_module_graph()
    check_direct_source_dependencies()
    check_forbidden_source_boundaries()
    check_dependency_cleanup()
    check_navigation_core()
    check_navigation_stage3()
    check_navigation_stage4()
    check_navigation_stage5()
    check_online_update_stage6()
    check_ui_debt_ratchets()

    print("MyHomeLib architecture check")
    print("=" * 32)
    for module in MODULES:
        deps = sorted(production_internal_dependencies(module))
        print(f"{module:28} -> {', '.join(deps) if deps else '-'}")
    print()
    for note in notes:
        print("INFO:", note)

    if errors:
        print(f"\nFAIL: {len(errors)} architecture violation(s)", file=sys.stderr)
        for item in errors:
            print(" -", item, file=sys.stderr)
        return 1

    print("\nPASS: architecture baseline is intact")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
