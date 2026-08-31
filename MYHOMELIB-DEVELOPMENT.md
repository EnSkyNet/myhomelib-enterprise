# MYHOMELIB — Development and Validation

**Version:** 7.1.0  
**Java:** 21

## Build commands

Fast platform scripts:

```bash
./build.sh
./run.sh
./package.sh
```

PowerShell equivalents:

```powershell
.\build.ps1
.\run.ps1
.\package.ps1
```

Full release build/test gate:

```bash
./mvnw clean verify -Pproduction
```

The Maven Wrapper may need external access on first use unless Maven/dependencies are already cached.

## Architecture checks

Offline source/POM guard:

```bash
python3 tools/architecture-check.py
```

Compiled ArchUnit gate:

```bash
./mvnw -pl myhomelib-architecture-tests -am test
```

Architecture changes must update both documentation and corresponding ratchets/tests. Do not weaken a check merely to preserve an obsolete stage assumption; update stale tests only when the production contract intentionally changed.

## Regression/static gates

`tools/` contains focused checks for import/index lifecycle, online updates/downloads, SQLite migrations/concurrency, UI/FXML reachability, Reader behavior, OPDS, backup/restore, performance guardrails and release packaging.

The checks are intentionally independent where practical so important invariants can be validated even when Maven Central is unavailable. They are not a substitute for the full Maven test reactor.

## Cross-platform CI

`.github/workflows/ci-release.yml` runs JDK 21 verification on:

- Ubuntu;
- Windows;
- macOS.

The release workflow requires the Maven verification matrix before packaging/publishing. Platform packaging uses JDK `jpackage --type app-image`, then exercises a headless `--release-smoke` path before accepting the artifact. Tagged releases include SHA-256 checksums.

Normal application startup does not download Maven artifacts; dependency resolution is a build-time concern only.

## Performance baseline

The repository keeps machine-readable performance evidence under `docs/` / `docs/release/`, including `docs/performance-baseline.json` and raw JSON benchmark outputs. The active contract is:

- large catalogue operations remain bounded/streaming;
- navigation/facets use indexed bounded SQL rather than materializing the whole catalogue;
- Lucene source traversal avoids progressive `OFFSET` behavior;
- import/index enrichment avoids per-book N+1 patterns;
- startup avoids catalogue-wide synchronous scans;
- Reader parsing/layout/resource caching remains bounded for large books.

Stored SQLite guardrails include representative 100k/500k/1M profiles. Synthetic timings are regression evidence for the measured environment, not universal hardware performance promises.

Reproducible helpers include:

- `tools/stage24-performance-baseline.py`;
- `tools/inpx-batch-index-benchmark.py`;
- `tools/duplicate-index-benchmark.py`;
- `myhomelib-benchmark` JVM/Lucene/Reader probes.

The dedicated performance Maven profile and scheduled/manual GitHub workflow must be used for JVM heap/GC, disk-backed Lucene and Reader baselines when dependency access is available.

## Database migrations

Flyway migrations are release history. Never edit/reorder an already released migration to make a new test pass. Add a new migration and provide an upgrade regression from representative old data.

The release gates verify the migration chain and historical baseline integrity.

## Online download development rules

- keep the `ConnectionScript` grammar declarative;
- never log/persist `%PASS%` or decrypted secrets;
- validate payload before atomic replace;
- do not mark `local=true` merely because an HTTP request succeeded;
- use actual physical file/member resolution, not only a stale database flag;
- keep archive-entry matching centralized in `ArchiveEntryNameSupport`;
- exact member match wins; safe fallback must remain unambiguous;
- avoid validating the same downloaded archive repeatedly in one operation;
- preserve previous valid local data when a forced refresh fails.

## UI/threading rules

- JavaFX scene-graph work stays on the FX thread;
- network/filesystem/large SQL/index work stays off the FX thread;
- no `Thread.sleep()` in JavaFX UI flows;
- row selection and batch checkbox selection are distinct concepts;
- use one application/coordinator entry point for actions such as download/open rather than duplicating UI-specific flows.

## Reader rules

Reader core/format/layout must stay independent from JavaFX and library persistence. JavaFX rendering belongs in `reader.render.javafx`. Source offsets, whitespace around inline FB2 tags, TOC anchors, multi-document ZIP behavior and position retry semantics are regression-sensitive and should have behavior tests.

## Windows / IntelliJ terminal encoding

If Cyrillic output is corrupted in the IntelliJ terminal on Windows, use a UTF-8 PowerShell session. A practical Shell path is:

```text
powershell.exe -NoExit -Command "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; chcp 65001"
```

This is a terminal-encoding setting only; it does not change project source encoding, which remains UTF-8.

## Documentation rule

Active project documentation is limited to:

- `README.md`;
- `ARCHITECTURE.md`;
- `MYHOMELIB-FEATURES.md`;
- `MYHOMELIB-OPERATIONS.md`;
- `MYHOMELIB-DEVELOPMENT.md`;
- `MYHOMELIB-RELEASE.md`.

Historical summaries live in `docs/history/`; original legacy notes live in `docs/archive/source-notes/`. Runtime help/localization Markdown is not part of this documentation consolidation.
