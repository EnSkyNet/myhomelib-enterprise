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

require("myhomelib-reader/src/main/java/com/myhomelibcorp/reader/api/ReaderSelection.java",
        ["startOffset", "endOffset", "chapterId", "paragraphId", "prefix", "suffix"])
require("myhomelib-reader/src/main/java/com/myhomelibcorp/reader/api/ReaderAnnotationOverlay.java",
        ["startOffset", "endOffset", "color", "note"])
require("myhomelib-application/src/main/java/com/myhomelibcorp/application/annotation/AnnotationAnchorData.java",
        ["allowsArtifact", "startOffset", "quote", "prefix", "suffix"])
require("myhomelib-application/src/main/java/com/myhomelibcorp/application/annotation/AnnotationReaderItem.java",
        ["AnnotationAnchorData anchor", "boolean note", "fromDomain"])
require("myhomelib-application/src/main/java/com/myhomelibcorp/application/annotation/AnnotationReaderResolver.java",
        ["AnnotationAnchorRelocator.resolve", "ResolvedRange"])
require("myhomelib-reader/src/main/java/com/myhomelibcorp/reader/render/javafx/ReaderSelectionController.java",
        ["beginHandleDrag", "extendByCharacters", "snapshot()", "renderHandle"])
require("myhomelib-reader/src/main/java/com/myhomelibcorp/reader/render/javafx/ReaderCanvas.java",
        ["ui.reader.selection.highlight", "ui.reader.selection.note", "setAnnotationOverlays", "renderAnnotationOverlays"])
require("myhomelib-reader/src/main/java/com/myhomelibcorp/reader/render/javafx/ReaderKeyboardScrollController.java",
        ["event.isShiftDown() && code == KeyCode.LEFT", "event.isShiftDown() && code == KeyCode.RIGHT",
         "requestHighlightFromInput", "requestNoteFromInput"])
require("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/ReaderAnnotationPresenter.java",
        ["AnnotationAnchorData", "AnnotationReaderItem", "AnnotationReaderResolver", "ReaderAnnotationOverlay"])
require("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java",
        ["AnnotationService annotationService", "setOnHighlightRequested", "setOnNoteRequested",
         "uiBackgroundExecutor.submit(() -> annotationService.createHighlight",
         "uiBackgroundExecutor.submit(() -> annotationService.createNote", "listBookAnnotationViews(bookId)", "refreshAnnotationsAsync"])

presenter = ROOT / "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/ReaderAnnotationPresenter.java"
if presenter.is_file() and "com.myhomelibcorp.domain.model.annotation" in presenter.read_text(encoding="utf-8"):
    errors.append("ReaderAnnotationPresenter must use application annotation DTOs, not domain annotation types")

reader_root = ROOT / "myhomelib-reader/src/main/java"
for p in reader_root.rglob("*.java"):
    text = p.read_text(encoding="utf-8")
    for forbidden in ("com.myhomelibcorp.domain.model.annotation", "AnnotationService", "SqliteAnnotationRepository"):
        if forbidden in text:
            errors.append(f"reader persistence boundary violation: {p.relative_to(ROOT)} -> {forbidden}")

keys = [
    "ui.reader.selection.highlight", "ui.reader.selection.note", "ui.reader.selection.copy", "ui.reader.selection.clear",
    "ui.reader.annotation.highlight_saved", "ui.reader.annotation.note.title", "ui.reader.annotation.note.header",
    "ui.reader.annotation.note.label", "ui.reader.annotation.note_saved", "ui.reader.annotation.save_failed",
]
for lang in ("uk", "en", "bg"):
    p = ROOT / f"myhomelib-ui/src/main/resources/lang/default/{lang}.json"
    try:
        data = json.loads(p.read_text(encoding="utf-8"))
    except Exception as exc:
        errors.append(f"{p.relative_to(ROOT)} invalid JSON: {exc}")
        continue
    translations = data.get("translations")
    if not isinstance(translations, dict):
        errors.append(f"{p.relative_to(ROOT)} translations must be an object")
        continue
    for key in keys:
        if not str(translations.get(key, "")).strip():
            errors.append(f"{p.relative_to(ROOT)} missing localization key {key}")

if errors:
    print("STAGE 36 READER ANNOTATIONS CHECK: FAIL")
    for error in errors:
        print(" -", error)
    sys.exit(1)

print("STAGE 36 READER ANNOTATIONS CHECK: PASS")
print(" - selection snapshots + handles + keyboard/context actions: PRESENT")
print(" - application-service async persistence bridge: PRESENT")
print(" - restart/reflow overlay restoration + artifact guard: PRESENT")
print(" - Reader -> annotation persistence/domain dependency: ABSENT")
print(" - UK/EN/BG localization keys: PRESENT")
