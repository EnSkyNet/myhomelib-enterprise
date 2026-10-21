#!/usr/bin/env python3
"""Action-specific behavioral evidence gate for the JavaFX user-action inventory.

This is intentionally stricter than ui-action-coverage-check.py: a generic test for the
same controller/class is not enough.  Each action must have evidence containing its exact
handler name, a non-trivial method/callback token from its implementation, or its concrete
programmatic control/getter token.  Evidence files must contain a JUnit @Test and an
observable assertion/Mockito verification.  A tiny explicit exclusion map is allowed only
for actions that are unsafe to execute inside the shared automated JVM.
"""
from __future__ import annotations
import argparse, importlib.util, json, re, sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = ROOT / "docs/current/UI-ACTION-BEHAVIOR-COVERAGE.json"
REPORT = ROOT / "docs/current/UI-ACTION-BEHAVIOR-COVERAGE-UA.md"

spec = importlib.util.spec_from_file_location("ui_action_coverage", ROOT / "tools/ui-action-coverage-check.py")
coverage = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(coverage)

EXCLUDED_METHODS = {
    "if", "for", "while", "switch", "catch", "return", "new", "super", "this",
    "run", "accept", "apply", "test", "text", "format", "requireNonNull", "of", "valueOf",
    "isBlank", "isEmpty", "size", "getScene", "getWindow", "orElse", "orElseGet", "stream",
    "filter", "map", "findFirst", "findAny", "toList", "collect", "equals", "hashCode",
    "toString", "println", "debug", "info", "warn", "error", "trace", "show", "hide",
    "getText", "getValue", "setValue", "getItems", "getSelectionModel", "isSelected",
    "setSelected", "setText", "setDisable", "setVisible", "setManaged", "close", "fire",
    "add", "remove", "clear", "refresh", "get", "set",
}

# Exact semantic action exclusions only.  Do not add convenience exclusions for merely hard tests.
JUSTIFIED_EXCLUSIONS = {
    ("FXML", "MainController", "handleExit"): (
        "Platform.exit() terminates the shared JavaFX runtime; FXML wiring/fingerprint is still checked, "
        "while real process exit is covered by packaged launcher/release smoke."
    ),
}

WORD_RE = re.compile(r"\b[A-Za-z_][A-Za-z0-9_]*\b")


def source_and_body(entry: dict) -> tuple[str, str]:
    if entry["kind"] == "FXML":
        controller = entry["controller"]
        source_path = ROOT / "myhomelib-ui/src/main/java" / Path(*controller.split(".")).with_suffix(".java")
        source = source_path.read_text(encoding="utf-8", errors="ignore")
        return source, coverage.method_body(source, entry["action"])
    if entry["kind"] == "WEB":
        source_path = ROOT / "myhomelib-web/src/main/java/com/myhomelibcorp/web/WebPwaAssets.java"
        source = source_path.read_text(encoding="utf-8", errors="ignore")
        body = coverage.method_body(source, "clientScript") + "\n" + coverage.method_body(source, "serviceWorker")
        return source, body
    source_path = ROOT / entry["surface"]
    source = source_path.read_text(encoding="utf-8", errors="ignore")
    receiver = entry["action"].split(".setOnAction")[0]
    ordinal = int(entry["action"].split("[")[1].split("]")[0])
    matches = [m for m in re.finditer(r"([A-Za-z_][A-Za-z0-9_]*)\.setOnAction\s*\(", source)
               if m.group(1) == receiver]
    if ordinal < 1 or ordinal > len(matches):
        raise AssertionError(f"cannot locate callback for {entry['key']}")
    return source, coverage.callback_text(source, matches[ordinal - 1].end())


def behavior_tokens(entry: dict, body: str) -> list[str]:
    tokens: list[str] = []
    if entry["kind"] == "FXML":
        tokens.append(entry["action"])
    for _, method in re.findall(r"\b([A-Za-z_]\w*)\.([A-Za-z_]\w*)\s*\(", body):
        if method not in EXCLUDED_METHODS and len(method) >= 5:
            tokens.append(method)
    for method in re.findall(r"(?<![.\w])([A-Za-z_]\w*)\s*\(", body):
        if method not in EXCLUDED_METHODS and method and method[0].islower() and len(method) >= 5:
            tokens.append(method)
    for callback in re.findall(r"\b(on[A-Z][A-Za-z0-9_]+)\b", body):
        tokens.append(callback)
        tokens.append("set" + callback[0].upper() + callback[1:])
    if entry["kind"] == "PROGRAMMATIC":
        receiver = entry["action"].split(".setOnAction")[0]
        tokens.append(receiver)
        tokens.append("get" + receiver[0].upper() + receiver[1:])
    elif entry["kind"] == "WEB":
        tokens.append(entry["control"])
        tokens.append({
            "offline-save": "SAVE_BOOK",
            "offline-remove": "REMOVE_BOOK",
            "offline-clear": "CLEAR_OFFLINE_BOOKS",
        }[entry["action"]])
    seen: set[str] = set()
    return [t for t in tokens if t and not (t in seen or seen.add(t))]


