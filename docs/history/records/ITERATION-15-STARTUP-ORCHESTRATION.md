# Iteration 15 — Startup orchestration decomposition

Date: 2026-09-06  
Backlog: **MHL-022**  
Scope: `myhomelib-bootstrap`, collection startup/recovery integration, PR CI

## Goal

Decompose desktop startup into explicit, independently testable phases with a stable order and controlled failure policy, while keeping blocking work off the JavaFX Application Thread and preserving the collection/search crash-recovery guarantees introduced in earlier iterations.

## Backlog acceptance mapping

The source backlog requires:

1. extract `MigrationStartupTask`, `SearchStartupTask`, `BackupStartupTask`, `OPDSStartupTask`, `RecoveryStartupTask`;
2. test each task separately;
3. define startup order explicitly;
4. give a controlled policy when one task fails.

Implementation result:

- all five requested task classes exist in `myhomelib-bootstrap` and implement the shared `StartupTask` contract;
- `StartupOrchestrator` contains the single explicit order:
  `RecoveryStartupTask -> MigrationStartupTask -> SearchStartupTask -> BackupStartupTask -> OPDSStartupTask`;
- `RecoveryStartupTask` and `MigrationStartupTask` are `REQUIRED`;
- `SearchStartupTask`, `BackupStartupTask` and `OPDSStartupTask` are `BEST_EFFORT`;
- required failure stops all later tasks and throws `StartupException` with the failing task id;
- best-effort failure is recorded as `DEGRADED` and startup continues;
- each task has focused tests, while `StartupOrchestratorTest` verifies order plus required/best-effort behavior;
- CI contains source-level startup policy/nonblocking guards so orchestration cannot silently drift back into `MyHomeLibApp`.

## Design

### RecoveryStartupTask

Runs `CollectionStartupRecoveryService.recoverBeforeOpen(...)` before SQLite is opened. This moves crash-recovery responsibility out of the monolithic bootstrap path and makes the "recovery before DB open" invariant explicit and testable.

### MigrationStartupTask

Activates the selected collection through `SwitchCollectionUseCase.executeWithStatus(..., false)` and captures whether the existing per-collection Lucene index is reusable. The `false` flag intentionally prevents a hidden search rebuild during migration/activation.

If a post-switch startup component fails, the task closes the partially opened collection through `CollectionLifecycleService` before propagating the required failure.

### SearchStartupTask

Consumes the reusable-index decision from `StartupContext`. A reusable index is kept; otherwise a managed background rebuild is scheduled independently of migration. Search rebuild failure therefore does not turn successful SQLite migration into a failed/rolled-back catalogue migration.

### BackupStartupTask

Performs bounded startup cleanup only: interrupted `.snapshot.tmp` staging files are removed. It does not perform an expensive full automatic backup on every application launch.

### OPDSStartupTask

Loads OPDS settings and performs autostart only when enabled. OPDS startup is best-effort: an OPDS bind/TLS/start failure degrades desktop startup but does not block the local library UI.

### StartupOrchestrator / MyHomeLibApp

`StartupOrchestrator` owns ordering, timing, task outcomes and failure policy. `MyHomeLibApp` submits the orchestrator through the managed application executor and only returns to the JavaFX thread to close the splash screen and show the main window or the startup error.

This prevents database/file/index startup work from being reintroduced directly onto the FX thread.

## Supporting lifecycle change

`SwitchCollectionUseCase` exposes a status-returning activation path used by startup so collection activation can report whether the search index is reusable without forcing a rebuild. `CollectionManager` delegates pre-open crash recovery through `CollectionStartupRecoveryService`, keeping recovery reusable by both normal switching and the explicit startup phase.

## CI regression barrier

PR CI includes the startup tests plus:

```bash
python3 tools/startup-orchestration-check.py
python3 tools/startup-nonblocking-check.py
python3 tools/startup-transaction-check.py
python3 tools/collection-search-lifecycle-check.py
```

The source-level guard checks the requested task classes, guarded order/failure policy and that backend startup is submitted through a managed executor rather than performed synchronously on the JavaFX thread.

## Verification

All results below were executed offline against the supplied Maven repository.

- startup unit/integration: **12/12 PASS**;
- startup orchestration policy: **PASS**;
- startup nonblocking policy: **PASS**;
- collection/search lifecycle policy: **PASS**;
- fast core: application **127 tests: 126 PASS, 1 SKIP**, OPDS **14/14 PASS**;
- migration/security/concurrency/SecretStore: **33/33 PASS**;
- ArchUnit: **12/12 PASS**;
- E2E journeys: **10/10 PASS**;
- localization, XML/archive, managed-executor, privacy/temp, SecretStore, supply-chain, architecture and static-release policy checks: **PASS**;
- full Maven `test-compile`: **13/13 modules — BUILD SUCCESS**;
- full Maven `package -DskipTests`: **13/13 modules — BUILD SUCCESS**.

## Inherited baseline debt

`tools/implementation-completeness-check.py` is not used as a green acceptance claim for this iteration. It reports the same **10 pre-existing findings** on the Iteration 14 baseline (including historical unused imports/clone/FXML-toolbar checks). Iteration 15 did not add a new finding to that inherited list. The startup-specific and release gates above are green.

## Files of interest

- `myhomelib-bootstrap/src/main/java/com/myhomelibcorp/startup/StartupOrchestrator.java`
- `myhomelib-bootstrap/src/main/java/com/myhomelibcorp/startup/RecoveryStartupTask.java`
- `myhomelib-bootstrap/src/main/java/com/myhomelibcorp/startup/MigrationStartupTask.java`
- `myhomelib-bootstrap/src/main/java/com/myhomelibcorp/startup/SearchStartupTask.java`
- `myhomelib-bootstrap/src/main/java/com/myhomelibcorp/startup/BackupStartupTask.java`
- `myhomelib-bootstrap/src/main/java/com/myhomelibcorp/startup/OPDSStartupTask.java`
- `myhomelib-bootstrap/src/main/java/com/myhomelibcorp/MyHomeLibApp.java`
- `myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/collection/CollectionStartupRecoveryService.java`
- `.github/workflows/ci-pr.yml`
- `tools/startup-orchestration-check.py`

## Boundary

MHL-022 is a startup-architecture change, not a Windows runtime acceptance claim. The remaining 7.1 P0 backlog items MHL-011 (real Windows DPI 100/125/150/200% screenshot acceptance) and MHL-012 (real Windows installer/portable smoke) require a Windows execution environment and are not marked PASS by this Linux/offline verification.
