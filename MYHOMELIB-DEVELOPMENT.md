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

Full release build/test gate from the formal source archive:

```bash
mvn clean verify -Pproduction
```

The formal source archive intentionally contains no Maven runtime or Maven Wrapper payload. Install Maven 3.9.6+ separately. The root build/run/package scripts use the repository wrapper when one exists, otherwise they fall back to `mvn` from `PATH`; dependencies still require network/cache access or the prepared offline repository.

For repeatable isolated validation without the install lifecycle, run `python tools/offline_acceptance.py --maven-repo <repo>`; add `--full` to append the full reactor test suite. This path uses `test-compile`/`test` and therefore does not require `maven-install-plugin`.

## Architecture checks

Offline source/POM guard:

```bash
python3 tools/architecture-check.py
```

Compiled ArchUnit gate:

```bash
mvn -pl myhomelib-architecture-tests -am test
```

Architecture changes must update both documentation and corresponding ratchets/tests. Do not weaken a check merely to preserve an obsolete stage assumption; update stale tests only when the production contract intentionally changed.

## Regression/static gates

`tools/` contains focused checks for import/index lifecycle, online updates/downloads, SQLite migrations/concurrency, UI/FXML reachability, Reader behavior, OPDS, backup/restore, performance guardrails and release packaging.

The checks are intentionally independent where practical so important invariants can be validated even when Maven Central is unavailable. They are not a substitute for the full Maven test reactor.

## Cross-platform CI

`.github/workflows/ci-pr.yml` is the fast pull-request gate. It compiles production/test sources and runs bounded core unit tests, migration/security regressions, ArchUnit, ten E2E journeys, XML/archive security checks and language-catalogue consistency. The job is named `Fast gate`, uses Maven caching and has a hard 10-minute timeout. Failed runs upload Surefire/Failsafe diagnostics.

To make a failed PR gate actually block merge, repository branch protection / a GitHub ruleset must require the `Fast gate` status check on protected branches. This is repository administration state and cannot be enforced by workflow YAML alone.

`.github/workflows/ci-release.yml` runs JDK 21 verification on:

- Ubuntu;
- Windows;
- macOS.

The release workflow requires the Maven verification matrix before packaging/publishing. Platform packaging uses JDK `jpackage --type app-image`, then exercises a headless `--release-smoke` path before accepting the artifact. Tagged releases include SHA-256 checksums.

Normal application startup does not download Maven artifacts; dependency resolution is a build-time concern only.

### Supply-chain CI

- `mvn -Psbom -DskipTests verify` generates aggregate CycloneDX `target/bom.json` and `target/bom.xml` for runtime/compile dependencies.
- `mvn -Pdependency-check -DskipTests verify` runs OWASP Dependency-Check; CVSS **7.0+** is blocking. `NVD_API_KEY` should be configured in GitHub secrets for reliable/rate-friendly NVD updates.
- `security/dependency-check-suppressions.xml` is reviewed policy, not a permanent allowlist. Every future suppression must have an expiry and substantive issue-linked rationale; `tools/supply-chain-policy-check.py` enforces this offline.
- `.github/workflows/codeql.yml` runs Java/Kotlin CodeQL on PR, `main`/`master`, schedule and manual dispatch. The release workflow additionally refuses to proceed while open High/Critical code-scanning alerts exist.

The fast PR unit/E2E job stays bounded; dependency scanning is a separate parallel required check because vulnerability-database refresh time is not suitable for the 10-minute fast-gate budget.

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
- `myhomelib-benchmark` JVM/Lucene/Reader probes;
- `com.myhomelibcorp.benchmark.search.SmartCollectionLuceneBenchmark` for reproducible 500k Smart Collections/Custom Fields query measurements (cold/warm first page, 5/20-rule AND/OR, numeric range, DocValues sort, bounded maxResults, heap/RSS/GC).

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
- production `CompletableFuture.supplyAsync/runAsync` always receives an explicit managed executor;
- use the bounded roles `task`, `io`, `import`, `search`, or `UiBackgroundExecutor` rather than creating private fixed/cached pools;
- never use `CallerRunsPolicy` for background work: saturation must reject explicitly so the FX/caller thread cannot inherit blocking work;
- when an async API returns `CompletableFuture`, convert executor admission rejection into an exceptional future where feasible;
- no `Thread.sleep()` in JavaFX UI flows;
- row selection and batch checkbox selection are distinct concepts;
- use one application/coordinator entry point for actions such as download/open rather than duplicating UI-specific flows.

