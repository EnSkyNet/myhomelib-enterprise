# Iteration 03 — Transactional FolderSync and coordinated Lucene rebuild

Backlog scope: **MHL-027, MHL-035** (7.1 Final / P0).

## Implemented

### MHL-027 — FolderSync transactional + crash-consistent with Lucene
- Added `CommittedCatalogMutationService` as the authoritative application boundary for FolderSync catalog mutations.
- `BookCommandRepository.save/saveBatch/updateStorage/markStorageMissing` calls used by FolderSync now execute in the collection `TransactionTemplate`.
- Lucene synchronization is registered through `SearchIndexSynchronizer.synchronizeAfterCommit(...)`; Lucene is never mutated before SQLite commit.
- A persistent Lucene dirty intent is written **before** SQLite commit while the transaction is active. This closes the crash window where SQLite could commit and the process could die before `afterCommit()` had a chance to mark Lucene stale.
- Selective Lucene mutation remains atomic (`beginAtomicUpdate` / `commit` / rollback); on failure it falls back to a full rebuild. If the fallback also fails, freshness remains dirty for restart recovery.
- INPX FolderSync finalization now uses the same safe synchronizer for both bounded selective updates and overflow full rebuilds.
- Local availability restore/missing transitions also route through the transactional mutation boundary instead of directly mutating SQLite + Lucene independently.

### MHL-035 — one coordinated lifecycle for asynchronous Lucene rebuilds
- `CollectionLifecycleService` now owns manual and automatic async rebuild lifecycle.
- Manual rebuild acquires detached `INDEX` lease **before** returning its `Future`, eliminating the executor scheduling gap.
- Auto rebuild queued during SWITCH/CREATE waits for the initiating lifecycle lease to finish, then acquires detached `INDEX` for the complete Future lifecycle.
- Detached leases are released on success, failure and cancellation.
- Added cooperative cancellation/generation invalidation bound to the current collection.
- `SwitchCollectionUseCase` cancels and awaits a collection-specific rebuild before acquiring `SWITCH`, so an A rebuild cannot continue after B is activated.
- `IndexRebuilder` gained a cancellation-aware overload; `LuceneSearchService` honors the flag while preserving its previous committed index through atomic rollback.
- Database Tools UI is routed through `DatabaseToolsService -> CollectionLifecycleService`; it no longer bypasses the operation coordinator.
- Repeated manual rebuild requests reuse the already-active Future instead of cancelling the first rebuild and then conflicting with its INDEX lease.

## Fault/concurrency coverage added
- SQLite fault injection after the `books` write and before relation completion proves transaction rollback leaves zero committed rows and schedules no Lucene work.
- Lucene `commit()` failure + failed fallback rebuild proves freshness remains dirty and atomic rollback is invoked.
- Transaction rollback proves Lucene is untouched while the pre-commit dirty intent survives conservatively.
- Manual async rebuild proves INDEX is owned before executor execution and remains owned until completion.
- Failed async rebuild proves detached INDEX lease is released.
- SWITCH A -> B test runs a real asynchronous cancellable rebuild and proves cancellation/lease release happens before B activation.
- Coordinator await test proves queued detached maintenance cannot start before the current lifecycle lease releases.

## Verification
- Targeted application concurrency/fault tests: **PASS** (`20 tests`, 0 failures/errors in the final focused run).
- Targeted infrastructure sync/crash tests: **PASS** (`9 tests`, 0 failures/errors), including SQLite transaction fault injection and Lucene crash-safety fixture.
- Full `myhomelib-application` suite reached **112 tests, 0 failures/errors, 1 skipped** during reactor run.
- `collection-search-lifecycle-check.py`: **PASS**.
- `stage25c-search-sync-refactor-check.py`: **PASS**.
- `functional-regression-check.py`: **PASS**.
- `git diff --check`: **PASS**.
- Full offline reactor compilation reached and compiled application, infrastructure, reader and UI modules successfully. The complete reactor test/package command exceeded the execution window in the infrastructure/OPDS phase; no test failure was reported before timeout. Focused changed-path tests above are green.

## Recovery semantics
- SQLite remains authoritative.
- If a process dies before DB commit: DB rolls back; conservative dirty marker may remain and causes a safe rebuild.
- If it dies after DB commit but before Lucene post-commit sync: dirty marker is already durable, so restart will not trust stale search data.
- If Lucene selective update or commit fails: atomic Lucene mutation rolls back; full rebuild is attempted.
- If full rebuild also fails: freshness remains dirty and the last committed Lucene state is preserved as rollback/restart recovery point.
