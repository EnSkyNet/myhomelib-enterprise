# MyHomeLib — Changes V20 → V21

Date: 2026-09-28  
Baseline: `myhomelib-enterprise-optimized-v20-checkpoint-2026-09-28.zip`.

## Scope

V21 closes the three architecture-debt classes explicitly carried by V20. Changes were performed one responsibility boundary at a time, with characterization before each production change and targeted/downstream verification afterward.

No performance/memory change, dependency upgrade, broad rewrite or architecture-ratchet relaxation was performed.

## Production changes

### `DeferredMainFeatureActions` / `MainController`

Modified:

- `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/DeferredMainFeatureActions.java`;
- `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/MainController.java`.

Change:

- collection-properties UI action now returns a boolean commit signal rather than exposing domain `Collection`;
- navigation refresh behavior remains conditional on a committed edit and is otherwise unchanged.

### `ReaderBookMapCoordinator`

Modified:

- `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/ReaderBookMapCoordinator.java`;
- `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java`.

Change:

- coordinator consumes reader-level `ReaderPosition` values instead of domain `Bookmark`;
- removed its persistence-service dependency;
- workspace orchestration performs the existing bookmark-to-position mapping before map aggregation.

### Reader bookmark projection boundary

Added:

- `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/ReaderBookmarkItem.java`.

Modified:

- `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/ReaderBookmarkCoordinator.java`;
- `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/NewReaderPersistenceService.java`;
- `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java`.

Change:

- Reader UI/coordinator now consume immutable `ReaderBookmarkItem` projections;
- domain `Bookmark` mapping is contained in the existing Reader persistence boundary;
- existing persistence/domain methods are retained for compatibility;
- no repository schema, bookmark semantics or navigation contract changed.

## Characterization/tests

Added:

- `DeferredMainFeatureActionsTest.java` — 2 characterization tests;
- `ReaderBookmarkProjectionTest.java` — 3 projection/navigation tests.

Modified for the new boundary types:

- `ReaderBookMapCoordinatorTest.java`;
- `ReaderBookmarkCoordinatorTest.java`.

Verification:

- focused Reader/Deferred set: **9/9 PASS**;
- expanded architecture/persistence set: **15/15 PASS**;
- UI full module: **235 tests, 0 failures/errors**;
- full Maven 3.9.16 reactor: **1807 tests, 0 failures, 0 errors, 17 skipped; 20/20 modules SUCCESS**.

## Architecture result

Before V21, the known group was:

- `DeferredMainFeatureActions`;
- `ReaderBookMapCoordinator`;
- `ReaderBookmarkCoordinator`.

After V21:

- standalone architecture check — **PASS**;
- non-value domain-model users: **25 current / 28 baseline**;
- output-port users: **15 current / 18 baseline**;
- architecture baseline thresholds unchanged.

## Release verification

- production package — PASS;
- Linux `jpackage` app-image — PASS;
- desktop launcher smoke — PASS;
- portable archive + extracted smoke — PASS;
- `SHA256SUMS` — 225 entries;
- strict Stage23 checksums + portable validation — PASS;
- static release check — PASS;
- supply-chain policy check — PASS.

Strict libmobi preparation remains fail-closed with exit code 2 because the verified licensed libmobi 0.12 input is not present. Real Windows x64 + Calibre acceptance remains an external platform gate.