Run `python3 tools/managed-executor-check.py` when changing asynchronous execution or executor configuration.

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

Historical summaries live in `docs/history/`; original legacy notes live in `docs/history/source-notes/`. Runtime help/localization Markdown is not part of this documentation consolidation.

## Refactoring guardrails (2026-09-02)

For new work, preserve the completed stabilization rules: do not run repository/file/index maintenance on the JavaFX Application Thread; acquire the collection-operation coordinator for mutating/maintenance flows; expose long operations through `OperationProgress`/Operation Center; never translate database/index failures into normal empty results; keep interactive search/navigation bounded; and preserve semantic Reader position when layout changes. `docs/history/records/REFACTORING_COMPLETION.md` records the current source-level baseline and release boundary.

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

### Reloadable JavaFX controller lifecycle

- FXML controllers must be created per load through `FxmlLoaderFactory`; do not switch controller factories back to `ApplicationContext.getBean(...)` for reloadable views.
- Controllers that subscribe to `ApplicationState`, shared ViewModels or long-lived services must implement `WorkspaceLifecycle` and release those subscriptions in `dispose()`.
- Use `UiSubscriptions` for long-lived observable/list registrations; `close()` is idempotent and registration after close is an error.
- Database/network/file work initiated from `setBookId`, `loadGroups` or equivalent UI methods must execute through `UiBackgroundExecutor`, not directly on the JavaFX Application Thread.
- Async UI completions must use `UiAsyncRequestGuard` (generation + collection identity) when a newer request or collection switch can make a result stale.
- For user-visible async loads provide distinct loading, empty/not-found and error states. Preserve navigation intent when a target is requested before an async list finishes loading.

Regression gates: `UiSubscriptionsLifecycleTest`, `FxmlLoaderFactoryLifecycleTest`, `AsyncWorkspaceControllerContractTest`, `UiAsyncRequestGuardTest`.

## Iteration 11 regression rules

- Classic book metadata edit belongs to `EditBookUseCase`; do not reintroduce direct repository/Lucene writes in JavaFX services.
- Book author/genre lists are read-only to callers. Use aggregate/repository population methods rather than mutating getter results.
- PR CI includes transactional edit fault injection and async Classic edit contract tests.\n\n## Iteration 12 privacy/filesystem regression rules\n\n- Never add raw log/release-file copying back to `SupportBundleService`; text content must pass through the sanitizer and bounded-input path.\n- New sensitive setting names or diagnostic fields require a sanitizer/golden-bundle regression case. Absolute settings paths must be redacted as whole values, not merely prefixed with an alias that leaves private suffixes visible.\n- External-reader materialization must use `ExternalReaderMaterializationCache`; do not reintroduce `deleteOnExit` for book content.\n- If a real `Process` handle exists, retain the materialized-file lease until process exit. If no handle exists, prefer next-startup cleanup over premature deletion.\n- PR CI runs `SupportBundleServicePrivacyTest`, `ExternalReaderMaterializationCacheTest`, `RunBookActionMaterializationLifecycleTest` and `tools/privacy-temp-lifecycle-check.py`.\n

## Critical UI localization gate

Critical Search/Reader/Import/OPDS/Backup JavaFX code must use stable `ui.*` / `common.*` keys for user-facing programmatic text. Before committing localization changes run:

```bash
python3 tools/check-critical-ui-localization.py
python3 tools/validate-language-catalogs.py
```

