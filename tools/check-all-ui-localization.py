#!/usr/bin/env python3
"""Strict static guard for user-visible desktop UI localization.

Checks that:
* shipped UK/EN/BG catalogs stay synchronized and contain no blank/raw-key values;
* English catalog does not leak Cyrillic, Bulgarian does not leak Ukrainian/Russian-only letters;
* FXML visible attributes contain localization keys instead of display text;
* JavaFX UI sinks do not receive hard-coded alphabetic text;
* literal legacy tr("...") calls are gone;
* stable ui./common. keys referenced by desktop UI/reader sources are present (or are valid dynamic prefixes/settings IDs);
* Ukrainian display literals are absent from UI Java except logs and explicit internal parsing tokens.
"""
from __future__ import annotations

from pathlib import Path
import json
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
LANGS = ("uk", "en", "bg")
CATALOGS = {}
errors: list[str] = []

for lang in LANGS:
    path = ROOT / "Lang" / f"{lang}.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    CATALOGS[lang] = data["translations"]

base_keys = set(CATALOGS["uk"])
for lang in LANGS:
    keys = set(CATALOGS[lang])
    if keys != base_keys:
        errors.append(f"{lang}: catalog key set differs from uk (missing={len(base_keys-keys)}, extra={len(keys-base_keys)})")
    for key, value in CATALOGS[lang].items():
        if not isinstance(value, str) or not value.strip():
            errors.append(f"{lang}: blank translation for {key}")
        if isinstance(value, str) and value.startswith(("ui.", "common.")):
            errors.append(f"{lang}: raw localization key leaked as value for {key}: {value}")

cyrillic = re.compile(r"[А-Яа-яІіЇїЄєҐґ]")
non_bg_letters = re.compile(r"[ІіЇїЄєҐґЫыЭэЁё]")
for key, value in CATALOGS["en"].items():
    if cyrillic.search(value):
        errors.append(f"en: Cyrillic text remains in {key}: {value!r}")
for key, value in CATALOGS["bg"].items():
    if non_bg_letters.search(value):
        errors.append(f"bg: non-Bulgarian Cyrillic letters remain in {key}: {value!r}")

# FXML visible strings must always be stable catalog keys. Even language-neutral labels
# are kept in the catalog so future terminology changes remain data-only.
fxml_attrs = ("text", "promptText", "title", "accessibleText", "headerText", "contentText")
for path in (ROOT / "myhomelib-ui" / "src" / "main" / "resources").rglob("*.fxml"):
    text = path.read_text(encoding="utf-8")
    for attr in fxml_attrs:
        for m in re.finditer(rf"\b{attr}=\"([^\"]*)\"", text):
            value = m.group(1)
            if not value:
                continue
            if not value.startswith(("ui.", "common.")):
                errors.append(f"{path.relative_to(ROOT)}: hard-coded FXML {attr}={value!r}")
            elif value not in base_keys:
                errors.append(f"{path.relative_to(ROOT)}: unknown FXML localization key {value}")

java_roots = [
    ROOT / "myhomelib-ui" / "src" / "main" / "java",
    ROOT / "myhomelib-reader" / "src" / "main" / "java",
    # Application use cases may emit stable UI message descriptors. They must never
    # introduce a key that is missing from a shipped language catalog.
    ROOT / "myhomelib-application" / "src" / "main" / "java",
    # Bootstrap/OPDS may return stable message descriptors that are rendered by desktop UI.
    ROOT / "myhomelib-bootstrap" / "src" / "main" / "java",
    ROOT / "myhomelib-opds" / "src" / "main" / "java",
]

# Stable key reference check. Some ui.* strings are settings identifiers, while trailing-dot
# values are dynamic localization prefixes. Those are handled explicitly below.
key_re = re.compile(r'\"((?:ui|common)\.[A-Za-z0-9_.-]+)\"')
non_localization_ids = {"ui.confirmDelete", "ui.language", "ui.restoreSession", "ui.theme."}
for root in java_roots:
    for path in root.rglob("*.java"):
        for lineno, line in enumerate(path.read_text(encoding="utf-8", errors="ignore").splitlines(), 1):
            for m in key_re.finditer(line):
                key = m.group(1)
                if key in base_keys or key in non_localization_ids:
                    continue
                if key.endswith(".") and any(k.startswith(key) for k in base_keys):
                    continue
                errors.append(f"{path.relative_to(ROOT)}:{lineno}: unknown stable UI key/prefix {key}")

