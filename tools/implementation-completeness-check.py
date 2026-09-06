#!/usr/bin/env python3
from __future__ import annotations

from collections import defaultdict
from pathlib import Path
import hashlib
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
MAIN_ROOTS = [
    ROOT / "myhomelib-application/src/main/java",
    ROOT / "myhomelib-domain/src/main/java",
    ROOT / "myhomelib-infrastructure/src/main/java",
    ROOT / "myhomelib-ui/src/main/java",
    ROOT / "myhomelib-reader/src/main/java",
    ROOT / "myhomelib-shared/src/main/java",
    ROOT / "myhomelib-mcp/src/main/java",
    ROOT / "myhomelib-opds/src/main/java",
]

failures: list[str] = []


def fail(message: str) -> None:
    failures.append(message)


def read(rel: str) -> str:
    p = ROOT / rel
    if not p.is_file():
        fail(f"missing required file: {rel}")
        return ""
    return p.read_text(encoding="utf-8", errors="replace")


java_files = [p for base in MAIN_ROOTS if base.exists() for p in base.rglob("*.java")]

# 1. No obvious production placeholders / deliberately unimplemented behavior.
placeholder_re = re.compile(
    r"\bTODO\b|\bFIXME\b|NotImplementedException|"
    r"not\s+implemented|не\s+реалізован[а-яіїєґ]*",
    re.IGNORECASE,
)
for p in java_files:
    text = p.read_text(encoding="utf-8", errors="replace")
    for match in placeholder_re.finditer(text):
        line = text.count("\n", 0, match.start()) + 1
        fail(f"placeholder/unimplemented marker: {p.relative_to(ROOT)}:{line}: {match.group(0)}")
    for match in re.finditer(r"throw\s+new\s+UnsupportedOperationException\b", text):
        line = text.count("\n", 0, match.start()) + 1
        fail(f"placeholder/unimplemented marker: {p.relative_to(ROOT)}:{line}: thrown UnsupportedOperationException")

# 2. No empty public/protected methods. Constructors/records are not matched by requiring a return type.
empty_method_re = re.compile(
    r"(?ms)^\s*(public|protected)\s+(?:static\s+)?(?:final\s+)?(?:<[^>]+>\s+)?"
    r"[\w<>?,.\[\] ]+\s+(\w+)\s*\([^;{}]*\)\s*(?:throws\s+[^\{]+)?\{\s*\}"
)
for p in java_files:
    text = p.read_text(encoding="utf-8", errors="replace")
    declared_types = set(re.findall(r"\b(?:class|record|interface|enum)\s+(\w+)", text))
    for match in empty_method_re.finditer(text):
        if match.group(2) in declared_types:
            continue
        line = text.count("\n", 0, match.start()) + 1
        fail(f"empty public/protected method: {p.relative_to(ROOT)}:{line}: {match.group(2)}")

# 3. No sentinel-only interface default implementations. A default must perform real behavior.
sentinel_re = re.compile(
    r"(?ms)\bdefault\s+[\w<>?,.\[\] ]+\s+(\w+)\s*\([^;{}]*\)\s*\{\s*"
    r"return\s+(null|false|0|-1|Optional\.empty\(\)|List\.of\(\)|Set\.of\(\)|Map\.of\(\)|Stream\.empty\(\));\s*\}"
)
for p in java_files:
    text = p.read_text(encoding="utf-8", errors="replace")
    for match in sentinel_re.finditer(text):
        line = text.count("\n", 0, match.start()) + 1
        fail(f"sentinel interface default: {p.relative_to(ROOT)}:{line}: {match.group(1)} -> {match.group(2)}")

# 4. No simple unused explicit imports. This catches stale wiring/refactor residue cheaply.
for p in java_files:
    text = p.read_text(encoding="utf-8", errors="replace")
    for match in re.finditer(r"^import\s+(?:static\s+)?([\w.]+);$", text, re.MULTILINE):
        simple = match.group(1).split(".")[-1]
        if len(re.findall(r"\b" + re.escape(simple) + r"\b", text)) == 1:
            line = text.count("\n", 0, match.start()) + 1
            fail(f"unused import candidate: {p.relative_to(ROOT)}:{line}: {simple}")

