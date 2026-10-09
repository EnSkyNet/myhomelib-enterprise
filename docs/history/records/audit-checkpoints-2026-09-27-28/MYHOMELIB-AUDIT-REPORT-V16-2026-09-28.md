# MyHomeLib — Technical Audit Report V16

Date: 2026-09-28
Baseline: `myhomelib-enterprise-optimized-v15-checkpoint-2026-09-28.zip`
Scope: Maven/release gate continuation and staged P4 decomposition only. No unrelated stable code was rewritten.

## Executive status

V16 is a clean P4 checkpoint after four responsibility extractions:

1. `NewReaderWorkspaceController` → `ReaderSidebarSearchCoordinator`
2. `SearchWorkspaceController` → `ContentSearchCoordinator`
3. `DesktopIntegrationBackend` → `DesktopAiIntegration`
4. `VersionedUserDataTransferAdapter` → `ReaderPreferencesTransferStore`

No public application/UI/plugin contract was intentionally changed by these extractions. `InpxImportPipeline` was deliberately not decomposed in this checkpoint because the exact Maven 3.9.16 acceptance gate is still unavailable; V16 is intentionally sealed after four independently verified extraction boundaries.

## Maven gate

The supplied `.mvn.zip` contains Apache Maven **3.9.6**, while the project wrapper contract points to **3.9.16**. The environment still cannot download Maven 3.9.16 because outbound DNS/network access for the wrapper is unavailable.

Therefore:

- Maven 3.9.16 authoritative acceptance: **PENDING**.
- Maven 3.9.6 offline lifecycle evidence: available and used as a near-authoritative safety gate.
- Current V16 full reactor `clean verify` under Maven 3.9.6 + Xvfb: **BUILD SUCCESS**, 20/20 modules, total 4:11.
- Aggregate current V16 Surefire results: **1789 tests, 0 failures, 0 errors, 17 skipped**.
- Current V16 infrastructure module inside the full reactor: **637 tests, 0 failures, 0 errors, 9 skipped**.
- Current V16 production reactor package (`-Pproduction -DskipTests`): **BUILD SUCCESS**, 15/15 package modules.

All Maven test runs in this audit use explicit `LANG=C.UTF-8`, `LC_ALL=C.UTF-8` and UTF-8 JVM file encoding. Without this, two Unicode-path tests in the application module can fail because of the host locale; targeted rerun under UTF-8 was green.

## P4 decomposition details

### 1. Reader sidebar search

`NewReaderWorkspaceController` changed from 1932 to 1908 lines. A new 102-line `ReaderSidebarSearchCoordinator` owns sidebar full-text-search request generation, cancellation, queue-saturation handling and guarded result publication. Workspace/context lifecycle checks remain in place.

Validation:
- reader module: 137 tests, 0 failures/errors, 1 skipped — PASS;
- UI after step 1: 225/225 — PASS;
- later UI suite after step 2: 226/226 — PASS.

### 2. Content search

`SearchWorkspaceController` changed from 1254 to 1230 lines. A new 87-line `ContentSearchCoordinator` owns cancellable collection-scoped content search, index-not-ready/error status and guarded publication. UI-facing controller contracts remain unchanged.

Validation:
- full UI module after extraction: 226 tests, 0 failures/errors — PASS.

### 3. Desktop AI integration

`DesktopIntegrationBackend` changed from 1367 to 1265 lines. A new 181-line package-private `DesktopAiIntegration` owns AI provider discovery, configuration, secret handling and execution while preserving lazy provider/secret-store initialization.

A new characterization test fixes current behavior for built-in provider priority, presentation sorting, capability metadata and atomic configuration mutation.

Validation:
- targeted characterization + lazy initialization: 3/3 — PASS;
- full bootstrap module: 52 tests, 0 failures/errors, 2 skipped — PASS.

### 4. Reader-preferences transfer lifecycle

`VersionedUserDataTransferAdapter` changed from 1225 to 1080 lines. A new 167-line `ReaderPreferencesTransferStore` owns the bounded/race-safe `reader-preferences.json` file lifecycle: safe snapshot, import/replace, rollback, hash/race checks, symlink rejection, size validation and file publication. SQL export/import/migration orchestration remains in `VersionedUserDataTransferAdapter`.

Validation:
- existing characterization suite before extraction: 14/14 — PASS;
- same suite after extraction: 14/14 — PASS.

## Infrastructure suite verification

Direct long-running tool calls were initially terminated by the execution interface and could misleadingly appear to stall at arbitrary test classes. To separate tool-runner behavior from project behavior, the infrastructure suite and then the complete reactor were executed as detached Maven processes and monitored independently.

Authoritative current-V16 result:

- infrastructure: **637 tests, 0 failures, 0 errors, 9 skipped — PASS**;
- full 20-module reactor: **1789 tests, 0 failures, 0 errors, 17 skipped — BUILD SUCCESS**.

The previously suspected test-harness/resource-leak issue is therefore **not reproduced** and is not carried forward as project debt. No production code was changed for it.

## Release/package downstream contracts

On the current V16 source state:

- production package: **BUILD SUCCESS**;
- Linux `jpackage` app-image: **PASS**;
- packaged launcher smoke: `MYHOMELIB_RELEASE_SMOKE_OK` — **PASS**;
- Stage23 cross-platform release artifact validation: **PASS**;
- static release check: **PASS** (49 XML/POM/FXML, 63 migrations, 11 root shell scripts, 0 static release issues);
- architecture checker: exactly **1 known debt group**, unchanged from V15.

`jpackage` reports that bundled libmobi is absent; MOBI/AZW conversion therefore still depends on a compatible runtime `mobitool`. Stage23 also warns that `SHA256SUMS` is absent inside `dist`; this is a warning, not a validation failure.

## Architecture debt

The architecture ratchet still reports the existing UI → non-value-domain debt group:

- `DeferredMainFeatureActions`
- `ReaderBookMapCoordinator`
- `ReaderBookmarkCoordinator`

No additional architecture violation was introduced by V16. UI output-port ratchet remains 15/18 baseline and non-value-domain-model users 28/28 baseline.

## Source delta vs pristine V15

Production/test source delta before adding V16 documentation:

- modified: 7 files;
- added: 5 files;
- deleted: 0 files.

The clean V16 package excludes injected Maven 3.9.6 binaries, `.mvn/repository`, all `target` directories, `dist`, transient logs/caches and other build output. The project Maven wrapper files are preserved.

## Release decision for this checkpoint

V16 is suitable as the next **source checkpoint** for continued audit/refactoring. It is **not** declared a final release candidate because exact Maven 3.9.16 `clean verify`, Windows x64 packaged acceptance and the final P4 `InpxImportPipeline` decomposition remain open. The current full reactor is green under the supplied Maven 3.9.6.
