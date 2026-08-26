# Stage 25A Changelog — Main/Table/Navigation UI Orchestration Refactor

## Scope

Stage 25A is a behaviour-preserving targeted refactor performed only after the Stage 24 performance baseline. It reduces `MainController` fan-in and removes concrete callback cycles from workspace/navigation orchestration without adding product features.

## Refactored

- Added `MainNavigationCoordinator` for main-shell navigation commands, recent/history menu orchestration and navigation-mode actions.
- Added `MainBookCommandCoordinator` for selected-book commands such as internal/external open, local-copy removal, metadata editing and deletion.
- Reduced `MainController` from 793 lines before the refactor to 647 lines while preserving its FXML-facing handler surface.
- Removed `MainController` references from:
  - `WorkspaceManager`;
  - `NavigationHistoryService`;
  - `DefaultNavigationService`;
  - `BookDetailsController`.
- Removed the former callback cycle where navigation/workspace services called back into the concrete shell controller.
- Moved authoritative Back/Forward availability state into `WorkspaceManager` as observable read-only boolean properties.
- Bound shell Back/Forward buttons directly to `WorkspaceManager.canGoBackProperty()` / `canGoForwardProperty()`.
- Kept `WorkspaceManager` responsible for workspace lifecycle/history while the shell remains an FXML composition layer.

## Regression protection

- Added `WorkspaceManagerNavigationStateTest` covering observable Back/Forward state without `MainController` callbacks.
- Added `tools/stage25a-ui-orchestration-check.py` to guard:
  - MainController size/fan-in;
  - absence of concrete shell dependencies in production UI services;
  - property-bound Back/Forward state;
  - coordinator command coverage;
  - Stage 24 baseline presence before targeted refactoring.

## Behaviour/performance contract

No intentional user-visible behaviour, SQL contract, navigation history semantics or performance threshold was changed by Stage 25A. Stage 24 remains the before/after performance baseline.
