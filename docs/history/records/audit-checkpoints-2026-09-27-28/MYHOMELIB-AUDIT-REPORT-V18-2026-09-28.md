# MyHomeLib — Technical Audit Report V18

Date: 2026-09-28  
Baseline: `myhomelib-enterprise-optimized-v17-checkpoint-2026-09-28.zip`  
Baseline SHA-256: `aa87f1f0a200ea6b213448dd3847bbf0a447337435f935e58890d13fd576d4a1`.

## Executive status

V18 follows the V17 defect-first priority and does not reopen completed large-class decomposition. Three concrete conversion/cancellation lifecycle defects were addressed with regression evidence before production changes:

1. process termination could return after the root exited while a forcibly killed descendant was still alive;
2. `ConvertBookUseCase` classified cancellation as `FAILED` instead of `CANCELLED`;
3. the existing book-conversion cancellation token had no user-triggerable Operation Center action, so user cancellation could not be requested from the UI.

The fixes are intentionally narrow. No public converter API was redesigned, no large-class size refactor was performed, and no memory/performance tuning was attempted without A/B evidence.

## Defect 1 — descendant lifetime after forced process-tree termination

### V17 behavior

`ProcessExecutionSupport.terminateTree(...)` captured descendants, requested graceful/forcible termination, then callers waited only on the root `Process`. A child handle may therefore remain alive after the method returns even when the root is already dead.

For Calibre on Windows this is a correctness risk because child lifetime and open file handles can outlive the root and race cleanup of `mhl-calibre-*` workspaces or `input.fb2`.

### Regression evidence

Added `ProcessExecutionSupportTest.terminateWaitsForDescendantsAfterForcibleTermination()` using deterministic fake process handles: the root exits immediately while one forcibly killed child exits after a short delay.

On unchanged V17 the test failed because `terminate(...)` returned while the child was still alive. After the fix the same test passes.

### Minimal fix

`terminateTree` now returns the captured descendant handles. Existing termination paths retain that snapshot and wait for root + live descendants within the pre-existing bounded `TERMINATION_GRACE` budget. Interrupted waits preserve the interrupt flag. No public API changed.

Downstream Calibre converter tests remain green.

## Defect 2 — cancellation incorrectly recorded as failure

### V17 behavior

`ConvertBookUseCase.execute(...)` allowed `CancellationException` to enter the generic `catch (Exception)` block, which marked the operation lease `FAILED`.

### Regression evidence

Added `cancellationPublishesCancelledOperationOutcomeInsteadOfFailure()`.

On V17 it observed `FAILED`; after the fix it observes `CANCELLED` and the cancellation exception still propagates to the caller.

### Minimal fix

A dedicated `catch (CancellationException)` precedes the generic failure handler, calls `lease.markCancelled(...)`, and rethrows. All non-cancellation failures retain existing behavior.

## Defect 3 — no user-triggerable conversion cancellation from Operation Center

### V17 behavior

Book conversion already passed a cancellation supplier through the conversion stack, but `BookConversionUiService` created it with an `AtomicBoolean` that had no user-facing action capable of setting it. The Operation Center row was therefore non-cancellable in practice.

### Regression evidence

Added `BookConversionUiServiceCancellationTest`. On unchanged V17 the conversion entry failed the contract `entry.cancellable() == true`. The final test now verifies the complete UI contract:

- the conversion row is cancellable;
- requesting cancellation signals the exact request token passed to `ConvertBookUseCase`;
- worker cancellation completes exceptionally with `CancellationException`;
- the Operation Center becomes terminal `CANCELLED` only after completion reaches the UI callback;
- cancellation does not raise the normal conversion error dialog.

`OperationCenterServiceTest` separately verifies that the cancellation action is one-shot and unavailable after terminal transition.

### Minimal/generalized fix

`OperationCenterService` gained an optional producer-owned `Runnable` cancellation action. `requestCancellation` consumes it once but does not set a terminal state. This keeps cleanup ownership with the producer and avoids a false “cancelled” UI state while an external process may still be terminating.

`BookConversionUiService` registers `() -> cancelled.set(true)`. Existing cancellation propagation then reaches `ConvertBookUseCase` and process execution. The Operation Center controller adds one generic Cancel button rather than a Calibre-specific control.

## Targeted/downstream verification

