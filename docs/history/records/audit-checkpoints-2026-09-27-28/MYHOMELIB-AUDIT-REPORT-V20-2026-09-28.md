# MyHomeLib — Technical Audit Report V20

Date: 2026-09-28  
Baseline: `myhomelib-enterprise-optimized-v19-checkpoint-2026-09-28.zip`  
Baseline SHA-256: `c946835a30f28200530e80e97ceee0904583c0c470044464ed8197a39aa62435`.

## Executive status

V20 continues defect-first auditing from V19. No class-size cleanup, dependency upgrade campaign, performance tuning or architecture-ratchet gaming was performed.

Five related correctness defects were reproduced on pristine V19 and fixed with narrow changes:

1. cancellation thrown by collection-scoped UI workers was recorded as `FAILED` instead of `CANCELLED`;
2. `DownloadBookUseCase` recorded cooperative download cancellation as `FAILED`;
3. `ImportFileUseCase` and `ImportDirectoryUseCase` closed their operation leases with the default `COMPLETED` outcome even for cancellation or fatal failure;
4. asynchronous navigation cancellation was recorded as a failure and could therefore strengthen a shared lifecycle barrier to `FAILED`;
5. the Calibre plugin still contained an older duplicated process-tree termination implementation that returned after the root process exited without waiting for a forcibly-killed descendant. V18 had already fixed the shared `ProcessExecutionSupport`, so the plugin duplicate had diverged from the authoritative implementation.

The Calibre change is a single responsibility-boundary refactor: plugin-specific termination now delegates to the already-tested shared process lifecycle primitive. No converter selection, command line, timeout policy, output validation or publication behavior was rewritten.

## Regression evidence on pristine V19

All new regression tests were run against pristine V19 production code before accepting the fixes.

### UI collection-scoped cancellation

Two new `UiBackgroundExecutorCollectionScopeTest` cases failed on V19:

- nested/shared conversion cancellation: expected `CANCELLED`, observed `FAILED`;
- cooperative `CancellationException` from a cancellable task: expected `CANCELLED`, observed `FAILED`.

### Download cancellation

`DownloadBookUseCaseCancellationTest` failed on V19:

- expected root library-operation outcome `CANCELLED`;
- observed `FAILED`.

### Import terminal outcomes

`ImportFileUseCaseCancellationOutcomeTest` failed on V19:

- cancelled fast import: expected `CANCELLED`, observed `COMPLETED`;
- fatal fast-import exception: expected `FAILED`, observed `COMPLETED`.

`ImportDirectoryUseCaseTest` V20 regression cases failed on V19:

- cancellation before the first child: expected `CANCELLED`, observed `COMPLETED`;
- fatal directory scan failure: expected `FAILED`, observed `COMPLETED`.

### Navigation cancellation under a shared lifecycle barrier

`DefaultNavigationQueryServiceTest.cancelledNavigationQueryDoesNotPoisonRunningConversionOutcome` failed on V19:

- expected terminal root outcome `CANCELLED` after the conversion itself was cancelled;
- observed `FAILED` because the cancelled navigation future had first been recorded as a failure.

### Calibre descendant termination

`CalibreConverterPluginTest.processTreeTerminationWaitsForForciblyKilledDescendants` failed on V19:

- the root process handle was terminated;
- a modeled descendant that exits asynchronously after `destroyForcibly()` was still alive when the plugin termination method returned.

This directly models the Windows-relevant lock/orphan risk where a child process releases `input.fb2` or a temporary workspace later than the root process.

## Minimal production changes

### `UiBackgroundExecutor`

`CancellationException` is now mapped to `lease.markCancelled(...)` in both scoped `CompletableFuture` and cancellable `FutureTask` paths. Non-cancellation failures still use `markFailed(...)`.

### `DownloadBookUseCase`

Added a dedicated `CancellationException` catch before the general exception path so cooperative cancellation is published as `CANCELLED` and rethrown unchanged.

### `ImportFileUseCase` / `ImportDirectoryUseCase`

Lease ownership is unchanged. Each use case now explicitly records:

- `CANCELLED` when the returned `ImportResult` has `ImportStatus.CANCELLED`;
- `CANCELLED` for thrown `CancellationException`;
- `FAILED` for uncaught runtime/fatal failures.

The import algorithms, transaction boundaries, parsing, batching, persistence and search-index finalization were not changed.

### `DefaultNavigationQueryService`

Async navigation completion now recognizes `CancellationException`, including wrapped causes, and marks the lease cancelled instead of failed. Success/failure behavior is otherwise unchanged.

### `CalibreConverterPlugin`

Removed the duplicated local destroy/wait algorithm. `terminateProcessTree(...)` is retained as a thin plugin-local delegate to:

`ProcessExecutionSupport.terminate(process)`.

This reuses the V18 shared descendant-snapshot + graceful/forcible termination + bounded descendant/root wait behavior and removes 19 net duplicated lines from the plugin implementation.

## Targeted/downstream verification

Changed application/P1 downstream set:

