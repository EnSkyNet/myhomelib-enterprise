# MyHomeLib — Changes V19 → V20

Date: 2026-09-28  
Baseline: `myhomelib-enterprise-optimized-v19-checkpoint-2026-09-28.zip`.

## Production changes

### Modified

`myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/UiBackgroundExecutor.java`

- collection-scoped `CancellationException` now records `CANCELLED` instead of `FAILED`;
- applied to both scoped `CompletableFuture` and cancellable `FutureTask` execution paths.

`myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/download/DownloadBookUseCase.java`

- cooperative cancellation now records `CANCELLED` before rethrowing.

`myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/imports/ImportFileUseCase.java`

- cancelled result/exception now records `CANCELLED`;
- uncaught runtime/fatal failure now records `FAILED`;
- import algorithm and persistence behavior unchanged.

`myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/imports/ImportDirectoryUseCase.java`

- cancelled directory result/exception now records `CANCELLED`;
- fatal uncaught failure now records `FAILED`;
- scanner/import/index-finalization behavior unchanged.

`myhomelib-application/src/main/java/com/myhomelibcorp/application/navigation/DefaultNavigationQueryService.java`

- async cancellation, including wrapped `CancellationException`, is no longer recorded as failure.

`myhomelib-converter-calibre-plugin/src/main/java/com/myhomelibcorp/plugins/calibre/CalibreConverterPlugin.java`

- removed duplicated process-tree kill/wait implementation;
- plugin termination delegates to the already-tested `ProcessExecutionSupport.terminate(...)` boundary.

## Regression tests

### Added

- `DownloadBookUseCaseCancellationTest.java`;
- `ImportFileUseCaseCancellationOutcomeTest.java`.

### Extended

- `UiBackgroundExecutorCollectionScopeTest.java`;
- `ImportDirectoryUseCaseTest.java`;
- `DefaultNavigationQueryServiceTest.java`;
- `CalibreConverterPluginTest.java`.

All new regression cases were confirmed RED against pristine V19 before accepting the fixes.

## Verification

Final full reactor under Maven 3.9.16:

- **1802 tests**;
- **0 failures**;
- **0 errors**;
- **17 skipped**;
- **20/20 modules SUCCESS**;
- **BUILD SUCCESS**.

Downstream:

- production package — PASS;
- Linux `jpackage` app-image — PASS;
- packaged launcher smoke — PASS;
- SHA256SUMS — 221 entries;
- Stage23 strict checksums — PASS;
- static release check — PASS;
- supply-chain policy check — PASS;
- architecture ratchet — unchanged known 3-class debt group only.

Formal libmobi release input remains absent and strict preparation remains fail-closed by design.