Application/process/converter slice:
- `ProcessExecutionSupportTest`: 6/6 PASS;
- `ConvertBookUseCaseTest`: 18/18 PASS;
- `LibraryOperationCoordinatorTest`: 23/23 PASS;
- `CalibreCliBookConverterTest`: 11/11 PASS.

UI/downstream slice:
- `BookConversionUiServiceCancellationTest`;
- `OperationCenterServiceTest`;
- `UiBackgroundExecutorCollectionScopeTest`;
- `JavaFxControlResourcesTest`;
- `AllFxmlLocalizationFxTest`;
- combined: 21/21 PASS.

## Final whole-reactor verification

Environment:
- Java 21.0.11;
- `LANG=C.UTF-8`;
- `LC_ALL=C.UTF-8`;
- JVM file encoding UTF-8;
- Xvfb for JavaFX-dependent tests;
- supplied offline Maven 3.9.6 and supplied offline repository/JavaFX artifacts.

Result:
- **20/20 modules SUCCESS**;
- **BUILD SUCCESS**;
- total time: **2:56**;
- aggregate Surefire: **1793 tests, 0 failures, 0 errors, 17 skipped**;
- application: 464 tests, 1 skipped;
- infrastructure: 637 tests, 9 skipped;
- reader: 137 tests, 1 skipped;
- UI: 228 tests, 0 skipped;
- bootstrap: 52 tests, 2 skipped;
- E2E: 15 tests, all PASS;
- benchmark: 3 tests, all intentionally skipped.

Two earlier foreground attempts were terminated by the audit runner execution ceiling and were not counted as PASS. The successful long-lived run above completed normally with exit code 0; the separately rerun application suite also completed normally (464 tests, 0 failures/errors), so no production change was made for the runner artefact.

## Release/package downstream contracts

Final V18 source state:
- production profile package: BUILD SUCCESS;
- Linux `jpackage` app-image: PASS;
- packaged launcher smoke: `MYHOMELIB_RELEASE_SMOKE_OK` — PASS;
- Stage23: PASS;
- static release check: PASS;
- XML/POM/FXML: 49, 0 errors;
- FXML handler references: 262, 0 missing;
- SQLite migrations: 63, integrity OK;
- root shell scripts: 11, 0 static issues.

Known packaging warnings are unchanged:
- bundled libmobi 0.12 runtime/source is absent, so compatible externally configured `mobitool` is still required for that conversion path;
- `SHA256SUMS` is absent and Stage23 treats that as a warning in the non-strict invocation.

## Exact Maven 3.9.16 gate

`.mvn/wrapper/maven-wrapper.properties` requires Apache Maven 3.9.16. The supplied `.mvn.zip` contains Apache Maven 3.9.6. Therefore exact-version 3.9.16 acceptance is still **PENDING** and the V18 3.9.6 evidence is not presented as equivalent.

## Windows acceptance boundary

The Linux contract tests now cover cancellation classification, signalling, bounded process-tree waiting and UI terminal-state sequencing. They do not prove Windows process-handle semantics.

Real Windows x64 acceptance with Calibre installed is still required to verify:
- long FB2 → EPUB responsiveness;
- unrelated navigation/reader/download usability during conversion;
- deterministic user cancellation;
- termination of the complete real Calibre child process tree;
- deletion of `mhl-calibre-*` workspace after termination;
- no stale `CONVERSION` lease;
- shutdown during conversion leaves no locked `input.fb2` and no orphan process.

## Architecture status

`tools/architecture-check.py` reports the same one known standalone debt group as V17 and no new debt:
- `DeferredMainFeatureActions`;
- `ReaderBookMapCoordinator`;
- `ReaderBookmarkCoordinator`.

The Maven architecture-test module itself is green (14/14). No architecture refactor was attempted because this iteration had concrete P1 defects and no new characterization evidence justifying another responsibility extraction.

## Performance/memory status

No performance or memory optimization was made in V18. Consequently there is no A/B claim and no benchmark result is used to justify a source change.

## Source delta and release decision

Compared with pristine V17, excluding generated `target/` and `dist/` trees:
- modified production/resource files: 6;
- modified existing test files: 3;
- added test files: 1;
- deleted source/test files: 0.

V18 is the recommended clean source baseline for the next audit session. It improves the conversion cancellation/process lifecycle while preserving the V17 green reactor and release gates available in this Linux/offline environment.

It is not a final production release candidate until exact Maven 3.9.16 acceptance and real Windows x64/Calibre acceptance are completed, and the remaining packaging/architecture decisions are resolved or explicitly accepted.
