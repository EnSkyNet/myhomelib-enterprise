#!/usr/bin/env python3
"""Regression guard: every runtime/product reactor module must be architecture-audited."""
from __future__ import annotations

import ast
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
NON_RUNTIME = {"myhomelib-architecture-tests", "myhomelib-e2e-tests", "myhomelib-benchmark"}

root_pom = ET.parse(ROOT / "pom.xml").getroot()
reactor = {
    node.text.strip()
    for node in root_pom.findall("./m:modules/m:module", NS)
    if node.text and node.text.strip()
}
expected = reactor - NON_RUNTIME

source = (ROOT / "tools" / "architecture-check.py").read_text(encoding="utf-8")
tree = ast.parse(source)
modules = None
for node in tree.body:
    if isinstance(node, ast.Assign):
        for target in node.targets:
            if isinstance(target, ast.Name) and target.id == "MODULES":
                modules = set(ast.literal_eval(node.value))
                break
if modules is None:
    raise SystemExit("FAIL: architecture-check.py does not expose a literal MODULES registry")

archunit_source = (
    ROOT / "myhomelib-architecture-tests/src/test/java/com/myhomelibcorp/architecture/LayerArchitectureTest.java"
).read_text(encoding="utf-8")
match = re.search(r"PRODUCTION_MODULES\s*=\s*List\.of\((.*?)\);", archunit_source, re.S)
if not match:
    raise SystemExit("FAIL: LayerArchitectureTest does not expose a literal PRODUCTION_MODULES registry")
archunit_modules = set(re.findall(r'"(myhomelib-[^"]+)"', match.group(1)))

def drift(label: str, actual: set[str]) -> None:
    missing = sorted(expected - actual)
    stale = sorted(actual - expected)
    if missing or stale:
        raise SystemExit(
            f"FAIL: {label} module coverage drift: "
            f"missing={missing or '-'} stale={stale or '-'}"
        )

drift("architecture-check.py", modules)
drift("LayerArchitectureTest", archunit_modules)
if modules != archunit_modules:
    raise SystemExit(
        "FAIL: architecture guards disagree: "
        f"python_only={sorted(modules - archunit_modules) or '-'} "
        f"archunit_only={sorted(archunit_modules - modules) or '-'}"
    )

print(
    f"PASS: architecture checker + ArchUnit cover the same {len(expected)} "
    "runtime/product reactor modules"
)