The critical gate rejects user-facing Cyrillic string literals and legacy `LocalizationService.tr(...)` calls on the guarded files, requires every referenced stable key in Ukrainian/English/Bulgarian, requires root and bundled catalogues to be identical, and rejects cross-language `%` placeholder-signature drift. Keep internal log messages out of the UI contract; do not silence genuine UI text with scanner exclusions.



## Iteration 15 startup regression rules

- Keep desktop backend startup behind `StartupOrchestrator`; `MyHomeLibApp` should only submit the orchestrator and marshal completion back to JavaFX.
- The guarded order is `RecoveryStartupTask`, `MigrationStartupTask`, `SearchStartupTask`, `BackupStartupTask`, `OPDSStartupTask`.
- Recovery/migration are `REQUIRED`; search/backup/OPDS are `BEST_EFFORT`. A required failure stops later tasks; a best-effort failure must be visible as degraded startup.
- Recovery must execute before SQLite is opened. Migration/collection activation must not hide a Lucene rebuild; search reuse/rebuild remains a separate task.
- If startup fails after a collection was opened, close the collection through `CollectionLifecycleService`; do not leave a half-started datasource/index behind.
- Run `python3 tools/startup-orchestration-check.py` and `python3 tools/startup-nonblocking-check.py` with the startup tests before changing this pipeline.


## Artifact integrity / Library Health development rules

- Artifact integrity checks are diagnostic only: do not change/delete book files or silently rewrite `book_artifacts.sha256` / `size_bytes`.
- Keep audit baseline/cache in `artifact_integrity_state`; catalog metadata remains the authoritative baseline when it exists.
- Incremental audit may skip content hashing only when the physical file size + mtime signature is unchanged and a prior observed hash exists.
- Archive readability failures and missing files are distinct from content-change findings.
- Library Health refresh is a maintenance operation and must acquire `LibraryOperationCoordinator` so it cannot race a collection switch/import/update.
- Dashboard filesystem/SQLite/Lucene work stays off the JavaFX thread.
- `app.health.backup-stale-hours` is a configurable warning threshold (default 168 hours), not a performance or retention SLA.
- The Smart Collections benchmark records measurements; do not turn one host's numbers into a product latency promise.

## Iteration 35 — annotation foundation

MHL-201/MHL-202 add the backend foundation for highlights/notes: immutable renderer-neutral anchors, exact quote/context relocation, application repository/service contracts, Flyway V57 persistence, and portable user-data schema v4. MHL-203 Reader UI remains the next consumer and must use `AnnotationService` rather than SQLite directly.

## Iteration 36 — MHL-203 Reader highlight/note creation

- Reader selection is represented by source-text offsets plus chapter/paragraph metadata and bounded quote context (`ReaderSelection`).
- Existing Shift+drag selection now has draggable endpoint handles; Shift+Left/Right extends a selection from the current source-text position.
- The selection context menu exposes Highlight, Note, Copy and Clear; keyboard shortcuts `Ctrl+Shift+H` / `Ctrl+Shift+N` invoke annotation actions.
- `ReaderAnnotationOverlay` keeps renderer state independent of the annotation domain. Overlays redraw from source offsets after reflow/layout changes.
- `NewReaderWorkspaceController` invokes `AnnotationService` only through the application boundary and performs database work on `UiBackgroundExecutor`.
- Persisted anchors are resolved after reopen through exact offsets first and quote/context relocation only when needed. Full document text is materialized lazily on the background path.
- Artifact binding is conservative: the opened legacy file projection is matched to a concrete `BookArtifact`; no uncertain artifact id is assigned or silently switched.

## Iteration 37 — MHL-204 Annotation Manager

MHL-204 adds a global annotation workspace over a dedicated application query contract. `SqliteAnnotationManagerQueryAdapter` performs bounded search/filter/count/facet queries, while the JavaFX controller stays asynchronous and application-only. Delete/undo uses a complete application snapshot, and `WorkspaceManager.showAnnotationInReader` supplies a one-shot annotation id so Reader can resolve the persisted anchor before jumping. Filtered CSV export is intentionally a simple manager capability; MHL-205 remains responsible for configurable Markdown/JSON/HTML exporters.

