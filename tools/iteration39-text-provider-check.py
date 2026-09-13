#!/usr/bin/env python3
"""Source-level acceptance guard for Iteration 39 (MHL-210/MHL-211)."""
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


canvas = read("myhomelib-reader/src/main/java/com/myhomelibcorp/reader/render/javafx/ReaderCanvas.java")
view = read("myhomelib-reader/src/main/java/com/myhomelibcorp/reader/render/javafx/ReaderView.java")
workspace = read("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java")
dictionary_service = read("myhomelib-application/src/main/java/com/myhomelibcorp/application/dictionary/DictionaryLookupService.java")
translation_service = read("myhomelib-application/src/main/java/com/myhomelibcorp/application/translation/TranslationService.java")
local_dictionary = read("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/dictionary/LocalDictionaryProvider.java")
local_translation = read("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/translation/LocalPhraseTranslationProvider.java")
deepl = read("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/translation/DeepLTranslationProvider.java")
google = read("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/translation/GoogleTranslationProvider.java")
custom = read("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/translation/CustomHttpTranslationProvider.java")
http_support = read("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/textprovider/TextProviderHttpSupport.java")

require(canvas, "ui.reader.selection.dictionary", "ui.reader.selection.translate", "setOnDictionaryRequested", "setOnTranslationRequested")
require(view, "setOnDictionaryRequested", "setOnTranslationRequested")
reject(canvas, "com.myhomelibcorp.application", "com.myhomelibcorp.infrastructure", "org.springframework", "java.sql")
reject(view, "com.myhomelibcorp.application", "com.myhomelibcorp.infrastructure", "org.springframework", "java.sql")

require(workspace,
        "setOnDictionaryRequested(this::lookupDictionaryFromSelection)",
        "setOnTranslationRequested(this::translateSelection)",
        "provider.remote() && !dialogService.showConfirmation",
        "ui.reader.translation.privacy.message",
        "cancelTextProviderRequests();",
        "sameOpenBook(requestToken, bookId)")
reject(workspace, "com.myhomelibcorp.infrastructure.dictionary", "com.myhomelibcorp.infrastructure.translation")
if "setOnSelectionChanged(this::translate" in workspace:
    raise SystemExit("Translation must never be triggered by a selection-change observer")

require(dictionary_service, "DictionaryProvider::isOffline", "TextProviderRequestContext.create", ".orTimeout(")
require(translation_service, "TranslationProvider::isRemote", "TextProviderRequestContext.create", ".orTimeout(")
require(local_dictionary, "isOffline() { return true; }", "dictionary.local.path", "StandardCharsets.UTF_8")
require(local_translation, "isRemote() { return false; }", "translation.local.path", "StandardCharsets.UTF_8")

for source, name in [(deepl, "DeepL"), (google, "Google"), (custom, "Custom")]:
    require(source, "isRemote() { return true; }", "HttpRequest", "TextProviderHttpSupport.send")
    if 'isEnabled() { return true; }' in source:
        raise SystemExit(f"{name} remote provider must not be enabled unconditionally")

require(deepl, 'settings.getBoolean("translation.deepl.enabled", false)')
require(google, 'settings.getBoolean("translation.google.enabled", false)')
require(custom, 'settings.getBoolean("translation.custom.enabled", false)')
require(http_support, '"https".equalsIgnoreCase(uri.getScheme())', "EncryptionUtil.isEncrypted", "must be encrypted or supplied at runtime")

keys = {
    "ui.reader.selection.dictionary",
    "ui.reader.selection.translate",
    "ui.reader.translation.privacy.title",
    "ui.reader.translation.privacy.message",
    "ui.reader.text_provider.issue.timeout",
    "ui.reader.text_provider.issue.authentication",
}
for lang in ("uk", "en", "bg"):
    root_path = ROOT / "Lang" / f"{lang}.json"
    bundled_path = ROOT / "myhomelib-ui" / "src/main/resources/lang/default" / f"{lang}.json"
    root_catalog = json.loads(root_path.read_text(encoding="utf-8"))
    bundled_catalog = json.loads(bundled_path.read_text(encoding="utf-8"))
    root_translations = root_catalog["translations"]
    bundled_translations = bundled_catalog["translations"]
    missing = sorted(key for key in keys if not root_translations.get(key))
    if missing:
        raise SystemExit(f"{lang}: missing Iteration 39 localization keys: {missing}")
    for key in keys:
        if root_translations[key] != bundled_translations.get(key):
            raise SystemExit(f"{lang}: root/bundled localization mismatch for {key}")

print("PASS: Iteration 39 DictionaryProvider/TranslationProvider source contract")
