# MyHomeLib — Changes V17 → V18

Date: 2026-09-28  
Baseline: `myhomelib-enterprise-optimized-v17-checkpoint-2026-09-28.zip` only.  
Baseline SHA-256: `aa87f1f0a200ea6b213448dd3847bbf0a447337435f935e58890d13fd576d4a1`.

## Production changes

### 1. Process-tree termination waits for captured descendants

Modified:
- `myhomelib-shared/src/main/java/com/myhomelibcorp/shared/util/ProcessExecutionSupport.java`

Before V18, process-tree termination captured and killed descendants but the post-kill wait observed only the root `Process`. On platforms where a forcibly terminated child remains alive briefly, termination could return before the child had exited, creating a race with temporary-workspace deletion and file-handle release.

V18 keeps the captured descendant handles and waits for the root plus all captured live descendants within the existing bounded termination grace period. Interrupt preservation and the public API are unchanged.

### 2. Conversion cancellation is a CANCELLED operation outcome

Modified:
- `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/conversion/ConvertBookUseCase.java`

`CancellationException` is now handled before the generic failure path. The operation lease is marked `CANCELLED`, then the same cancellation exception is rethrown. Genuine failures continue to be marked `FAILED`.

### 3. Operation Center can request real book-conversion cancellation

Modified:
- `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/operation/OperationCenterService.java`
- `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/operation/OperationCenterController.java`
- `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/BookConversionUiService.java`
- `myhomelib-ui/src/main/resources/view/operation-center-workspace.fxml`

The Operation Center now supports an optional producer-owned, one-shot cancellation action for cancellable operations. Requesting cancellation signals the producer but deliberately does not mark the operation terminal; the terminal state is published only after the background task actually completes/cancels and cleanup has returned.

Book conversion registers a cancellation action backed by its existing cancellation token. The token is passed into `ConvertBookUseCase`; Calibre execution observes it through the existing process-execution cancellation path. UI completion now maps `CancellationException` to `CANCELLED` without showing an error dialog. Collection changes do not turn cancellation into failure.

A Cancel button was added to the Operation Center and is enabled only for the selected active row that currently has a cancellation action.

## Regression/characterization tests

Modified:
- `myhomelib-shared/src/test/java/com/myhomelibcorp/shared/util/ProcessExecutionSupportTest.java`
- `myhomelib-application/src/test/java/com/myhomelibcorp/application/usecase/conversion/ConvertBookUseCaseTest.java`
- `myhomelib-ui/src/test/java/com/myhomelibcorp/ui/operation/OperationCenterServiceTest.java`

Added:
- `myhomelib-ui/src/test/java/com/myhomelibcorp/ui/service/BookConversionUiServiceCancellationTest.java`

New regression contracts:
- `terminateWaitsForDescendantsAfterForcibleTermination` — RED on V17, GREEN after the minimal process wait fix;
- `cancellationPublishesCancelledOperationOutcomeInsteadOfFailure` — RED on V17 (`FAILED`), GREEN after cancellation classification fix;
- `conversionRegistersCancellableOperationThatSignalsTheRequestToken` — RED on V17 because the conversion row was not cancellable, GREEN after the generalized cancellation contract;
- `cancellationActionIsOneShotAndClearedOnTerminalTransition` — protects one-shot signalling and terminal cleanup semantics.

## Verification

Targeted/downstream:
- `ProcessExecutionSupportTest`: 6/6 PASS;
- `ConvertBookUseCaseTest`: 18/18 PASS;
- `LibraryOperationCoordinatorTest`: 23/23 PASS;
- `CalibreCliBookConverterTest`: 11/11 PASS;
- `BookConversionUiServiceCancellationTest` + `OperationCenterServiceTest` + collection-scope/FXML/localization checks: 21/21 PASS.

Final whole-reactor gate under Java 21, UTF-8, Xvfb, supplied offline Maven 3.9.6:
- 20/20 reactor modules SUCCESS;
- aggregate Surefire: **1793 tests, 0 failures, 0 errors, 17 skipped**;
- infrastructure: 637 tests, 0 failures, 0 errors, 9 skipped;
- reader: 137 tests, 1 skipped;
- UI: 228 tests, 0 skipped;
- bootstrap: 52 tests, 2 skipped;
- E2E: 15 tests, all PASS;
- benchmark module: 3 intentionally skipped benchmark tests;
- total reactor time: 2:56.

Release/package downstream:
- production profile package: BUILD SUCCESS;
- Linux `jpackage` app-image: PASS;
- packaged launcher release smoke: `MYHOMELIB_RELEASE_SMOKE_OK` — PASS;
- Stage23 release artifact validation: PASS;
- static release check: PASS;
- XML/POM/FXML: 49, 0 errors;
- FXML handler references: 262, 0 missing;
- SQLite migrations: 63, integrity OK;
- root shell scripts: 11, 0 static issues.

Architecture ratchet remains exactly the known V17 group:
- `DeferredMainFeatureActions`;
- `ReaderBookMapCoordinator`;
- `ReaderBookmarkCoordinator`.

No memory/performance tuning was performed in V18, so no unmeasured performance claim is made.

## Still external/pending

The supplied Maven archive contains 3.9.6 while `.mvn/wrapper/maven-wrapper.properties` requires 3.9.16. Exact Maven 3.9.16 acceptance remains pending.

Real Windows x64 + installed Calibre acceptance remains required for OS-specific process-tree/file-lock behavior. Linux tests cover the lifecycle contract but are not represented as Windows proof.

Stage23 still warns that `SHA256SUMS` is absent. Bundled libmobi/mobitool is also absent; compatible externally configured `mobitool` remains required for that conversion path.
