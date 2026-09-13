#!/usr/bin/env python3
"""Source-level acceptance guard for Iteration 40 (MHL-206/MHL-207 closure)."""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def require(source: str, *needles: str) -> None:
    missing = [needle for needle in needles if needle not in source]
    if missing:
        raise SystemExit("Missing required source contract: " + ", ".join(missing))


def reject(source: str, *needles: str) -> None:
    found = [needle for needle in needles if needle in source]
    if found:
        raise SystemExit("Forbidden source dependency/behavior: " + ", ".join(found))


root_pom = read("pom.xml")
reader_pom = read("myhomelib-reader/pom.xml")
session = read("myhomelib-reader/src/main/java/com/myhomelibcorp/reader/render/pdf/PdfDocumentSession.java")
view = read("myhomelib-reader/src/main/java/com/myhomelibcorp/reader/render/pdf/PdfReaderView.java")
workspace = read("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java")
annotation_contract = read("myhomelib-application/src/main/java/com/myhomelibcorp/application/annotation/pdf/PdfAnnotationSelectionData.java")
packager = read("tools/package-v71-source.py")

require(root_pom, "<pdfbox.version>3.0.8</pdfbox.version>", "<jbig2-imageio.version>3.0.5</jbig2-imageio.version>")
require(reader_pom, "<artifactId>pdfbox</artifactId>", "<artifactId>jbig2-imageio</artifactId>", "<scope>runtime</scope>")

require(session,
        "PDFTextStripper", "MAX_SEARCH_RESULTS", "MAX_OUTLINE_ENTRIES", "MAX_OUTLINE_DEPTH",
        "readOutline", "searchText", "textLayerDetected", "checkInterrupted()")
reject(session.lower(), "tesseract", "ocrmypdf")

require(view,
        "setOnAddBookmark", "setOnBookmarks", "setOnToc", "setOnSearch",
        "searchTextAsync", "submitTrackedDocumentTask", "cancelOutstandingDocumentTasks",
        "goToPosition", "outlineEntries")
require(workspace,
        "pdfReaderView.setOnAddBookmark", "pdfReaderView.setOnBookmarks", "pdfReaderView.setOnToc",
        "pdfReaderView.setOnSearch", "pdfReaderView.searchTextAsync", "pdfReaderView.outlineEntries()",
        "persistenceService.saveBookmark", "pdfReaderView.goToPosition")
reject(workspace, "org.apache.pdfbox", "PDFTextStripper", "PDDocument")

require(annotation_contract, "pageTextStartOffset", "pageTextEndOffset", "page-local")
reject(annotation_contract, "org.apache.pdfbox", "java.sql", "com.myhomelibcorp.infrastructure")

# The formal source archive must stay Maven/dependency-binary free.
require(packager, '".mvn"', '"mvnw"', '"mvnw.cmd"')

keys = {
    "ui.reader.pdf.toc.select_header",
    "ui.reader.pdf.toc.label",
    "ui.reader.pdf.search.query",
    "ui.reader.pdf.search.result",
    "ui.reader.pdf.search.no_text_layer",
    "ui.reader.search.error_detail",
}
for lang in ("uk", "en", "bg"):
    root_catalog = json.loads((ROOT / "Lang" / f"{lang}.json").read_text(encoding="utf-8"))
    bundled_catalog = json.loads((ROOT / "myhomelib-ui/src/main/resources/lang/default" / f"{lang}.json").read_text(encoding="utf-8"))
    rt = root_catalog["translations"]
    bt = bundled_catalog["translations"]
    missing = sorted(key for key in keys if not rt.get(key))
    if missing:
        raise SystemExit(f"{lang}: missing Iteration 40 localization keys: {missing}")
    for key in keys:
        if rt[key] != bt.get(key):
            raise SystemExit(f"{lang}: root/bundled localization mismatch for {key}")

print("PASS: Iteration 40 PDF closure source contract")
