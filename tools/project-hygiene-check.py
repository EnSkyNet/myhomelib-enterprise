#!/usr/bin/env python3
"""Fail-closed source-tree hygiene checks for release/external-test source packages."""
from __future__ import annotations

import hashlib
import re
import sys
import urllib.parse
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
errors: list[str] = []


def fail(message: str) -> None:
    errors.append(message)


# Canonical current documentation must exist.
canonical = [
    "README.md",
    "ARCHITECTURE.md",
    "docs/README.md",
    "docs/current/FEATURES.md",
    "docs/current/USER-FUNCTIONS-UA.md",
    "docs/current/ARCHITECTURE-AUDIT-UA.md",
    "docs/current/FUNCTIONAL-AUDIT-UA.md",
    "docs/current/UI-ACTION-COVERAGE.json",
    "docs/current/UI-ACTION-BEHAVIOR-COVERAGE.json",
    "docs/current/OPERATIONS.md",
    "docs/current/DEVELOPMENT.md",
    "docs/current/RELEASE.md",
    "docs/history/HISTORY-UA.md",
    "docs/release/CURRENT-VALIDATION.md",
    "docs/release/WHAT-REMAINS-UA.md",
    "docs/release/EXTERNAL-ACCEPTANCE-RUNBOOK.md",
    "docs/release/EXTERNAL-TEST-PLAN.md",
    "docs/release/POST-R5-LIVE-ACCEPTANCE-RUNBOOK-UA.md",
]
for rel in canonical:
    if not (ROOT / rel).is_file():
        fail(f"missing canonical document: {rel}")

# Historical/task/checkpoint documents belong under docs/history, not repository root.
for pattern in ("CHECKPOINT*", "NEXT*", "TASKS*", "AUDIT*"):
    for path in ROOT.glob(pattern):
        if path.is_file():
            fail(f"historical/task document must not live in repository root: {path.name}")

# Reactor modules must resolve to real module POMs.
root_pom = ET.parse(ROOT / "pom.xml").getroot()
modules = [
    n.text.strip()
    for n in root_pom.findall("./m:modules/m:module", NS)
    if n.text and n.text.strip()
]
for module in modules:
    if not (ROOT / module / "pom.xml").is_file():
        fail(f"reactor module has no module POM: {module}")


# Duplicate Maven dependency/plugin declarations inside one POM are not allowed.
for pom in ROOT.rglob("pom.xml"):
    if "target" in pom.parts:
        continue
    root = ET.parse(pom).getroot()
    dep_seen: dict[tuple[str, str, str, str, str], int] = defaultdict(int)
    for dep in root.findall("./m:dependencies/m:dependency", NS):
        key = (
            dep.findtext("m:groupId", default="", namespaces=NS),
            dep.findtext("m:artifactId", default="", namespaces=NS),
            dep.findtext("m:type", default="jar", namespaces=NS),
            dep.findtext("m:classifier", default="", namespaces=NS),
            dep.findtext("m:scope", default="compile", namespaces=NS),
        )
        dep_seen[key] += 1
    for key, count in dep_seen.items():
        if count > 1:
            fail(f"duplicate Maven dependency declaration in {pom.relative_to(ROOT)}: {key}")
    plugin_seen: dict[tuple[str, str], int] = defaultdict(int)
    for plugin in root.findall("./m:build/m:plugins/m:plugin", NS):
        key = (
            plugin.findtext("m:groupId", default="org.apache.maven.plugins", namespaces=NS),
            plugin.findtext("m:artifactId", default="", namespaces=NS),
        )
        plugin_seen[key] += 1
    for key, count in plugin_seen.items():
        if count > 1:
            fail(f"duplicate Maven plugin declaration in {pom.relative_to(ROOT)}: {key}")

# Review classpath resource-name collisions. Two are intentional: service descriptors in
# separate plugin artifacts and the updater trust-key resource duplicated because the updater
# is standalone/JDK-only. Any new collision requires an explicit decision here.
resource_names: dict[str, list[Path]] = defaultdict(list)
for resource_root in ROOT.glob("*/src/main/resources"):
    if not resource_root.is_dir():
        continue
    for path in resource_root.rglob("*"):
        if path.is_file():
            resource_names[path.relative_to(resource_root).as_posix()].append(path)
allowed_resource_collisions = {
    "META-INF/services/com.myhomelibcorp.plugin.api.PluginEntrypoint",
    "update/trusted-keys.properties",
}
for name, paths in resource_names.items():
    if len(paths) > 1 and name not in allowed_resource_collisions:
        fail(f"unreviewed resource path collision {name}: {[str(p.relative_to(ROOT)) for p in paths]}")

trust_paths = resource_names.get("update/trusted-keys.properties", [])
if len(trust_paths) != 2:
    fail(f"expected exactly two updater trust-key resources, found {len(trust_paths)}")
