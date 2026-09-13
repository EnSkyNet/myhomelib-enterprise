# Iteration 41 — undo / transaction / offline acceptance checkpoint

**Date:** 2026-09-11  
**Status:** DONE locally — acceptance/regression/release-source gates complete; external Windows/GitHub gates remain out of scope.

## Scope

Iteration 41 closes the remaining locally-verifiable MHL-112 acceptance status, removes the pre-existing annotation SQLite transaction helper clone, and adds one reproducible offline acceptance entrypoint that does not require Maven install lifecycle support.

### MHL-112

No new undo data model is introduced. The existing V53 shared operation journal remains authoritative. New application acceptance covers reviewed-operation race refusal plus dispatch to BULK_METADATA and BOOK_MERGE undo. Existing SQLite restart/reverse-order/retention and merge tests remain part of the final targeted set.

### Transaction helper debt

`CollectionTransactionExecutor` centralizes short infrastructure transactions against the currently selected collection. Annotation repository, manager-query and export-query adapters use it instead of maintaining identical local `TransactionTemplate` helpers. The helper fails closed without a current collection DataSource and has commit/rollback tests.

### Offline acceptance

`tools/offline_acceptance.py --maven-repo <path>` discovers repository wrapper if present, otherwise external Maven from PATH. The formal source archive still excludes Maven/wrapper/dependency binaries. The script intentionally uses reactor `test-compile` + `test`, never `mvn install`; `--full` adds the full reactor tests.

## Freeze

Source/docs/tests are complete before the first Iteration 41 compile/test run. After inventory/manual review, edits are allowed only for a gate-discovered defect followed by rerunning the affected gate.


## Pre-test manual review / freeze

Manual review completed before the first Iteration 41 compile/test run:
- MHL-112 continues to use the existing V53 operation journal and existing application ports; no alternate undo store was introduced;
- bulk undo still performs the current-state equality guard before any write and marks history undone only after all restores complete;
- BOOK_MERGE undo remains routed through `MergeBooksUseCase`, which synchronizes both search documents;
- `CollectionTransactionExecutor` exists only in Infrastructure and annotation adapters no longer carry local `TransactionTemplate` clones;
- transaction execution fails closed when no current collection DataSource exists;
- `tools/offline_acceptance.py` contains no install/deploy lifecycle invocation and requires an external offline repository;
- formal source packaging policy remains unchanged: no Maven runtime/wrapper and no dependency binaries.

`ITERATION-41-CHANGED-FILES.txt` records the Iteration 41 source/docs/test delta against the formal Iteration 40 source baseline. The tree is frozen; later edits are allowed only for a defect found by the final gate, followed by rerunning the affected gate.


### Gate-discovered correction

The first offline-entrypoint run correctly failed before project compilation because the provided bundle path was the outer directory while the actual Maven repository root is its `maven-offline-repo/` child. The entrypoint was corrected to normalize that supported bundle layout and to fail early with an explicit message when the Spring Boot or JavaFX BOM is absent. No production Java behavior changed.

The next `test-compile` found that the newly added bulk-undo guard test itself imported `spring-jdbc`, which is intentionally absent from the Application module. The test was corrected to use a mocked `TransactionTemplate`; this preserved the Application dependency boundary and changed no production code.

The targeted offline selector initially omitted the newly added bulk current-state guard test even though the test compiled. The selector was corrected before final acceptance; the missing test is explicitly required in the rerun.

The Stage 35 source gate still encoded the pre-Iteration-41 local `inTransaction` implementation detail. It was updated to assert the new shared `CollectionTransactionExecutor` contract plus its Spring transaction implementation, preserving the original safety intent instead of reintroducing duplicated code.


## Final acceptance results

- Offline reactor `test-compile`: PASS for all 13 modules.
- Iteration 41 targeted acceptance: **18/18 PASS**, 0 failures/errors/skips. This covers shared operation history dispatch/guards, SQLite restart/reverse-order/retention, BOOK_MERGE synchronization, the shared transaction executor, and all three annotation SQLite adapters.
- Application regression: **205 tests**, 0 failures/errors, 1 intentional skip; Shared **12/12**, Domain **20/20**.
- Stage 35 annotations source gate: PASS after updating it to the shared transaction executor contract.
- Implementation completeness: PASS; production Java files scanned: 903; exact cross-file method clones >=180 chars: **0**.
- ArchUnit: **12/12 PASS**.
- Annotation transaction/integration smoke: PASS, including backup round-trip; no failures/errors.
- Infrastructure monolithic regression was not used as the final signal because real-probe/watcher cases exceed the command wall-clock limit in this environment; Iteration 41's changed infrastructure surface is covered by targeted integration tests and full reactor compilation.
- No Maven runtime, wrapper, `.mvn/`, dependency JAR, `target/`, `verification/`, or Python cache is allowed in the formal source archive.

MHL-112 is therefore closed for the locally verifiable scope. External MHL-010/011/012/017/018/019 evidence remains explicitly outside this iteration.
