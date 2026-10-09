# MyHomeLib — Technical Audit Report V17

Date: 2026-09-28  
Baseline: `myhomelib-enterprise-optimized-v16-checkpoint-2026-09-28.zip`  
Audit lineage: V13 → V14 → V15 → V16 → V17.

## Executive status

V17 closes the planned P4 staged decomposition from the V12/V15 task plan. The fifth and final boundary was extracted from `InpxImportPipeline` without changing import transaction, persistence, catalog-sync or public API contracts.

Cumulative P4 extraction sequence now completed:

1. `NewReaderWorkspaceController` → `ReaderSidebarSearchCoordinator`
2. `SearchWorkspaceController` → `ContentSearchCoordinator`
3. `DesktopIntegrationBackend` → `DesktopAiIntegration`
4. `VersionedUserDataTransferAdapter` → `ReaderPreferencesTransferStore`
5. `InpxImportPipeline` → `InpxAuthorCacheSupport`

No broad rewrite, rename campaign or unrelated cleanup was performed.

## Fifth decomposition boundary — INPX author-cache policy

`InpxImportPipeline` was 1124 lines before this step and is 1047 lines after extraction.

New package-private `InpxAuthorCacheSupport` (123 lines) owns only author-cache policy:

- bounded LRU author cache sizing;
- preload threshold policy for full snapshots and online deltas;
- safe preload of persistent author IDs when the entire table fits the configured bound;
- compact Bloom-filter seen-name history used to distinguish definitely-new authors from names that require exact DB resolution.

`InpxImportPipeline` keeps thin private delegate wrappers for the existing reflection-based characterization contracts, so no test was weakened merely to enable extraction.

The pipeline still owns:

- import preparation and source identity;
- catalog transaction boundaries;
- cancellation/rollback;
- batch flushing and book persistence;
- catalog revision tracking;
- search fingerprint persistence;
- progress/status reporting;
- local-file index semantics.

## Characterization / regression evidence for the INPX extraction

Before extraction, the relevant characterization set was green:

- `InpxImportPipelineTest`: 15/15 PASS;
- `ImportIndexLifecycleTest` + `JdbcBatchWriterStage6Test` + `InpxReaderCompatibilityTest`;
- combined targeted set: **31 tests, 0 failures, 0 errors**.

After extraction, the exact same targeted set is again:

- **31 tests, 0 failures, 0 errors — PASS**.

This covers, among other contracts:

- cancellation during catalog analysis;
- bounded large-catalog batch sizing;
- unchanged online snapshot replay skip;
- no inferred deletion from snapshot absence;
- existing-catalog constant-time probes;
- author-cache eviction/exact-resolution fallback;
- preload only when the complete author table fits the bound;
- large-catalog preload fallback;
- selective preview gating;
- Stage6 writer semantics and INPX reader compatibility.

## Full Maven verification on final V17 source

The final V17 source state was verified under the supplied offline Apache Maven **3.9.6** with:

- Java 21;
- `LANG=C.UTF-8`;
- `LC_ALL=C.UTF-8`;
- UTF-8 JVM file encoding;
- Xvfb for JavaFX-dependent tests.

Command class: full reactor `clean verify`.

Result:

- **20/20 reactor modules SUCCESS**;
- **BUILD SUCCESS**;
- total time: **4:15**;
- aggregate Surefire: **1789 tests, 0 failures, 0 errors, 17 skipped**;
- infrastructure module: **637 tests, 0 failures, 0 errors, 9 skipped**;
- reader: 137 tests, 1 skipped;
- UI: 226 tests, 0 skipped;
- bootstrap: 52 tests, 2 skipped;
- E2E: 15 tests, all PASS;
- benchmark module: 3 benchmark tests intentionally skipped.

The supplied Maven archive contains 3.9.6. The project wrapper still requires **Maven 3.9.16**. Exact-version 3.9.16 release acceptance therefore remains PENDING; it is not silently treated as equivalent to the 3.9.6 evidence above.

## Release/package downstream contracts

On the final V17 source state:

- production package (`-Pproduction -DskipTests`): **BUILD SUCCESS**, 15/15 modules;
- Linux `jpackage` app-image: **PASS**;
- packaged launcher smoke: `MYHOMELIB_RELEASE_SMOKE_OK` — **PASS**;
- Stage23 cross-platform release artifact validation: **PASS**;
- static release check: **PASS**;
- XML/POM/FXML: 49, 0 errors;
- FXML handler references: 261, 0 missing;
- SQLite migrations: 63, integrity OK;
- root shell scripts: 11, 0 static issues.

Packaging warnings retained as known environment/product constraints:

- bundled libmobi 0.12 runtime/source is absent, so MOBI/AZW conversion requires a compatible configured runtime `mobitool`;
- `SHA256SUMS` is absent inside generated `dist`, so Stage23 emits a warning, not a failure.

## Architecture status

`tools/architecture-check.py` still reports exactly one known debt group and no new debt from the P4 extractions:

- `DeferredMainFeatureActions`
- `ReaderBookMapCoordinator`
- `ReaderBookmarkCoordinator`

Ratchets remain unchanged:

- UI output-port users: 15/18 baseline;
- non-value domain-model users: 28/28 baseline.

The Maven architecture-test module itself is green in the full reactor; the standalone ratchet intentionally remains non-zero until the known debt group is resolved.

## Source delta

V16 → V17 production source delta before V17 documentation:

- modified: 1 file — `InpxImportPipeline.java`;
- added: 1 file — `InpxAuthorCacheSupport.java`;
- deleted: 0 files.

Cumulative P4 source/test delta from pristine V15 through V17:

- modified: 8 source/test files;
- added: 6 source/test files;
- deleted: 0 source/test files.

## Release decision for this checkpoint

V17 is the recommended clean source baseline for the next audit session. The planned large-class P4 decomposition is complete and the final state is green under the supplied Maven 3.9.6 full reactor plus production packaging/jpackage/smoke gates.

V17 is not labelled a final production release candidate until the remaining external/platform acceptance items are completed:

1. exact Maven 3.9.16 full gate;
2. Windows x64 packaged acceptance;
3. Windows Calibre cancellation/process-tree/temp-workspace acceptance;
4. resolution/triage of the existing architecture debt group.
