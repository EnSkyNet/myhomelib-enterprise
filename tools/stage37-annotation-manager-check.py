#!/usr/bin/env python3
from pathlib import Path
import json
import sys

ROOT = Path(__file__).resolve().parents[1]
errors = []


def require(path, needles):
    p = ROOT / path
    if not p.is_file():
        errors.append(f"missing file: {path}")
        return
    text = p.read_text(encoding="utf-8")
    for needle in needles:
        if needle not in text:
            errors.append(f"{path}: missing {needle!r}")


require("myhomelib-application/src/main/java/com/myhomelibcorp/application/annotation/AnnotationManagerService.java",
        ["AnnotationManagerQueryPort", "MAX_PAGE_SIZE", "deleteForUndo", "restoreDeleted", "repository.save"])
require("myhomelib-application/src/main/java/com/myhomelibcorp/application/annotation/AnnotationManagerFilter.java",
        ["searchText", "bookId", "type", "color", "tag", "dateFrom", "dateTo"])
require("myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/annotation/AnnotationManagerQueryPort.java",
        ["AnnotationManagerPage query", "AnnotationManagerFacets facets"])
require("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteAnnotationManagerQueryAdapter.java",
        ["LIMIT ? OFFSET ?", "annotation_tags", "LOWER(COALESCE", "escapeLike", "substr(a.updated_at,1,10)"])
require("myhomelib-ui/src/main/resources/view/annotation-manager-workspace.fxml",
        ["searchField", "bookFilter", "typeFilter", "colorFilter", "tagFilter", "dateFromFilter", "dateToFilter",
         "#openSelected", "#editSelected", "#deleteSelected", "#undoDelete", "#exportFilteredCsv"])
require("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/annotation/AnnotationManagerWorkspaceController.java",
        ["AnnotationManagerService annotationManagerService", "executor.submit", "annotationManagerService.query",
         "deleteForUndo", "restoreDeleted", "showAnnotationInReader", "StandardCharsets.UTF_8", "PAGE_SIZE = 100"])
require("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/navigation/WorkspaceManager.java",
        ["showAnnotationManagerWorkspace", "showAnnotationInReader", 'case "annotations"'])
require("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java",
        ["setAnnotationTargetId", "jumpToAnnotationTarget", "annotationTargetId = null", "readerView.goToPosition(position)"])
require("myhomelib-ui/src/main/resources/view/MainView.fxml", ["ui.annotations.menu", "#onAnnotations"])

ui_annotation = ROOT / "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/annotation"
for p in ui_annotation.rglob("*.java") if ui_annotation.is_dir() else []:
    text = p.read_text(encoding="utf-8")
    for forbidden in ("com.myhomelibcorp.domain.model.annotation", "SqliteAnnotation", "AnnotationRepository"):
        if forbidden in text:
            errors.append(f"annotation manager UI boundary violation: {p.relative_to(ROOT)} -> {forbidden}")

keys = [
    "ui.annotations.menu", "ui.annotations.title", "ui.annotations.search_prompt", "ui.annotations.book_filter",
    "ui.annotations.type_filter", "ui.annotations.color_filter", "ui.annotations.tag_filter", "ui.annotations.date_from",
    "ui.annotations.date_to", "ui.annotations.open", "ui.annotations.edit", "ui.annotations.delete",
    "ui.annotations.undo", "ui.annotations.export_csv", "ui.annotations.jump_unavailable",
]
for lang in ("uk", "en", "bg"):
    root_path = ROOT / f"Lang/{lang}.json"
    bundled = ROOT / f"myhomelib-ui/src/main/resources/lang/default/{lang}.json"
    if root_path.is_file() and bundled.is_file() and root_path.read_bytes() != bundled.read_bytes():
        errors.append(f"language catalogs not synchronized: {lang}")
    try:
        data = json.loads(bundled.read_text(encoding="utf-8"))
        translations = data.get("translations")
        if not isinstance(translations, dict):
            errors.append(f"{bundled.relative_to(ROOT)} translations must be an object")
            continue
        for key in keys:
            if not str(translations.get(key, "")).strip():
                errors.append(f"{bundled.relative_to(ROOT)} missing localization key {key}")
    except Exception as exc:
        errors.append(f"{bundled.relative_to(ROOT)} invalid JSON: {exc}")

if errors:
    print("STAGE 37 ANNOTATION MANAGER CHECK: FAIL")
    for error in errors:
        print(" -", error)
    sys.exit(1)

print("STAGE 37 ANNOTATION MANAGER CHECK: PASS")
print(" - bounded SQLite search/filter/pagination: PRESENT")
print(" - book/type/color/tag/date filters: PRESENT")
print(" - edit/delete/undo + filtered CSV export: PRESENT")
print(" - Reader jump-to-annotation integration: PRESENT")
print(" - UI -> annotation domain/SQLite direct dependency: ABSENT")
print(" - UK/EN/BG catalogs synchronized: PRESENT")
