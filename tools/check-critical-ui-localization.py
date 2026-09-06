#!/usr/bin/env python3
"""MHL-016: static localization gate for critical JavaFX screens."""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CRITICAL = [
    "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/search/SearchWorkspaceController.java",
    "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/BackupController.java",
    "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/opds/OpdsUiService.java",
    "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/imports/ImportProgressDialog.java",
    "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/imports/ImportWorkspaceController.java",
    "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/imports/ImportFileChooserFilters.java",
    "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/presenter/BookImportPresenter.java",
    "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/ReaderSettingsDialog.java",
    "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java",
    "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/NewReaderPersistenceService.java",
    "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/SearchDialogController.java",
    "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/TOCDialogController.java",
    "myhomelib-reader/src/main/java/com/myhomelibcorp/reader/render/javafx/ReaderToolbar.java",
    "myhomelib-reader/src/main/java/com/myhomelibcorp/reader/render/javafx/ReaderStatusBar.java",
]
LANGS = ("uk", "en", "bg")
CYRILLIC = re.compile(r"[А-Яа-яЁёІіЇїЄє]")
JAVA_STRING = re.compile(r'"((?:\\.|[^"\\])*)"')
KEY = re.compile(r'"((?:ui|common)\.[A-Za-z0-9_.-]+)"')
FORMAT = re.compile(r"%(?:\d+\$)?[,#+ 0(<-]*\d*(?:\.\d+)?[a-zA-Z%]")
LOG_CALL = re.compile(r"\blog\.(?:trace|debug|info|warn|error)\s*\(")
LEGACY_TR = re.compile(r"\b(?:i18n|localizationService)\.tr\s*\(")

DYNAMIC_KEYS = {
    *(f"ui.reader.semantic.{name}" for name in (
        "body", "book_title", "chapter_title", "section_title", "subtitle", "epigraph",
        "quote", "poem", "poem_author", "text_author", "annotation", "link", "footnote",
        "strong", "emphasis", "code")),
    *(f"ui.reader.theme.{name}" for name in ("light", "sepia", "dark", "amoled")),
}


def format_signature(value: str) -> tuple[str, ...]:
    result: list[str] = []
    for token in FORMAT.findall(value):
        if token == "%%":
            continue
        result.append(token[-1].lower())
    return tuple(result)


def main() -> int:
    errors: list[str] = []
    referenced = set(DYNAMIC_KEYS)

    for rel in CRITICAL:
        path = ROOT / rel
        if not path.is_file():
            errors.append(f"missing critical source: {rel}")
            continue
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            referenced.update(k for k in KEY.findall(line) if not k.endswith("."))
            if LEGACY_TR.search(line):
                errors.append(f"{rel}:{number}: legacy tr() is forbidden on critical screens")
            if LOG_CALL.search(line):
                continue
            for literal in JAVA_STRING.findall(line):
                if CYRILLIC.search(literal):
                    errors.append(
                        f"{rel}:{number}: user-facing Cyrillic literal must use a stable key: {literal!r}"
                    )

    catalogs: dict[str, dict[str, str]] = {}
    for lang in LANGS:
        root_file = ROOT / "Lang" / f"{lang}.json"
        bundled_file = ROOT / "myhomelib-ui" / "src" / "main" / "resources" / "lang" / "default" / f"{lang}.json"
        try:
            root_doc = json.loads(root_file.read_text(encoding="utf-8"))
            bundled_doc = json.loads(bundled_file.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            errors.append(f"{lang}: cannot read catalog: {exc}")
            continue
        if root_doc != bundled_doc:
            errors.append(f"{lang}: root and bundled catalogs differ")
        translations = root_doc.get("translations")
        if not isinstance(translations, dict):
            errors.append(f"{lang}: translations must be an object")
            continue
        catalogs[lang] = translations

    for key in sorted(referenced):
        values: dict[str, str] = {}
        for lang in LANGS:
            value = catalogs.get(lang, {}).get(key)
            if not isinstance(value, str) or not value.strip():
                errors.append(f"{lang}: missing/blank critical key {key}")
                continue
            if value == key:
                errors.append(f"{lang}: critical key resolves to itself: {key}")
            values[lang] = value
        if len(values) == len(LANGS):
            signatures = {lang: format_signature(value) for lang, value in values.items()}
            if len(set(signatures.values())) != 1:
                errors.append(f"format signature mismatch for {key}: {signatures}")

    if errors:
        print("Critical UI localization gate: FAILED")
        for error in errors:
            print(f" - {error}")
        return 1

    print(f"Critical UI localization gate: PASS ({len(referenced)} stable keys, {len(CRITICAL)} source files)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