## Iteration 38 — MHL-205 rich annotation export

Rich annotation export is split across the existing clean boundary. `myhomelib-application` owns export request/selection/templates, the stable flattened export projection and streaming serialization; its `AnnotationExportQueryPort` asks storage for bounded deterministic pages. `SqliteAnnotationExportQueryAdapter` implements selection and metadata/tag projection with parameterized SQLite queries. `AnnotationManagerWorkspaceController` only gathers format/scope/template choices and submits the application service through `UiBackgroundExecutor`. Final files are created by writing a sibling temporary file and publishing only after successful completion, so cancellation/error leaves an existing destination untouched.

## Iteration 38 — MHL-206 PDF Reader v1

PDF rendering is implemented as a separate renderer adapter rather than extending the FB2/EPUB text layout engine. `PdfDocumentSession` owns Apache PDFBox 3.0.8 document lifecycle, page metadata, rasterization and a bounded LRU cache; source bytes are copied in bounded chunks to a session-owned temporary file and PDFBox stream scratch data is file-backed. `PdfReaderView` owns only JavaFX presentation and schedules render jobs on a single daemon executor; callbacks are accepted only when both the session and generation token still match. `NewReaderWorkspaceController.prepareOpen` opens the PDF session on the existing background open path, while apply/open/save/close reuse the normal Reader lifecycle and persistence services. The v1 position maps the zero-based PDF page to `ReaderPosition.textOffset`, allowing existing save/restore infrastructure to remain unchanged.

Iteration 40 supplies PDFBox/JBIG2 to the build only through the external offline dependency repository and keeps the formal source package Maven/dependency-binary free. MHL-207 outline/search/bookmark work stays behind the Reader boundary, uses bounded/cancellable document tasks, does not add OCR, and does not introduce alternate annotation persistence or UI-to-PDFBox/SQLite bypasses.

## Iteration 39 — Reader DictionaryProvider / TranslationProvider rules

- `myhomelib-reader` may expose text-selection callbacks but must not depend on application provider contracts, Spring, JDBC or concrete provider adapters.
- Dictionary/translation orchestration belongs to `myhomelib-application`; provider adapters belong to `myhomelib-infrastructure`; JavaFX depends only on application services and neutral Reader APIs.
- Default provider selection is offline-first. Remote translation providers are disabled unless explicitly enabled.
- No selection observer may invoke translation. Remote text transmission requires an explicit Reader action plus a privacy confirmation immediately before invocation.
- Remote endpoints must be HTTPS. Do not add trust-all TLS behavior.
- Persisted secrets such as API keys/tokens must use the existing authenticated `EncryptionUtil` envelope; runtime system properties/environment variables may supply ephemeral credentials. Do not put credentials into URLs or user-visible provider errors.
- `TextProviderRequestContext` provides bounded deadline/cancellation. Reader close/book switch must cancel active requests and stale async completions must be ignored.
- Local dictionary format: UTF-8 TSV `language<TAB>headword<TAB>part-of-speech<TAB>definition<TAB>examples`, with examples separated by ` | `. Optional path: `dictionary.local.path`; default custom file is `config/dictionary.tsv`.
- Local translation format: UTF-8 TSV `source-language<TAB>target-language<TAB>source-text<TAB>translated-text`. Optional path: `translation.local.path`; default custom file is `config/translations.tsv`.
- Remote settings are namespaced under `translation.deepl.*`, `translation.google.*` and `translation.custom.*`; all remote providers remain opt-in.


## Iteration 41 — shared undo closure and transaction debt

Iteration 41 re-validates MHL-112 on the current reactor and adds application acceptance for shared undo dispatch/race refusal. The V53 operation journal remains unchanged. Annotation SQLite repository/query/export adapters now share `CollectionTransactionExecutor`, removing the historical exact clone while preserving current-collection transaction semantics and rollback. `tools/offline_acceptance.py` provides the supported no-`install` offline validation path.

