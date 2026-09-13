# Iteration 09 — Concurrency, managed executors and lifecycle hardening

Date: 2026-09-06
Baseline: `myhomelib-enterprise-7.1.0-rc3-iter08-supply-chain-source-packaging`
Backlog items: **MHL-023, MHL-026, MHL-036, MHL-037, MHL-040**

## Scope

This iteration groups the remaining concurrency/lifecycle work around event delivery and background execution into one bounded change set. The common goal is to ensure that asynchronous work is explicitly owned, observable, cancellable where practical, and never falls back to the Java/ForkJoin caller thread under overload.

## MHL-023 — SimpleEventBus unregister + thread safety

### Problem found

`register()` stored a wrapper lambda while `unregister()` compared list entries by identity against the original listener. As a result, unregister did not remove the registered callback. The backing `ArrayList` was also mutated concurrently without synchronization.

### Change

- `ConcurrentHashMap<Class<?>, CopyOnWriteArrayList<Registration<?>>>` now stores registrations.
- Each registration keeps both the original listener identity and a type-safe invoker.
- `unregister()` removes by the identity of the original listener and is idempotent.
- `publish()` iterates a stable CopyOnWrite snapshot; concurrent register/unregister/publish does not produce CME.

### Acceptance

- register -> publish calls listener: covered;
- unregister -> publish no longer calls listener: covered;
- concurrent register/unregister/publish stress: covered;
- repeated unregister: covered.

## MHL-026 — FolderSync on managed I/O executor

### Problem found

`FolderSyncService.syncFolderAsync()` used `CompletableFuture.supplyAsync(...)` without an executor, so filesystem scan/import/SQLite/Lucene work ran on `ForkJoinPool.commonPool`.

### Change

- `FolderSyncService` receives the named `ioExecutor` bean.
- Async admission uses that executor directly; commonPool is no longer involved.
- A custom returned `CompletableFuture` propagates `cancel()` to the existing cooperative `cancelSync()` flag.
- Executor admission rejection is returned as an exceptional future rather than silently running on the caller.
- Focused unit tests retain a package-private direct-executor constructor; Spring production wiring always uses the managed I/O executor.

### Acceptance

- async sync does not use commonPool: covered by runtime test + static ratchet;
- thread naming is defined (`app-io-`): covered;
- cancellation propagates cooperatively: covered;
- overload does not steal the caller/FX thread: covered by bounded executor policy.

## MHL-036 — Consolidated managed executors

### Change

`AsyncConfig` is now the owner of four bounded backend roles:

| Role | Core | Max | Queue | Prefix |
|---|---:|---:|---:|---|
| task | 5 | 20 | 200 | `app-task-` |
| io | 4 | 16 | 200 | `app-io-` |
| import | 2 | 10 | 100 | `app-import-` |
| search | 2 | 8 | 50 | `app-search-` |

Additional changes:

- `SpringExecutorAdapter` reuses `taskExecutor`; it no longer owns a second private pool.
- `BackgroundExecutor` is now a compatibility facade over `ioExecutor`; it no longer owns a fixed thread pool.
- startup backend initialization is submitted through `ExecutorPort`.
- `StatisticsController` uses `UiBackgroundExecutor` instead of unqualified `supplyAsync`.
- `AsyncConfig.metrics()` exposes active count, pool size, queue depth and remaining queue capacity per role.
- `AsyncConfig.shutdown()` centrally stops created backend pools and is idempotent.
- bootstrap no longer tries to call shutdown on compatibility facades that do not own executors.

### Static regression barrier

Added `tools/managed-executor-check.py` and wired it into `.github/workflows/ci-pr.yml`. It fails when production code introduces:

- `CompletableFuture.supplyAsync/runAsync` without an explicit executor;
- `CallerRunsPolicy`;
- direct `ForkJoinPool.commonPool()` usage;
- unmanaged fixed/cached/work-stealing pools.

It also asserts the reviewed startup, Statistics and FolderSync routes.

## MHL-037 — MemoryMonitor lifecycle

### Change

- interval is validated before any state change;
- scheduler is recreated on every valid start after stop;
- scheduler thread is named `memory-monitor` and is daemon;
- `start -> stop -> start` is supported;
- `close()` / `@PreDestroy` performs idempotent cleanup;
- invalid interval leaves `running=false`.