def behavioral_tests() -> tuple[dict[str, list[str]], dict[str, str]]:
    index: dict[str, list[str]] = defaultdict(list)
    texts: dict[str, str] = {}
    for path in ROOT.glob("myhomelib-*/src/test/**/*.java"):
        # Opt-in real-data/performance probes are observational diagnostics, not stable
        # action-specific UI behavior contracts. Do not let generic words inside probes
        # (e.g. add/next/sleep) become accidental evidence for programmatic controls.
        if path.name.endswith("ProbeTest.java"):
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        if "@Test" not in text or coverage.ASSERTION_RE.search(text) is None:
            continue
        rel = path.relative_to(ROOT).as_posix()
        texts[rel] = text
        for word in set(WORD_RE.findall(text)):
            index[word.lower()].append(rel)
    return index, texts


def collect() -> list[dict]:
    inventory = coverage.fxml_inventory() + coverage.programmatic_inventory() + coverage.web_inventory()
    index, texts = behavioral_tests()
    prepared: list[tuple[dict, list[str]]] = []
    primary_owners: dict[str, set[str]] = defaultdict(set)
    for entry in inventory:
        _, body = source_and_body(entry)
        tokens = behavior_tokens(entry, body)
        prepared.append((entry, tokens))
        owner = entry["controller"].split(".")[-1]
        if entry["kind"] == "FXML":
            primary_owners[entry["action"].lower()].add(owner)
        elif entry["kind"] == "PROGRAMMATIC":
            receiver = entry["action"].split(".setOnAction")[0]
            primary_owners[receiver.lower()].add(owner)
            primary_owners[("get" + receiver[0].upper() + receiver[1:]).lower()].add(owner)

    result: list[dict] = []
    unresolved: list[str] = []
    for entry, tokens in prepared:
        owner_token = entry["controller"].split(".")[-1]
        candidates: set[str] = set()
        primary = {entry["action"].lower()} if entry["kind"] == "FXML" else set()
        if entry["kind"] == "PROGRAMMATIC":
            receiver = entry["action"].split(".setOnAction")[0]
            primary.update({receiver.lower(), ("get" + receiver[0].upper() + receiver[1:]).lower()})
        if entry["kind"] == "WEB":
            command = {
                "offline-save": "SAVE_BOOK",
                "offline-remove": "REMOVE_BOOK",
                "offline-clear": "CLEAR_OFFLINE_BOOKS",
            }[entry["action"]]
            marker = entry["control"]
            for evidence in entry.get("evidence", []):
                text = texts.get(evidence)
                if text is not None and (marker in text or command in text):
                    candidates.add(evidence)
        else:
            for token in tokens:
                for path in index.get(token.lower(), []):
                    words = {w.lower() for w in WORD_RE.findall(texts[path])}
                    if token.lower() in primary:
                        # Programmatic control names such as search/stop/preview are frequently generic.
                        # Always scope them to the owning UI class; otherwise any unrelated test that
                        # happens to contain the same word becomes accidental behavioral evidence.
                        # FXML handler names remain directly identifying unless reused by several owners.
                        if entry["kind"] == "PROGRAMMATIC" and owner_token.lower() not in words:
                            continue
                        if entry["kind"] != "PROGRAMMATIC" and len(primary_owners[token.lower()]) > 1 and owner_token.lower() not in words:
                            continue
                    else:
                        # Delegated method names are only evidence when the test is explicitly scoped
                        # to the owning UI class.  This prevents unrelated service tests that happen to
                        # call a common method (execute/refresh/snapshot/...) from satisfying a UI action.
                        if owner_token.lower() not in words:
                            continue
                    # Reactive Streams subscriber callbacks are generic names.
                    # A shared HTTP test calling subscriber.onNext must never
                    # count as evidence for an unrelated JavaFX onNext button.
                    if (entry["kind"] == "FXML" and token.lower() in {
                            "onnext", "onsubscribe", "oncomplete", "onerror"}
                            and not (path.startswith("myhomelib-ui/src/test/")
                                     or owner_token.lower() in words)):
                        continue
                    candidates.add(path)
        semantic = (entry["kind"], owner_token, entry["action"])
        exclusion = JUSTIFIED_EXCLUSIONS.get(semantic)
        item = {
            "key": entry["key"],
            "kind": entry["kind"],
            "surface": entry["surface"],
            "controller": entry["controller"],
            "action": entry["action"],
            "user_function_ref": entry["user_function_ref"],
            "implementation_fingerprint": entry.get("handler_body_sha256") or entry.get("callback_sha256"),
            "behavior_tokens": tokens,
        }
        if candidates:
            item["status"] = "AUTOMATED_ACTION_SPECIFIC"
            item["evidence"] = sorted(candidates)
        elif exclusion:
            item["status"] = "JUSTIFIED_EXCLUSION"
            item["exclusion_reason"] = exclusion
            item["evidence"] = []
        else:
            item["status"] = "MISSING_ACTION_SPECIFIC_EVIDENCE"
            item["evidence"] = []
            unresolved.append(entry["key"])
        result.append(item)
    if unresolved:
        sample = "\n  - ".join(unresolved)
        raise AssertionError(f"{len(unresolved)} UI actions lack action-specific behavioral evidence:\n  - {sample}")
    return sorted(result, key=lambda x: x["key"])

