# Iteration 11 — Transactional edit and domain collection immutability

Date: 2026-09-06
Baseline: `myhomelib-enterprise-7.1.0-rc3-iter10-javafx-lifecycle-async-loads.zip`
Backlog items: **MHL-021**, **MHL-030**

## Scope

This iteration closes the remaining catalog-edit consistency gap in the Classic UI and hardens the `Book` aggregate against relationship-list mutation outside domain methods.

## MHL-021 — Book relationship collections are immutable to callers

Changes:
- `Book` now defensively copies builder-provided `authors` and `genres` lists.
- `getAuthors()` and `getGenres()` return immutable views.
- repository/mapping code may still populate a reconstructed aggregate through `addAuthor()` / `addGenre()`.
- copy-style Book operations continue to work because the constructor takes a defensive copy.

Acceptance mapping:
1. External list mutation is rejected with `UnsupportedOperationException` — covered by `BookCollectionsImmutabilityTest`.
2. Builder input aliases cannot mutate an already-built aggregate — covered by `BookCollectionsImmutabilityTest`.
3. Domain population API remains available — covered by the same test and repository regression/build gates.

## MHL-030 — Classic edit moved to transactional application use case

Changes:
- added `EditBookUseCase` in `myhomelib-application`;
- the use case reloads the authoritative Book, validates input, preserves non-editable metadata/storage/user state, and saves through `CommittedCatalogMutationService`;
- SQLite mutation is executed inside the existing collection transaction;
- `SearchIndexSynchronizer` is invoked only after commit and keeps the durable dirty/recovery marker semantics established in Iteration 03;
- `ClassicLibraryActionsService` no longer imports or calls `BookCommandRepository` / `SearchIndexer` directly;
- both initial dialog snapshot loading and authoritative save run through `UiBackgroundExecutor` rather than the JavaFX thread;
- callers now refresh only from the asynchronous success callback after the committed edit returns.

Acceptance mapping:
1. **Relation failure rollback** — `EditBookTransactionalConsistencyTest.relationFailureRollsBackEditAndNeverSchedulesLucene` uses real SQLite + transaction manager and verifies the pre-edit title remains after a fault injected after the row update.
2. **Lucene failure keeps DB change + recovery state** — `EditBookTransactionalConsistencyTest.luceneFailureKeepsCommittedEditAndLeavesIndexDirtyForRecovery` injects selective and full-rebuild Lucene failures, verifies SQLite retains the new title, verifies dirty lifecycle marking, and verifies no synchronized marker is written.
3. **Edit does not block FX thread** — `ClassicEditAsyncContractTest` verifies both initial query and use-case execution are submitted to `UiBackgroundExecutor` and that no direct repository/Lucene write remains in the UI service.
4. **Old/new metadata regression** — `EditBookUseCaseTest` verifies editable fields change while ISBN, libId, libraryRate, translators, city, source URL, rating, progress, file/storage, genres and creation state are preserved.

## CI regression barrier

`ci-pr.yml` now includes:
- `EditBookTransactionalConsistencyTest` in migration/security regression;
- `ClassicEditAsyncContractTest` in JavaFX lifecycle/async regression.

## Verification

All commands used the bundled Maven launcher and `/mnt/data/maven-offline-repo`.

- New MHL-021/MHL-030 domain/application/infrastructure tests: **6/6 PASS**.
- Classic edit + existing JavaFX lifecycle/async regression: **9/9 PASS**.
- Fast core: application **122 tests, 121 PASS + 1 SKIP**; OPDS **14/14 PASS**; shared/domain also PASS.
- Migration/security/concurrency regression: **31/31 PASS**.
- ArchUnit `LayerArchitectureTest`: **12/12 PASS**.
- E2E journeys: **10/10 PASS**.
- Static XML/archive, language catalogue, managed-executor, supply-chain and source architecture checks: **PASS**.
- Full reactor `test-compile`: **13/13 modules BUILD SUCCESS**.
- Full reactor `package -DskipTests`: **13/13 modules BUILD SUCCESS**.

Expected fault-injection stack traces from the Lucene-failure test appear in Maven logs; the suite result is PASS and they demonstrate the required post-commit recovery behavior.
