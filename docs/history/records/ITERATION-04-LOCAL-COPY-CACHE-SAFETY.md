# Iteration 04 — Local-copy crash safety, deletion guard, cache isolation

Date: 2026-09-06  
Baseline: `myhomelib-enterprise-7.1.0-rc3-iter03-data-integrity-lucene.zip`

## Backlog items completed

| ID | Result |
|---|---|
| MHL-028 | DONE — `RemoveLocalBookCopy` is now two-phase, transactional for all affected rows, and recoverable after abrupt process termination. |
| MHL-029 | DONE — physical deletion is allowed only for an application-downloaded resource inside the canonical managed collection root; symlink/root escapes are rejected. |
| MHL-034 | DONE — `invalidateAll()` also clears author, genre and series caches, preventing collection-switch cache leakage. |

## MHL-028 — crash-safe local-copy removal

The destructive path now has an explicit durable state machine:

1. Validate the physical path and managed collection root.
2. Validate managed-download provenance (`downloaded_baseline_at`).
3. Persist a durable pending-deletion marker **before** removing the visible file path.
4. Create a recovery hard-link (preferred) or recovery copy in the same managed filesystem location.
5. Remove the visible file path.
6. Update every affected catalog row (including all books sharing one archive) plus download baselines in **one collection transaction**.
7. Lucene synchronization is scheduled only after the SQLite transaction commits through `CommittedCatalogMutationService`.
8. On normal DB failure, restore bytes immediately.
9. On process/OS crash, `CollectionCrashRecovery` reconciles the durable marker before the collection DB is opened:
   - all affected `books.local = 1` -> restore the visible file;
   - all affected `books.local = 0` -> keep deletion committed and remove hidden recovery bytes;
   - mixed state -> fail closed and retain recovery artifacts for diagnosis.

Recovery markers are bound to the stable collection ID. This prevents a marker from one collection being claimed by another collection even if legacy/deterministic book IDs collide.

## MHL-029 — physical deletion safety

Physical deletion now fails closed unless all mandatory conditions are satisfied:

- at least one affected catalog row has a durable successful-download baseline;
- an active collection with a stable collection ID exists;
- a non-root managed collection path is configured and exists;
- the target is a regular file, not a symbolic link;
- `toRealPath()` of the target remains under `toRealPath()` of the managed root;
- symlinked-parent path traversal is rejected;
- crash-recovery paths are revalidated before any startup restore/cleanup action.

This prevents the local-copy action from becoming a generic arbitrary-file delete primitive.

## MHL-034 — collection-scoped cache invalidation

`CacheInvalidationAdapter.invalidateAll()` now clears:

- book cache;
- author cache;
- genre cache;
- series cache;
- search cache;
- cover cache.

The previous author/genre/series omission could expose stale reference data after a collection switch.

## Tests and verification

### Focused regression / fault / security suite

Command scope: application + infrastructure changed paths.

- `RemoveLocalBookCopyUseCaseTest`: 3 passed
- infrastructure focused suites: 23 passed
- total focused tests: **26 passed, 0 failed, 0 errors, 0 skipped**

Covered fault/security scenarios include:

- DB failure while updating the second row of a shared archive -> staged bytes restored;
- restart after filesystem stage but before DB commit -> original bytes restored;
- restart after DB commit but before recovery cleanup -> hidden bytes removed;
- marker belonging to another collection -> ignored;
- path outside managed root -> rejected;
- symlink-parent escape -> rejected;
- filesystem root as deletion root -> rejected;
- managed-download provenance absent -> deletion blocked before filesystem mutation;
- author/genre/series cache clearing -> verified.

### Architecture

`LayerArchitectureTest`: **12 passed, 0 failed**.

### Reactor compilation

`test-compile` completed successfully for **all 13 Maven modules**.

### Core packaging

`mvn package -DskipTests` for shared/domain/application/infrastructure: **BUILD SUCCESS**.

### Full reactor test note

A full `mvn test` run was also started. The execution environment terminated it at its external time limit before the complete reactor finished. No completed test suite had failed before termination. This timeout is not reported as a successful full-suite run; the focused regression suite and architecture suite above are the authoritative verification for this iteration.

## Files added/changed in this iteration

Main code:

- `myhomelib-application/.../CatalogUpdateTrackingPort.java`
- `myhomelib-application/.../BookResourcePort.java`
- `myhomelib-application/.../RemoveLocalBookCopyUseCase.java`
- `myhomelib-infrastructure/.../CacheInvalidationAdapter.java`
- `myhomelib-infrastructure/.../SqliteCatalogUpdateTrackingAdapter.java`
- `myhomelib-infrastructure/.../CollectionCrashRecovery.java`
- `myhomelib-infrastructure/.../BookResourceResolver.java`
- `myhomelib-infrastructure/.../LocalCopyDeletionRecoveryStore.java` (new)

Tests:

- `RemoveLocalBookCopyUseCaseTest.java` (new)
- `CacheInvalidationAdapterTest.java` (new)
- `SqliteCatalogUpdateTrackingAdapterTest.java`
- `BookResourceResolverDeletionSafetyTest.java` (new)
- `LocalCopyDeletionRecoveryStoreTest.java` (new)
- `CollectionManagerCrashRecoveryTest.java`

## Scope intentionally not mixed into Iteration 04

The iteration is limited to the tightly coupled data-safety/cache cluster MHL-028, MHL-029 and MHL-034. Unrelated items (CI, installer/manual verification, encryption envelope, support bundle, Classic edit transactionality, performance work, etc.) remain for subsequent iterations so their acceptance and rollback surface stay independently reviewable.