# 4b. Spring-managed constructor dependencies must be used by their class.
for p in java_files:
    text = p.read_text(encoding="utf-8", errors="replace")
    if not re.search(r"@(Component|Service|Repository|Configuration)\b", text):
        continue
    for match in re.finditer(r"(?m)^\s*private\s+final\s+[\w<>?,.\[\] ]+\s+(\w+)\s*;", text):
        name = match.group(1)
        if len(re.findall(r"\b" + re.escape(name) + r"\b", text)) == 1:
            line = text.count("\n", 0, match.start()) + 1
            fail(f"unused Spring dependency: {p.relative_to(ROOT)}:{line}: {name}")

# 4c. Every application use-case class must have a production consumer/wiring reference.
all_production_text = "\n".join(p.read_text(encoding="utf-8", errors="replace") for p in java_files)
usecase_root = ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase"
for p in usecase_root.rglob("*.java"):
    text = p.read_text(encoding="utf-8", errors="replace")
    match = re.search(r"\b(?:class|record)\s+(\w+)", text)
    if not match:
        continue
    name = match.group(1)
    total = len(re.findall(r"\b" + re.escape(name) + r"\b", all_production_text))
    own = len(re.findall(r"\b" + re.escape(name) + r"\b", text))
    if total <= own:
        fail(f"application use case has no production consumer: {p.relative_to(ROOT)} ({name})")

# 4d. Application output ports must have a concrete implementation/extension in production.
port_root = ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/port"
for p in port_root.rglob("*.java"):
    text = p.read_text(encoding="utf-8", errors="replace")
    match = re.search(r"\binterface\s+(\w+)", text)
    if not match:
        continue
    name = match.group(1)
    implementation_pattern = r"\b(?:implements|extends)\s+[^\{;]*\b" + re.escape(name) + r"\b"
    if not re.search(implementation_pattern, all_production_text):
        fail(f"application port has no production implementation: {p.relative_to(ROOT)} ({name})")

# 5. Exact cross-file method clone detection. Long exact clones should share one implementation.
method_start_re = re.compile(
    r"(?m)^\s*(?:public|protected|private)\s+(?:static\s+)?(?:final\s+)?(?:<[^>]+>\s+)?"
    r"[\w<>?,.\[\] ]+\s+(\w+)\s*\([^;{}]*\)\s*(?:throws\s+[^\{]+)?\{"
)
clones: dict[str, list[tuple[int, str, Path]]] = defaultdict(list)
for p in java_files:
    text = p.read_text(encoding="utf-8", errors="replace")
    for match in method_start_re.finditer(text):
        start = match.end() - 1
        depth = 0
        end = None
        for idx in range(start, len(text)):
            char = text[idx]
            if char == "{":
                depth += 1
            elif char == "}":
                depth -= 1
                if depth == 0:
                    end = idx + 1
                    break
        if end is None:
            continue
        body = text[start + 1:end - 1]
        body = re.sub(r"//.*?$|/\*.*?\*/", "", body, flags=re.MULTILINE | re.DOTALL)
        normalized = re.sub(r"\s+", " ", body).strip()
        if len(normalized) < 180:
            continue
        digest = hashlib.sha256(normalized.encode("utf-8")).hexdigest()
        clones[digest].append((len(normalized), match.group(1), p))

for entries in clones.values():
    files = {p for _, _, p in entries}
    if len(files) <= 1:
        continue
    detail = "; ".join(f"{name}@{p.relative_to(ROOT)}" for _, name, p in entries)
    fail(f"exact cross-file method clone ({entries[0][0]} chars): {detail}")

# 6. Specific invariants from this completeness pass.
legacy_paths = [
    "myhomelib-application/src/main/java/com/myhomelibcorp/application/mapper/BookMapperHelper.java",
    "myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/importer/ImportReader.java",
    "myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/resource/ReaderBookResourcePort.java",
    "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/importer/reader/Fb2ImportReader.java",
    "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/importer/reader/InpxImportReader.java",
    "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/adapter/ReaderBookResourceAdapter.java",
]
for rel in legacy_paths:
    if (ROOT / rel).exists():
        fail(f"dead legacy API returned: {rel}")

batch = read("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/BatchOperationsController.java")
main = read("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/MainController.java")
fxml = read("myhomelib-ui/src/main/resources/view/MainView.fxml")
if "updateProgressBatchUseCase.execute(selected, progress)" not in batch:
    fail("batch progress use case is not executed by UI controller")
if "handleBatchProgress" not in main or 'onAction="#handleBatchProgress"' not in fxml:
    fail("batch progress is not UI-reachable")