def write(entries: list[dict]) -> None:
    summary = {
        "actions": len(entries),
        "automated_action_specific": sum(e["status"] == "AUTOMATED_ACTION_SPECIFIC" for e in entries),
        "justified_exclusions": sum(e["status"] == "JUSTIFIED_EXCLUSION" for e in entries),
        "missing": sum(e["status"].startswith("MISSING") for e in entries),
    }
    REGISTRY.write_text(json.dumps({"summary": summary, "entries": entries}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    lines = [
        "# MyHomeLib — action-specific UI behavioral coverage",
        "",
        "Цей реєстр є строгішим доповненням до `UI-ACTION-COVERAGE.json`: generic controller-level test не рахується. "
        "Для кожної дії потрібен exact handler/callback/delegate/control token у JUnit-тесті з observable assertion/Mockito verification, "
        "або вузький documented exclusion.",
        "",
        f"- Inventory: **{summary['actions']}**",
        f"- Automated action-specific: **{summary['automated_action_specific']}**",
        f"- Justified exclusions: **{summary['justified_exclusions']}**",
        f"- Missing: **{summary['missing']}**",
        "",
        "| Surface | Action | Status | Evidence / reason |",
        "|---|---|---|---|",
    ]
    for e in entries:
        ev = e.get("exclusion_reason") or "<br>".join(f"`{Path(x).name}`" for x in e.get("evidence", [])[:4])
        lines.append(f"| `{e['surface']}` | `{e['action']}` | `{e['status']}` | {ev} |")
    REPORT.write_text("\n".join(lines) + "\n", encoding="utf-8")


def validate(entries: list[dict]) -> None:
    if not REGISTRY.exists() or not REPORT.exists():
        raise AssertionError("action-specific behavior registry/report missing; run --write after reviewed test changes")
    saved = json.loads(REGISTRY.read_text(encoding="utf-8"))
    current = {e["key"]: e for e in entries}
    prior = {e["key"]: e for e in saved.get("entries", [])}
    if set(current) != set(prior):
        raise AssertionError("action-specific behavior inventory drift; run reviewed --write")
    changed = [
        key for key, item in current.items()
        if prior[key].get("implementation_fingerprint") != item.get("implementation_fingerprint")
    ]
    if changed:
        raise AssertionError(f"UI action behavior changed without behavior-coverage review ({len(changed)}): " + ", ".join(changed))
    for key, item in current.items():
        old = prior[key]
        if old.get("implementation_fingerprint") != item.get("implementation_fingerprint"):
            raise AssertionError(f"UI action behavior changed without behavior-coverage review: {key}")
        if old.get("status") != item.get("status") or old.get("evidence") != item.get("evidence"):
            raise AssertionError(f"UI action evidence changed without reviewed registry refresh: {key}")
    if saved.get("summary", {}).get("actions") != len(entries) or saved.get("summary", {}).get("missing") != 0:
        raise AssertionError("action-specific behavior summary stale")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    try:
        entries = collect()
        if args.write:
            write(entries)
            print(f"WROTE action-specific UI behavior coverage: {len(entries)}")
        else:
            validate(entries)
        automated = sum(e["status"] == "AUTOMATED_ACTION_SPECIFIC" for e in entries)
        exclusions = sum(e["status"] == "JUSTIFIED_EXCLUSION" for e in entries)
        print(f"PASS: action-specific UI behavior {automated} automated + {exclusions} justified exclusions = {len(entries)}/{len(entries)}")
        return 0
    except Exception as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1

if __name__ == "__main__":
    raise SystemExit(main())