# No literal source-text translation calls should return after the stable-key migration.
legacy_tr = re.compile(r"\.tr\(\s*\"")
for path in (ROOT / "myhomelib-ui" / "src" / "main" / "java").rglob("*.java"):
    for lineno, line in enumerate(path.read_text(encoding="utf-8", errors="ignore").splitlines(), 1):
        if legacy_tr.search(line):
            errors.append(f"{path.relative_to(ROOT)}:{lineno}: literal tr(\"...\") call remains")

# UI sink guard for direct language-bearing text. Pure glyphs/numbers are intentionally allowed.
ui_sink_patterns = [
    re.compile(r'\.set(?:Text|PromptText|Title|HeaderText|ContentText|AccessibleText)\s*\(\s*\"([^\"]+)\"'),
    re.compile(r'new\s+(?:Label|Button|CheckBox|RadioButton|Tooltip|Tab|MenuItem|TableColumn|TextField|PasswordField)\s*\(\s*\"([^\"]+)\"'),
    re.compile(r'\.show(?:Info|Warning|Error|Confirmation|Confirm|Input|Choice)[A-Za-z]*\s*\(\s*\"([^\"]+)\"'),
    re.compile(r'new\s+ButtonType\s*\(\s*\"([^\"]+)\"'),
    # Project-local helpers that ultimately render their label argument as JavaFX text.
    re.compile(r'\b(?:row|commandRow)\s*\(\s*"([^"]+)"'),
    re.compile(r'\baddConverterRow\s*\([^,]+,\s*[^,]+,\s*"([^"]+)"'),
]
letters = re.compile(r"[A-Za-zА-Яа-яІіЇїЄєҐґ]")
log_line = re.compile(r"\b(?:log|LOGGER|logger)\.(?:trace|debug|info|warn|error)\s*\(")
ui_sink_roots = [
    ROOT / "myhomelib-ui" / "src" / "main" / "java",
    ROOT / "myhomelib-bootstrap" / "src" / "main" / "java",
]
for sink_root in ui_sink_roots:
    for path in sink_root.rglob("*.java"):
        for lineno, line in enumerate(path.read_text(encoding="utf-8", errors="ignore").splitlines(), 1):
            if log_line.search(line):
                continue
            for pattern in ui_sink_patterns:
                for m in pattern.finditer(line):
                    value = m.group(1)
                    if letters.search(value):
                        if value.startswith(("ui.", "common.")):
                            errors.append(f"{path.relative_to(ROOT)}:{lineno}: raw localization key passed directly to JavaFX UI sink: {value!r}; resolve it with i18n.text(...) or UiText.text(...)")
                        else:
                            errors.append(f"{path.relative_to(ROOT)}:{lineno}: hard-coded text in JavaFX UI sink: {value!r}")


# Raw exception messages must never be rendered directly. They are often produced by the OS,
# JDBC/HTTP libraries or external tools and therefore bypass both localization and privacy-safe
# user messaging. The only allowed direct getMessage() calls in the UI module are logger lines,
# the centralized technical-message extractor and the language-catalog diagnostic file writer.
exception_helper = ROOT / "myhomelib-ui" / "src" / "main" / "java" / "com" / "myhomelibcorp" / "ui" / "util" / "UiExceptionSupport.java"
language_catalog = ROOT / "myhomelib-ui" / "src" / "main" / "java" / "com" / "myhomelibcorp" / "ui" / "service" / "LanguageCatalogService.java"
for path in (ROOT / "myhomelib-ui" / "src" / "main" / "java").rglob("*.java"):
    for lineno, line in enumerate(path.read_text(encoding="utf-8", errors="ignore").splitlines(), 1):
        if "getMessage()" not in line:
            continue
        if path == exception_helper or path == language_catalog or log_line.search(line):
            continue
        errors.append(f"{path.relative_to(ROOT)}:{lineno}: raw exception getMessage() may leak into UI; use UiExceptionSupport.message(...) or technicalMessage(...) for logs")


# Raw Throwable variables must not be passed directly into localized/rendered UI text.
# This catches leaks via Throwable.toString(), which do not contain an explicit getMessage() call.
raw_throwable_ui = re.compile(
    r'(?:i18n\.format|UiText\.format|\.set(?:Text|HeaderText|ContentText)|\.show(?:Error|Warning|Info))'
    r'[^;\n]*(?<![A-Za-z0-9_])(?:error|exception|failure|cause|throwable)(?![A-Za-z0-9_])'
)
for path in (ROOT / "myhomelib-ui" / "src" / "main" / "java").rglob("*.java"):
    for lineno, line in enumerate(path.read_text(encoding="utf-8", errors="ignore").splitlines(), 1):
        if log_line.search(line):
            continue
        # Ignore words such as "error" occurring only inside localization keys/messages.
        code_only = literal_re.sub('""', line) if 'literal_re' in globals() else re.sub(r'"(?:\\.|[^"\\])*"', '""', line)
        if not raw_throwable_ui.search(code_only):
            continue
        if any(safe in code_only for safe in ("UiExceptionSupport.message(", "UiExceptionMessages.root(", "rootMessage(", "safeMessage(", "userVisibleDownloadError(", "message(unwrap(")):
            continue
        errors.append(f"{path.relative_to(ROOT)}:{lineno}: raw Throwable variable may be rendered via toString(); use UiExceptionSupport.message(...)")

