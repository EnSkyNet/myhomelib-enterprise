#!/usr/bin/env python3
"""Reproducible offline Maven acceptance without requiring `mvn install` or bundled Maven."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def maven_command() -> list[str]:
    wrapper = ROOT / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    if wrapper.is_file():
        return [str(wrapper)]
    for candidate in ("mvn.cmd", "mvn") if os.name == "nt" else ("mvn",):
        resolved = shutil.which(candidate)
        if resolved:
            return [resolved]
    raise SystemExit("Maven 3.9.6+ is required externally; the source archive intentionally bundles no Maven runtime/wrapper.")


def run(command: list[str]) -> None:
    print("+", " ".join(command), flush=True)
    subprocess.run(command, cwd=ROOT, check=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--maven-repo", required=True, help="Path to the prepared external offline Maven repository")
    parser.add_argument("--full", action="store_true", help="Run the full reactor test suite after the targeted acceptance")
    args = parser.parse_args()

    repo = Path(args.maven_repo).expanduser().resolve()
    if not repo.is_dir():
        raise SystemExit(f"Offline Maven repository does not exist: {repo}")
    nested = repo / "maven-offline-repo"
    if nested.is_dir():
        repo = nested
    required = [
        repo / "org/springframework/boot/spring-boot-dependencies/3.5.0/spring-boot-dependencies-3.5.0.pom",
        repo / "org/openjfx/javafx/21.0.2/javafx-21.0.2.pom",
    ]
    missing = [str(path.relative_to(repo)) for path in required if not path.is_file()]
    if missing:
        raise SystemExit("Offline Maven repository is incomplete; missing: " + ", ".join(missing))

    mvn = maven_command()
    base = mvn + ["-o", "-B", "-ntp", f"-Dmaven.repo.local={repo}"]
    run(base + ["test-compile"])
    targeted = (
        "LibraryOperationHistoryUseCaseTest,BatchMetadataEditUseCaseUndoGuardTest,"
        "SqliteOperationHistoryAcceptanceTest,SqliteBookMergeAdapterIntegrationTest,MergeBooksUseCaseTest,"
        "CollectionTransactionExecutorTest,SqliteAnnotationRepositoryIntegrationTest,"
        "SqliteAnnotationManagerQueryAdapterIntegrationTest,SqliteAnnotationExportQueryAdapterIntegrationTest"
    )
    run(base + ["-Dsurefire.failIfNoSpecifiedTests=false", f"-Dtest={targeted}", "test"])
    run([sys.executable, str(ROOT / "tools" / "implementation-completeness-check.py")])
    if args.full:
        run(base + ["test"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
