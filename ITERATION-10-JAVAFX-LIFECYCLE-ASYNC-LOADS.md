# Iteration 10 — JavaFX lifecycle and async workspace loads

Date: 2026-09-06
Baseline: `myhomelib-enterprise-7.1.0-rc3-iter09-concurrency-executor-lifecycle`
Backlog items: **MHL-024, MHL-025, MHL-041**

## Scope

This iteration closes one UI-lifecycle/concurrency block:

- **MHL-024** — reloadable JavaFX controllers must not accumulate listeners/callbacks and must use a fresh controller instance for each reload;
- **MHL-025** — book workspace database loading must not block the JavaFX Application Thread and stale A → B completions must be rejected;
- **MHL-041** — group-list database loading must not block the JavaFX Application Thread and must provide stale-result/loading-state protection.

The implementation reuses the existing `WorkspaceLifecycle`, `UiBackgroundExecutor` and `UiAsyncRequestGuard` contracts instead of introducing another asynchronous/lifecycle framework.

## Implemented changes

### 1. Fresh controller per FXML load

`FxmlLoaderFactory` no longer delegates FXML controller creation to singleton-oriented `ApplicationContext.getBean(...)`. It configures each `FXMLLoader` with a factory that calls `AutowireCapableBeanFactory.createBean(controllerType)`.

This preserves Spring dependency injection while guaranteeing a new view-controller instance per load. The same factory is reused by nested dialog/workspace loads in `DatabaseToolsController` and `SearchWorkspaceController`.

### 2. Explicit subscription lifecycle

Added `UiSubscriptions`, an idempotent registry for JavaFX `ChangeListener`, `ListChangeListener` and invalidation listener registrations.

Controllers that subscribe to long-lived application/view-model state now close those registrations from `dispose()`:

- `BookTableController`;
- `SearchWorkspaceController`;
- `DashboardController`;
- `StatisticsController`;
- `GroupWorkspaceController`.

The affected reloadable controllers are prototype-scoped where appropriate. `StatisticsController` is explicitly disposed when its window is hidden. `BookTableController` also clears its `ApplicationState` controller reference if it still owns that slot.

### 3. Book workspace async loading (MHL-025)

`BookWorkspaceController.setBookId(...)` no longer performs `LoadBookByIdUseCase.execute(...)` synchronously.

The flow is now:

1. invalidate previous generation;
2. cancel the previous `Future` with interruption;
3. create a generation + collection token using `UiAsyncRequestGuard`;
4. show `Завантаження…`;
5. execute the database load with `UiBackgroundExecutor.submitCancellable(...)`;
6. apply the result only if the token is still current;
7. expose distinct not-selected, not-found and error states.

`dispose()` invalidates the generation, cancels the pending load and clears cover/current-book state. A late result from book A therefore cannot overwrite book B or a new active collection.

### 4. Group-list async loading (MHL-041)

`GroupWorkspaceController.loadGroups()` now uses the bounded `UiBackgroundExecutor` instead of calling `LoadGroupsUseCase` on the FX thread.

The completion is guarded by generation + active-collection identity. A collection change invalidates in-flight group-list and page generations, clears stale state, then starts a new load.

User-visible states are explicit:

- `Завантаження…`;
- `Груп немає`;
- retry-oriented error text after load failure.

A requested group ID is retained when `setGroup()` arrives before the list has finished loading and is selected after the current valid load completes.

### 5. FXML state labels

`book-workspace.fxml` and `groups-workspace.fxml` now contain dedicated state labels used for loading/not-found/error/empty feedback rather than silently leaving stale content visible.

## Regression barriers

Added tests:

- `UiSubscriptionsLifecycleTest`
  - 100 attach/dispose cycles against one long-lived JavaFX property;
  - exactly one active callback per cycle;
  - zero callback after dispose;
  - idempotent close and rejection of registration after close.
- `FxmlLoaderFactoryLifecycleTest`
  - 100 controller creations;
  - 100 distinct identities;
  - Spring `@Autowired` processing remains active.
