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

## Refactoring guardrails (2026-09-02)

For new work, preserve the completed stabilization rules: do not run repository/file/index maintenance on the JavaFX Application Thread; acquire the collection-operation coordinator for mutating/maintenance flows; expose long operations through `OperationProgress`/Operation Center; never translate database/index failures into normal empty results; keep interactive search/navigation bounded; and preserve semantic Reader position when layout changes. `REFACTORING_COMPLETION.md` records the current source-level baseline and release boundary.

## Stage 05 real Online Update phase probes

Real P3 measurements are opt-in and must use user-supplied production-sized data; DB, INPX, Lucene index, JFR and logs are never committed or packaged in a code-only checkpoint.

Available real-data probes in `myhomelib-infrastructure`:

- `RealInpxPerformanceProbeTest` — full INPX production importer, including changed-full classification;
- `RealSelectiveLucenePerformanceProbeTest` — builds the baseline Lucene index from a pre-change DB and then measures the exact selective change-set stored in `book_search_state` of the changed DB;
- `RealStatisticsPerformanceProbeTest` — production statistics refresh on a real DB.

For `RealSelectiveLucenePerformanceProbeTest`, pass `-Dmhl.real.seed.db=<pre-change.db>`, `-Dmhl.real.changed.db=<post-change.db>` and optionally `-Dmhl.real.index=<scratch-index-dir>`. The changed DB is expected to contain only the exact changed IDs in `book_search_state`; the probe asserts that the final Lucene document count remains equal to the baseline count.

P3 acceptance must report Download, SHA/preflight, SQLite checkpoint/validation, INPX import, selective/full Lucene, statistics, total duration and peak memory separately. Linux/container results are reproducibility evidence only; the release acceptance numbers still have to be repeated on the target Windows machine.

### Stage 05 P3 comparison probes

The P3 Linux comparison is split into opt-in probes so no benchmark corpus, database or Lucene index is committed:

- `RealOnlineNoOpPerformanceProbeTest` measures only the production orchestration after the downloader has already returned a full snapshot with an applied SHA-256 fingerprint. Network transfer and hashing are intentionally outside this timed region; the test asserts that checkpoint/importer/Lucene/statistics are never called.
- `RealInpxPerformanceProbeTest` measures initial/full/delta production import paths. A synthetic UTF-8 delta made from the Flibusta fallback corpus must retain the UTF-8 BOM at the start of `online.inp`; otherwise an archive without `structure.info` can legitimately select a legacy encoding fallback and is not comparable with the UTF-8 source corpus.
- `RealLucenePerformanceProbeTest` and `RealStatisticsPerformanceProbeTest` measure the initial derived-state rebuild on the production-created initial DB.

Representative Linux/JDK 21 measurements on the Stage 05 real Flibusta corpus:

| Scenario / phase | Result |
| --- | ---: |
| Initial full importer, 562,307 records | 59.865 s |
| Initial full Lucene rebuild, 444,779 docs | 21.485 s |
| Initial statistics refresh | 0.880 s |
| Identical full snapshot, post-download fingerprint fast-path | median 0.390 ms; p95 0.663 ms (100 runs) |
| Changed full snapshot, exactly 1,000 title updates | importer median 11.719 s |
| Small delta containing the same 1,000 title updates | importer 0.509 / 0.582 / 0.444 s; median 0.509 s |

The small-delta runs preserve 562,307 books, 126,317 authors, 675,502 `book_authors`, 796,151 `book_genres`, 117,528 deleted books and report exactly `updated=1000`, `deleted=0`. Using the separately measured checkpoint, selective-Lucene and statistics phases, its assembled post-download phase budget is about 3.43 s; this is a phase sum, not a single wall-clock Online Update measurement.