## Plugin SDK development

For third-party extension work, depend on `myhomelib-plugin-api` rather than Infrastructure/UI/Bootstrap modules. The current Plugin API is 1.3. Declare an inclusive API range, exact services, intentional core overrides and all required network/filesystem capabilities in `PluginManifest`, then register the entrypoint with `META-INF/services/com.myhomelibcorp.plugin.api.PluginEntrypoint`.

Use `PluginTestHarness.verify(...)` or `verifyServiceLoader(...)` in plugin CI. The harness validates host compatibility/bindings/permissions but its synthetic test approval is not runtime trust. Buildable metadata/dictionary/export references are in `myhomelib-plugin-samples`; the full authoring and compatibility guide is in `docs/plugin-sdk/README.md`.

## Device profile development

Device targeting is an Application concern. Extend `DeviceTargetProfile`/`DeviceProfileService` rather than putting USB-device heuristics into UI or Infrastructure. Saved destinations must remain relative to the user-selected mount root; never persist a platform drive letter as part of a built-in profile. Unknown device roots must fail safe to the generic-folder profile rather than guessing a vendor.

When selecting an export representation, prefer an existing compatible `BookArtifact` before invoking a converter. Converter selection and legacy direct-source fallback remain in `ExportToDeviceUseCase`; a device profile supplies only ordered format preference and destination subfolder policy. Regression coverage for MHL-504 is in `DeviceProfileServiceTest`, `ExportProfileServiceTest`, `ExportToDeviceUseCaseDeviceProfileTest` and the existing crash-safety test.

### Send-to-device completion contract

Do not create a second send pipeline for removable devices. Batch iteration, progress, cancellation, collision handling, staging and atomic publication remain in `ExportToDeviceUseCase`. New callers choose completion behavior through `ExportRequest.CompletionPolicy`: `VERIFY_READABLE` preserves the legacy post-commit reopen/read check, while `EJECT_SAFE` additionally requires `ExportCompletionService` to force the committed file with `FileChannel.force(true)` before success is counted. Parent-directory force is best-effort only because some host/device filesystems do not expose a forceable directory channel.

A file-level durability-flush failure must propagate into the per-book export failure result; UI code must not present that item as safely completed. Even after `EJECT_SAFE` succeeds, desktop UX must tell the user to use the operating system’s safe-removal/eject action before unplugging hardware. Regression coverage is in `ExportToDeviceUseCaseSendToDeviceTest`, `ExportCompletionServiceTest` and `ExportControllerSendToDeviceContractTest`.



## Knowledge Markdown integration development

MHL-509 must reuse `AnnotationExportQueryPort`; do not add UI/JDBC access or a second annotation projection. Query implementations must preserve one stable total order with rows for a logical book contiguous across page boundaries. Keep export path rendering fail-closed: only documented placeholders are accepted, every generated segment is sanitized, and the resolved target must remain below the user-selected root.

Do not implement convenient auto-rename on re-export. The deterministic target is part of the integration contract. `REPLACE_MANAGED` may replace only a file whose ownership marker matches the same book id; otherwise surface a collision. Publication must remain staged/atomic and cancellation/error must remove staging files without modifying an existing target.

## Release candidate integrity development

Release packaging must keep `SHA256SUMS` authoritative for the `dist/` payload. `tools/release-candidate-integrity.py` may only attest files already represented by that manifest and must fail closed if unchecksummed payload exists. The record is deliberately separate from `dist/` so generating it cannot change the payload it describes. Keep candidate SHA binding mandatory in CI, and keep source-tree hashing deterministic by excluding build/runtime payload directories (`target`, `dist`, `.mvn`, `.git`, IDE/cache directories).

Do not describe the integrity record as a signature or SLSA provenance. It proves deterministic SHA-256 cohesion inside one CI candidate; GitHub artifact digests and the existing connected/final acceptance evidence remain the external trust boundary.