vm = read("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/viewmodel/BookViewModel.java")
author_ui = read("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/author/AuthorWorkspaceController.java")
if "IntegerProperty year" not in vm or "yearProperty()" not in vm:
    fail("BookViewModel year property missing")
if "yearColumn.setCellValueFactory(cellData -> cellData.getValue().yearProperty())" not in author_ui:
    fail("author workspace year column is not bound to book year")

transaction_config = read("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/config/CollectionTransactionConfig.java")
for required in [
    "currentDataSource().getConnection(username, password)",
    "currentDataSource().getLogWriter()",
    "currentDataSource().setLogWriter(out)",
    "currentDataSource().setLoginTimeout(seconds)",
    "currentDataSource().getLoginTimeout()",
    "currentDataSource().unwrap(iface)",
    "currentDataSource().isWrapperFor(iface)",
]:
    if required not in transaction_config:
        fail(f"collection DataSource contract not delegated: {required}")

book_importer = read("myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/importer/BookImporterPort.java")
zip_importer = read("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/importer/zip/ZipImporter.java")
if re.search(r"default\s+long\s+countBooks", book_importer):
    fail("BookImporterPort.countBooks must not be a sentinel default")
if "long countBooks(Path file)" not in zip_importer:
    fail("ZipImporter.countBooks implementation missing")

session = read("myhomelib-application/src/main/java/com/myhomelibcorp/application/session/SessionService.java")
if "sessionRepository.clearSession(collectionId)" not in session:
    fail("SessionService.clearCurrentSession does not clear the real repository")


# 7. Runtime-contract invariants: user-visible settings and rich ports must not degrade into no-op compatibility APIs.
settings_dialog = read("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/ApplicationSettingsDialog.java")
session_service = read("myhomelib-application/src/main/java/com/myhomelibcorp/application/session/SessionService.java")
bootstrap_app = read("myhomelib-bootstrap/src/main/java/com/myhomelibcorp/MyHomeLibApp.java")
startup_resolver = read("myhomelib-bootstrap/src/main/java/com/myhomelibcorp/startup/StartupCollectionResolver.java")
if '"ui.restoreSession"' not in settings_dialog or 'getBoolean("ui.restoreSession"' not in session_service:
    fail("ui.restoreSession is exposed but not consumed at runtime")
if "getLastCollectionId()" not in startup_resolver or "restoreSessionWorkspace" not in bootstrap_app:
    fail("restore-session setting does not reach startup collection/workspace restoration")

for rel in [
    "myhomelib-bootstrap/src/main/resources/application.yml",
    "myhomelib-bootstrap/src/main/resources/application-prod.yml",
    "myhomelib-bootstrap/src/main/resources/application-dev.yml",
]:
    cfg = read(rel)
    for obsolete in ["features:", "index-disable-threshold:"]:
        if obsolete in cfg:
            fail(f"obsolete runtime-less config returned: {rel}: {obsolete}")
if '"search.autoIndex"' in all_production_text:
    fail("unsafe search.autoIndex pseudo-setting returned")

fast_port = read("myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/importer/FastImportService.java")
if "default " in fast_port or fast_port.count("ImportResult importInpx(") != 1:
    fail("FastImportService must expose one complete non-default import contract")
online_port = read("myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/download/OnlineBookDownloadPort.java")
if "default DownloadedBook download" in online_port or "boolean forceRefresh" not in online_port:
    fail("OnlineBookDownloadPort may silently discard forceRefresh")
remote_port = read("myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/download/RemoteCatalogDownloadPort.java")
if "default RemoteCatalogUpdatePlan" in remote_port or "Consumer<RemoteDownloadProgress> detailedProgress" not in remote_port:
    fail("RemoteCatalogDownloadPort may silently discard detailed progress")

import_usecase = read("myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/imports/ImportFileUseCase.java")
if "executeWithDetailedProgress" in import_usecase:
    fail("dead detailed-progress import API returned")

storage_port = read("myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/infrastructure/CollectionStorageManager.java")
storage_impl = read("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteCollectionStorageManager.java")
if "vacuum(Collection" in storage_port or "void vacuumCurrent()" not in storage_port or "void vacuumCurrent()" not in storage_impl:
    fail("collection VACUUM contract again implies arbitrary-collection support")