- `AsyncWorkspaceControllerContractTest`
  - book load remains behind `UiBackgroundExecutor.submitCancellable`;
  - previous load cancellation and stale guard remain wired;
  - loading/not-found/error state contracts remain present;
  - group load remains behind `UiBackgroundExecutor.submit(...)`;
  - collection-switch guard and requested-group preservation remain wired.
- existing `UiAsyncRequestGuardTest`
  - older request rejected within one collection;
  - request rejected after active-collection switch.

The four tests are included in the GitHub PR `Fast gate` as **JavaFX lifecycle and async workspace regression**.

## Validation results

All commands were executed with JDK 21 and the provided offline Maven repository.

| Gate | Result |
| --- | --- |
| Iteration-10 targeted UI lifecycle/async tests | **7/7 PASS** |
| UI non-FX suite (`MainLayoutServiceFxTest` and `MainToolbarWrapFxTest` excluded) | **39/39 UI PASS** |
| Fast core | **147 PASS, 1 SKIP, 0 failures/errors** |
| Migration/security/concurrency | **29/29 PASS** |
| ArchUnit | **12/12 PASS** |
| E2E journeys | **10/10 PASS** |
| FXML regression | **2/2 PASS** |
| XML/archive security check | **PASS** |
| language catalogue validation | **PASS** |
| managed-executor/backpressure policy | **PASS** |
| supply-chain policy | **PASS** |
| source architecture check | **PASS** |
| full reactor `test-compile` | **BUILD SUCCESS, all 13 modules** |
| full reactor `package -DskipTests` | **BUILD SUCCESS, all 13 modules** |

The two pre-existing JavaFX headless tests (`MainLayoutServiceFxTest`, `MainToolbarWrapFxTest`) were intentionally not used as the final Iteration-10 gate because they are known to depend on JavaFX/headless runtime behavior in this execution environment. Their exclusion is explicit; no failing result is being reclassified as a pass.

## Acceptance mapping

### MHL-024

- fresh instance per reload: covered by 100 distinct `createBean` controller instances;
- no callback accumulation after 100 lifecycle cycles: covered by shared long-lived property test;
- no callbacks after dispose: covered directly;
- long-lived listeners moved to explicit lifecycle registry in affected controllers.

**Status: implemented and locally verified.**

### MHL-025

- DB load outside FX thread: wired through `UiBackgroundExecutor.submitCancellable`;
- stale A → B result guard: generation + collection token;
- previous request cancellation: `Future.cancel(true)`;
- explicit loading/not-found/error states.

**Status: implemented and locally verified.**

### MHL-041

- group DB load outside FX thread: wired through `UiBackgroundExecutor.submit`;
- stale completion after a newer load/collection switch rejected;
- explicit loading/empty/error states;
- requested group preserved across async list population.

**Status: implemented and locally verified.**

## Files changed in this iteration

Primary production changes:

- `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/FxmlLoaderFactory.java`
- `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/util/UiSubscriptions.java`
- `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/book/BookWorkspaceController.java`
- `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/group/GroupWorkspaceController.java`
- `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/table/BookTableController.java`
- `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/search/SearchWorkspaceController.java`
- `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/dashboard/DashboardController.java`
- `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/StatisticsController.java`
- `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/DatabaseToolsController.java`
- `myhomelib-ui/src/main/resources/view/book-workspace.fxml`
- `myhomelib-ui/src/main/resources/view/groups-workspace.fxml`
- `.github/workflows/ci-pr.yml`

Regression tests:

- `myhomelib-ui/src/test/java/com/myhomelibcorp/ui/util/UiSubscriptionsLifecycleTest.java`
- `myhomelib-ui/src/test/java/com/myhomelibcorp/ui/service/FxmlLoaderFactoryLifecycleTest.java`
- `myhomelib-ui/src/test/java/com/myhomelibcorp/ui/AsyncWorkspaceControllerContractTest.java`

Documentation was updated in `ARCHITECTURE.md`, `MYHOMELIB-DEVELOPMENT.md`, `MYHOMELIB-OPERATIONS.md` and `MYHOMELIB-FEATURES.md`.