else:
    def properties(path: Path) -> dict[str, str]:
        result: dict[str, str] = {}
        for raw in path.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if not line or line.startswith(("#", "!")):
                continue
            if "=" not in line:
                fail(f"invalid trust-key property line in {path.relative_to(ROOT)}: {raw}")
                continue
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()
        return result
    if properties(trust_paths[0]) != properties(trust_paths[1]):
        fail("standalone/main updater trust-key resources have different semantic key entries")

# Duplicate Java FQCNs are never allowed. Filename is the canonical top-level type name here.
fqcn: dict[str, list[str]] = defaultdict(list)
package_re = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.MULTILINE)
for path in ROOT.rglob("*.java"):
    if any(part in {"target", ".git"} for part in path.parts):
        continue
    text = path.read_text(encoding="utf-8", errors="replace")
    match = package_re.search(text)
    package = match.group(1) if match else ""
    name = f"{package}.{path.stem}" if package else path.stem
    fqcn[name].append(path.relative_to(ROOT).as_posix())
for name, paths in fqcn.items():
    if len(paths) > 1:
        fail(f"duplicate Java FQCN {name}: {paths}")

# Flyway versions must be unique inside each migration location. Separate DBs may reuse V1..Vn.
for location in sorted((ROOT / "myhomelib-infrastructure/src/main/resources/db").glob("migration*")):
    if not location.is_dir():
        continue
    versions: dict[str, list[str]] = defaultdict(list)
    for path in location.glob("V*__*.sql"):
        match = re.match(r"V([^_]+)__", path.name)
        if match:
            versions[match.group(1)].append(path.name)
    for version, names in versions.items():
        if len(names) > 1:
            fail(f"duplicate Flyway version V{version} in {location.relative_to(ROOT)}: {names}")

# Verify all local Markdown links, including historical documents. External URLs are not resolved here.
md_link = re.compile(r"(?<!!)\[[^\]]*\]\(([^)]+)\)")
checked_links = 0
for path in ROOT.rglob("*.md"):
    if any(part in {"target", ".git"} for part in path.parts):
        continue
    text = path.read_text(encoding="utf-8", errors="replace")
    for match in md_link.finditer(text):
        raw = match.group(1).strip()
        if raw.startswith("<") and raw.endswith(">"):
            raw = raw[1:-1]
        target = raw.split(' "', 1)[0].split(" '", 1)[0].strip()
        if not target or target.startswith(("#", "http://", "https://", "mailto:", "data:")):
            continue
        target = urllib.parse.unquote(target.split("#", 1)[0])
        if not target:
            continue
        checked_links += 1
        resolved = (path.parent / target).resolve()
        try:
            resolved.relative_to(ROOT.resolve())
        except ValueError:
            fail(f"Markdown link escapes repository: {path.relative_to(ROOT)} -> {target}")
            continue
        if not resolved.exists():
            fail(f"broken Markdown link: {path.relative_to(ROOT)} -> {target}")

# Current/release docs must not contain byte-identical duplicate artifacts.
for base in (ROOT / "docs/current", ROOT / "docs/release"):
    hashes: dict[str, list[str]] = defaultdict(list)
    for path in base.rglob("*"):
        if path.is_file():
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            hashes[digest].append(path.relative_to(ROOT).as_posix())
    for paths in hashes.values():
        if len(paths) > 1:
            fail(f"byte-identical duplicate current/release documents: {paths}")

# A source package must stay free of generated build/cache material.
trash_dirs = {"target", "__pycache__", ".pytest_cache", ".idea", ".gradle"}
trash_files = {".DS_Store", "Thumbs.db"}
for path in ROOT.rglob("*"):
    rel = path.relative_to(ROOT)
    if path.is_dir() and path.name in trash_dirs:
        fail(f"generated directory in source tree: {rel}")
    elif path.is_file() and (path.name in trash_files or path.suffix == ".pyc"):
        fail(f"generated/cache file in source tree: {rel}")

if errors:
    print("PROJECT HYGIENE CHECK: FAIL")
    for message in errors:
        print(" -", message)
    raise SystemExit(1)

print("PROJECT HYGIENE CHECK: PASS")
print(f" - reactor child modules: {len(modules)}")
print(f" - Java source/test files: {sum(len(v) for v in fqcn.values())}; duplicate FQCN: 0")
print(f" - local Markdown links checked: {checked_links}; broken: 0")
print(" - Flyway versions unique within each migration location")
print(" - current/release exact duplicate docs: 0")
print(" - duplicate Maven dependency/plugin declarations: 0")
print(" - reviewed resource path collisions: 2 intentional; updater trust-key entries match")
print(" - generated build/cache artifacts: 0")