# 8. FXML wiring must be bidirectional: no stale @FXML fields and no fx:id controls without a Java consumer.
fxml_files = list((ROOT / "myhomelib-ui/src/main/resources").rglob("*.fxml"))
fxml_text = "\n".join(p.read_text(encoding="utf-8", errors="replace") for p in fxml_files)
fxml_ids = set(re.findall(r'fx:id="([A-Za-z_$][\w$]*)"', fxml_text))
ui_java_files = list((ROOT / "myhomelib-ui/src/main/java").rglob("*.java"))
ui_java_text = "\n".join(p.read_text(encoding="utf-8", errors="replace") for p in ui_java_files)
for fxid in sorted(fxml_ids):
    if not re.search(r"\b" + re.escape(fxid) + r"\b", ui_java_text):
        fail(f"FXML fx:id has no Java consumer: {fxid}")
for p in ui_java_files:
    text = p.read_text(encoding="utf-8", errors="replace")
    for match in re.finditer(r'@FXML\s+private\s+[\w<>?,.\[\] ]+\s+(\w+)\s*;', text):
        field = match.group(1)
        if field not in fxml_ids:
            line = text.count("\n", 0, match.start()) + 1
            fail(f"stale @FXML field without fx:id: {p.relative_to(ROOT)}:{line}: {field}")

# Buttons/menu items without declarative onAction must have explicit setOnAction wiring.
for p in fxml_files:
    text = p.read_text(encoding="utf-8", errors="replace")
    for tag in re.finditer(r'<(?:Button|MenuItem|CheckMenuItem|RadioMenuItem)\b([^>]*)>', text, re.DOTALL):
        attrs = tag.group(1)
        if "onAction=" in attrs:
            continue
        fx = re.search(r'fx:id="([A-Za-z_$][\w$]*)"', attrs)
        if fx and not re.search(r"\b" + re.escape(fx.group(1)) + r"\.setOnAction\s*\(", ui_java_text):
            line = text.count("\n", 0, tag.start()) + 1
            fail(f"action control has no handler: {p.relative_to(ROOT)}:{line}: {fx.group(1)}")

# 9. Specific end-to-end feature contracts found during the runtime pass.
author_controller = read("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/author/AuthorWorkspaceController.java")
author_fxml = read("myhomelib-ui/src/main/resources/view/author-workspace.fxml")
catalog_updates = read("myhomelib-application/src/main/java/com/myhomelibcorp/application/catalog/CatalogUpdateService.java")
for required in ["catalogUpdateService.isAuthorFollowed(authorId)", "catalogUpdateService.setAuthorFollowed(authorId, target)"]:
    if required not in author_controller:
        fail(f"followed-author feature is not end-to-end: missing {required}")
if 'onAction="#onToggleAuthorFollowed"' not in author_fxml:
    fail("followed-author feature has no UI action")
if "public List<CatalogUpdateRecord> pendingUpdates" in catalog_updates:
    fail("unused pendingUpdates compatibility API returned")

group_workspace = read("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/group/GroupWorkspaceController.java")
if "appState.setCurrentGroup(new Group(" not in group_workspace or "appState.setCurrentGroup(null);" not in group_workspace:
    fail("group selection does not synchronize/clear ApplicationState.currentGroup")

load_books = read("myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/book/LoadBooksUseCase.java")
for dead_method in ["loadByAuthor(", "loadBySeries(", "loadByGenre(", "loadByGroup(", "loadAll(", "loadByLanguage("]:
    if dead_method in load_books:
        fail(f"unused LoadBooksUseCase convenience API returned: {dead_method}")

if failures:
    print("IMPLEMENTATION COMPLETENESS CHECK: FAIL")
    for item in failures:
        print(" -", item)
    sys.exit(1)

print("IMPLEMENTATION COMPLETENESS CHECK: PASS")
print(f" - production Java files scanned: {len(java_files)}")
print(" - TODO/FIXME/unsupported markers: 0")
print(" - empty public/protected methods: 0")
print(" - sentinel interface defaults: 0")
print(" - unused explicit imports: 0")
print(" - unused Spring constructor dependencies: 0")
print(" - unconsumed use cases / unimplemented output ports: 0")
print(" - exact cross-file method clones >=180 chars: 0")
print(" - batch progress + year UI behavior: reachable")
print(" - delegating collection DataSource contract: complete")
print(" - runtime settings / rich-port contracts: complete")
print(" - bidirectional FXML wiring / action controls: complete")
print(" - followed-author + group-selection contracts: complete")
