#!/usr/bin/env python3
"""Offline release sanity checks that require only Python 3.

This is intentionally not a replacement for `./mvnw clean verify`: it catches
broken XML/FXML references and validates the raw SQLite migration chain in an
empty file-backed database when Maven dependencies are not available yet.
"""
from __future__ import annotations

import re
import sqlite3
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def xml_checks() -> tuple[int, list[str]]:
    files = sorted(ROOT.rglob("pom.xml")) + sorted((ROOT / "myhomelib-ui/src/main/resources").rglob("*.fxml"))
    errors: list[str] = []
    for path in files:
        try:
            ET.parse(path)
        except Exception as exc:  # noqa: BLE001 - report exact parser failure
            errors.append(f"{path.relative_to(ROOT)}: {exc}")
    return len(files), errors


def fxml_handler_checks() -> tuple[int, int, list[str]]:
    fxml_files = sorted((ROOT / "myhomelib-ui/src/main/resources/view").glob("*.fxml"))
    handlers: list[tuple[Path, str]] = []
    pattern = re.compile(r'\bon[A-Za-z]+\s*=\s*["\']#([A-Za-z_][A-Za-z0-9_]*)["\']')
    for path in fxml_files:
        text = path.read_text(encoding="utf-8")
        handlers.extend((path, name) for name in pattern.findall(text))

    # Static check only: runtime controller injection still belongs to Maven/JavaFX smoke tests.
    java_text = "\n".join(
        path.read_text(encoding="utf-8", errors="replace")
        for path in (ROOT / "myhomelib-ui/src/main/java").rglob("*.java")
    )
    missing = []
    for path, name in handlers:
        if not re.search(rf"\b{re.escape(name)}\s*\(", java_text):
            missing.append(f"{path.relative_to(ROOT)} -> #{name}")
    return len(fxml_files), len(handlers), missing


def migration_checks() -> tuple[int, list[str], str]:
    migration_dir = ROOT / "myhomelib-infrastructure/src/main/resources/db/migration"
    migrations = sorted(
        migration_dir.glob("V*__*.sql"),
        key=lambda p: int(re.match(r"V(\d+)__", p.name).group(1)),
    )
    errors: list[str] = []
    integrity = "not-run"
    with tempfile.TemporaryDirectory(prefix="myhomelib-migrations-") as td:
        db_path = Path(td) / "library.sqlite"
        connection = sqlite3.connect(db_path)
        try:
            for migration in migrations:
                try:
                    connection.executescript(migration.read_text(encoding="utf-8"))
                    connection.commit()
                except Exception as exc:  # noqa: BLE001
                    errors.append(f"{migration.name}: {exc}")
                    connection.rollback()
                    break
        finally:
            connection.close()

        if not errors:
            # Prove that the resulting file can be closed and opened again.
            reopened = sqlite3.connect(db_path)
            try:
                integrity = reopened.execute("PRAGMA integrity_check").fetchone()[0]
                fk_violations = reopened.execute("PRAGMA foreign_key_check").fetchall()
                if integrity != "ok":
                    errors.append(f"PRAGMA integrity_check: {integrity}")
                if fk_violations:
                    errors.append(f"PRAGMA foreign_key_check: {len(fk_violations)} violation(s)")
            finally:
                reopened.close()
    return len(migrations), errors, integrity


def shell_checks() -> tuple[int, list[str]]:
    # Only flag obvious CRLF/shebang problems here; `bash -n` is performed by release scripts.
    scripts = sorted(ROOT.glob("*.sh"))
    errors: list[str] = []
    for path in scripts:
        data = path.read_bytes()
        if not data.startswith(b"#!/"):
            errors.append(f"{path.name}: missing shebang")
        if b"\r\n" in data:
            errors.append(f"{path.name}: CRLF line endings")
    return len(scripts), errors


def main() -> int:
    xml_count, xml_errors = xml_checks()
    fxml_count, handler_count, handler_errors = fxml_handler_checks()
    migration_count, migration_errors, integrity = migration_checks()
    shell_count, shell_errors = shell_checks()

    java_count = sum(1 for _ in ROOT.rglob("*.java"))
    test_count = sum(1 for p in ROOT.rglob("*.java") if "/src/test/" in p.as_posix())
    all_errors = xml_errors + handler_errors + migration_errors + shell_errors

    print(f"XML (POM + FXML): {xml_count}; errors: {len(xml_errors)}")
    print(f"FXML workspaces: {fxml_count}; handler references: {handler_count}; missing: {len(handler_errors)}")
    print(f"SQLite migrations: {migration_count}; errors: {len(migration_errors)}; integrity: {integrity}")
    print(f"Root shell scripts: {shell_count}; static issues: {len(shell_errors)}")
    print(f"Java sources: {java_count}; test sources: {test_count}")

    if all_errors:
        print("\nFAILURES:")
        for error in all_errors:
            print(f"- {error}")
        return 1
    print("OFFLINE STATIC RELEASE CHECK: PASS")
    print("NOTE: this does not replace ./mvnw clean verify or JavaFX/jpackage runtime smoke tests.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