## MHL-040 — Explicit backpressure, no caller-thread fallback

### Problem found

`AsyncConfig` used `ThreadPoolExecutor.CallerRunsPolicy`. If a bounded queue filled while the submitter was the JavaFX thread, the supposedly-background task could run synchronously on the FX thread and freeze the UI.

### Change

- all managed backend executors now use explicit rejection;
- rejection logs role, queue depth, active count and pool size;
- the rejection message includes the executor role and queue depth;
- future-returning adapters convert admission rejection into failed futures where practical (`SpringExecutorAdapter`, `BackgroundExecutor`, `UiBackgroundExecutor`, FolderSync async path);
- no executor uses `CallerRunsPolicy`.

## Tests added

- `SimpleEventBusTest`
- `AsyncConfigTest`
- `SpringExecutorAdapterTest`
- `MemoryMonitorTest`
- `FolderSyncAsyncExecutorTest`

These tests are included in the PR migration/security/concurrency gate.

## Verification performed

### Focused new regression tests

`SimpleEventBusTest, AsyncConfigTest, MemoryMonitorTest, FolderSyncAsyncExecutorTest, SpringExecutorAdapterTest`

Result: **11 tests, 11 PASS, 0 failures/errors**.

### PR infrastructure gate

Includes existing migration/security tests plus the five new concurrency/lifecycle test classes.

Result: **29 tests, 29 PASS, 0 failures/errors**.

### Fast core regression

- shared: 7 PASS;
- domain: 7 PASS;
- application: 120 total = 119 PASS + 1 SKIP;
- OPDS: 14 PASS.

Result: **147 PASS + 1 SKIP, 0 failures/errors**.

### Architecture

`LayerArchitectureTest`: **12/12 PASS**.

`tools/architecture-check.py`: **PASS**.

### E2E

Four E2E journey classes: **10/10 PASS**.

### Static policy checks

- `tools/managed-executor-check.py`: PASS;
- `tools/startup-nonblocking-check.py`: PASS;
- `tools/supply-chain-policy-check.py`: PASS;
- `tools/xml-archive-security-check.py`: PASS;
- `tools/validate-language-catalogs.py`: PASS.

### Full infrastructure suite note

A full `myhomelib-infrastructure -am test` run was started. The execution environment terminated the command at the external 180-second timeout while the long migration matrix was still running. All suites that completed before termination reported zero failures/errors. This is recorded as an environment timeout, not as a successful full-suite completion.

### Full reactor compile/package

- `./mvnw ... -DskipTests test-compile`: **13/13 modules BUILD SUCCESS**;
- `./mvnw ... -DskipTests package`: **13/13 modules BUILD SUCCESS**.

## Files materially changed

Production/CI:

- `.github/workflows/ci-pr.yml`
- `myhomelib-bootstrap/.../MyHomeLibApp.java`
- `myhomelib-infrastructure/.../config/AsyncConfig.java`
- `myhomelib-infrastructure/.../event/SimpleEventBus.java`
- `myhomelib-infrastructure/.../executor/BackgroundExecutor.java`
- `myhomelib-infrastructure/.../executor/SpringExecutorAdapter.java`
- `myhomelib-infrastructure/.../monitoring/MemoryMonitor.java`
- `myhomelib-infrastructure/.../sync/FolderSyncService.java`
- `myhomelib-ui/.../controller/StatisticsController.java`
- `myhomelib-ui/.../service/UiBackgroundExecutor.java`
- `tools/managed-executor-check.py`

Tests:

- `SimpleEventBusTest`
- `AsyncConfigTest`
- `SpringExecutorAdapterTest`
- `MemoryMonitorTest`
- `FolderSyncAsyncExecutorTest`

Documentation:

- `ARCHITECTURE.md`
- `MYHOMELIB-DEVELOPMENT.md`
- `MYHOMELIB-OPERATIONS.md`
- this iteration report.

## Remaining related backlog

Not included in this iteration because they are UI/data-flow changes rather than executor infrastructure:

- MHL-024 reloadable FXML controller lifecycle;
- MHL-025 async BookWorkspace load;
- MHL-041 async group-list load.