- `CollectionLifecycleServiceTest` — 8/8 PASS;
- `ImportFileUseCaseCollectionIsolationTest` — 1/1 PASS;
- `ImportDirectoryUseCaseTest` — 9/9 PASS;
- `ImportFileUseCaseCancellationOutcomeTest` — 2/2 PASS;
- `SwitchCollectionUseCaseTest` — 4/4 PASS;
- `DownloadBookUseCaseIndexConcurrencyTest` — 1/1 PASS;
- `DownloadBookUseCaseCancellationTest` — 1/1 PASS;
- `DefaultNavigationQueryServiceTest` — 15/15 PASS;
- combined application targeted set: **41 tests, 0 failures/errors**.

Process/Calibre set:

- `ProcessExecutionSupportTest` — 6/6 PASS;
- `CalibreConverterPluginTest` — 6/6 PASS.

UI lifecycle/cancellation set:

- `UiBackgroundExecutorCollectionScopeTest` — 9/9 PASS;
- `BookConversionUiServiceCancellationTest` — 1/1 PASS;
- `OperationCenterServiceTest` — 8/8 PASS;
- `CoordinatedOperationCenterBridgeTest` — 4/4 PASS;
- combined UI targeted set: **22 tests, 0 failures/errors**.

File-publication/race characterization:

- `SafeFilePublicationTest` — 9/9 PASS;
- `ExportCompletionServiceTest` — 4/4 PASS;
- combined: **13 tests, 0 failures/errors**.

No additional deterministic Linux file-publication defect was reproduced, so no speculative filesystem rewrite was made.

## Full Maven 3.9.16 verification

Environment:

- Apache Maven **3.9.16** through `./mvnw`;
- Java 21.0.11;
- `LANG=C.UTF-8`, `LC_ALL=C.UTF-8`;
- UTF-8 JVM file encoding;
- Xvfb for JavaFX-dependent tests;
- supplied populated offline Maven repository.

Final V20 full reactor `clean verify`:

- **20/20 modules SUCCESS**;
- **BUILD SUCCESS**;
- total time: **3:22**;
- aggregate Surefire XML: **1802 tests, 0 failures, 0 errors, 17 skipped**;
- 547 Surefire XML reports parsed.

Selected module totals:

- shared: 41 tests;
- application: **470 tests, 1 skipped**;
- Calibre plugin: **6 tests**;
- infrastructure: 637 tests, 9 skipped;
- reader: 137 tests, 1 skipped;
- UI: **230 tests**;
- bootstrap: 52 tests, 2 skipped;
- E2E: 15 tests, all PASS;
- benchmark: 3 tests, all intentionally skipped.

V19 reference was 1793 tests; V20 adds 9 regression tests and preserves the existing 17 intentional skips.

## Release/downstream gates

On the final V20 source state:

- production profile package: **BUILD SUCCESS**;
- Linux `jpackage` app-image: **PASS**;
- packaged launcher smoke: `MYHOMELIB_RELEASE_SMOKE_OK` — **PASS**;
- `SHA256SUMS`: generated for **221 files**;
- Stage23 with `--require-checksums`: **PASS**, 221 checksums verified;
- static release check: **PASS**;
- supply-chain policy check: **PASS**.

Formal bundled-libmobi gate remains intentionally fail-closed:

- `MHL_REQUIRE_LIBMOBI=1 tools/prepare-runtime-tools.sh` exits **2**;
- reason: verified `tools/converters/libmobi/build-src` / bundled libmobi 0.12 input is still absent.

The full-release contract was not weakened.

## Collection-switch / shutdown audit status

Deterministic lifecycle characterization confirms that:

- a collection-scoped UI lease is held from submission until the worker actually completes;
- cancelling a running scoped task does not release the lifecycle barrier before worker cleanup exits;
- active conversion/download/index/import characterization still blocks incompatible `SWITCH` operations;
- queued import paths retain expected-collection identity checks before mutation;
- conversion Operation Center cancellation remains producer-owned until cleanup completes.

No additional Linux-reproducible switch/publication/shutdown defect was found in this pass. Real Windows x64 + Calibre acceptance remains required because Windows process-tree and file-lock semantics cannot be proven by Linux modeling alone.

## Architecture status

`tools/architecture-check.py` remains intentionally non-zero with exactly the pre-existing debt group:

- `DeferredMainFeatureActions`;
- `ReaderBookMapCoordinator`;
- `ReaderBookmarkCoordinator`.

Ratchets are unchanged:

- UI output-port users: 15/18 baseline;
- non-value domain-model users: 28/28 baseline.

No architecture refactor was attempted while Windows P0 and formal libmobi release input remain open.

## Performance/memory status

No performance or memory code change was made. No benchmark timing is presented as performance evidence and no A/B performance claim is made.

## Source delta

Compared with pristine V19, excluding generated `target/`, `dist/` and local Maven cache trees:

Production Java modified: **6 files**.  
Test Java modified: **4 files**.  
Test Java added: **2 files**.  
Deleted source/test files: **0**.

Production/test source line delta: approximately **+442 / -41**, dominated by regression tests; the Calibre production duplicate itself shrank.

## Release decision for this checkpoint

V20 is the recommended clean source baseline for the next audit session.

V20 is not a final cross-platform production release candidate until:

1. real Windows x64 packaged + Calibre cancellation/process-tree/temp-workspace acceptance succeeds;
2. verified bundled libmobi 0.12 / `mobitool` plus license is restored for formal full-release packaging;
3. applicable Windows installer/portable acceptance succeeds;
4. only after the P0/P1 correctness gates, the unchanged architecture debt group is triaged one responsibility boundary at a time.