# Legacy Throwable text helpers return raw backend/OS messages. UI code must route all user-facing
# failures through UiExceptionSupport, which separates localized user text from technical details.
legacy_throwable_helpers = ("ThrowableMessages.", "ThrowableText.")
for path in (ROOT / "myhomelib-ui" / "src" / "main" / "java").rglob("*.java"):
    for lineno, line in enumerate(path.read_text(encoding="utf-8", errors="ignore").splitlines(), 1):
        if log_line.search(line):
            continue
        if any(helper in line for helper in legacy_throwable_helpers):
            errors.append(f"{path.relative_to(ROOT)}:{lineno}: legacy raw Throwable text helper used in UI; use UiExceptionSupport")

# Result DTOs may carry technical backend errors for diagnostics. Singular result.error() is only
# allowed when it is explicitly captured as a technical value and never rendered directly. Error
# counters such as result.errors() are numeric metrics and remain valid UI data.
for path in (ROOT / "myhomelib-ui" / "src" / "main" / "java").rglob("*.java"):
    lines = path.read_text(encoding="utf-8", errors="ignore").splitlines()
    for index, line in enumerate(lines):
        lineno = index + 1
        if ".error()" in line and not log_line.search(line):
            if not re.search(r"\b(?:String|var)\s+technical\w*\s*=.*\.error\(\)", line):
                errors.append(f"{path.relative_to(ROOT)}:{lineno}: result.error() may leak technical text into UI; keep it technical-only and show a localized key")
        if "String.join" in line and ".errors()" in line:
            # Logger calls are frequently wrapped across lines; inspect the beginning of the
            # current statement as well as the current physical line.
            context = " ".join(lines[max(0, index - 2):index + 1])
            if not log_line.search(context):
                errors.append(f"{path.relative_to(ROOT)}:{lineno}: result.errors() technical details may leak into UI; log them and show localized summary")

# Ukrainian/Russian display literals in the UI module are prohibited. Logs, the alphabet data
# constant and internal text-classification compatibility checks are not display text.
literal_re = re.compile(r'\"((?:\\.|[^\"\\])*)\"')
internal_call_re = re.compile(r'\.(?:contains|startsWith|endsWith|equals|equalsIgnoreCase|matches|replace|replaceAll)\s*\(\s*$')
for path in (ROOT / "myhomelib-ui" / "src" / "main" / "java").rglob("*.java"):
    in_block_comment = False
    for lineno, raw_line in enumerate(path.read_text(encoding="utf-8", errors="ignore").splitlines(), 1):
        # Lightweight comment stripping sufficient for the project's conventional Java formatting.
        line = raw_line
        if in_block_comment:
            if "*/" in line:
                line = line.split("*/", 1)[1]
                in_block_comment = False
            else:
                continue
        if "/*" in line:
            before, after = line.split("/*", 1)
            if "*/" in after:
                after = after.split("*/", 1)[1]
                line = before + after
            else:
                line = before
                in_block_comment = True
        if "//" in line:
            line = line.split("//", 1)[0]
        if not line.strip() or log_line.search(line) or "CYRILLIC_ALPHABET" in line:
            continue
        for m in literal_re.finditer(line):
            value = m.group(1)
            if not cyrillic.search(value):
                continue
            before = line[max(0, m.start() - 120):m.start()]
            if internal_call_re.search(before):
                continue
            errors.append(f"{path.relative_to(ROOT)}:{lineno}: hard-coded Cyrillic Java UI literal: {value!r}")

if errors:
    print(f"UI localization guard FAILED: {len(errors)} issue(s)")
    for issue in errors:
        print(" -", issue)
    sys.exit(1)

print("UI localization guard OK")
print(f" - synchronized UI keys: {len(base_keys)}")
print(" - shipped locales: uk, en, bg")
print(" - FXML visible attributes: stable keys only")
print(" - JavaFX alphabetic UI-sink literals: 0")
print(" - legacy literal tr(...) calls: 0")
