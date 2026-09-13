# Iteration 63 — Plugin permissions and isolation (MHL-502)

Date: 2026-09-12
Status: DONE locally

## Scope

- Bumped the public Plugin API from 1.0 to 1.1 without removing the legacy no-permissions `PluginManifest` constructor.
- Added explicit `NETWORK_ACCESS`, `FILESYSTEM_READ` and `FILESYSTEM_WRITE` manifest capabilities.
- Added host-owned `TRUSTED` / `UNTRUSTED` state and approval bound to the exact plugin id/version.
- Activation fails closed when approval is absent, stale, untrusted or does not exactly match requested capabilities.
- Added manifest-only activation preview so requested permissions can be shown before services are touched.
- A changed plugin version returns to `UNTRUSTED` instead of inheriting prior approval.
- Added enabled/disabled/quarantined lifecycle with explicit re-enable after disable/quarantine.
- Added a managed invocation boundary that quarantines ordinary exceptions, linkage failures and assertion failures rather than propagating them through normal host control flow. Fatal VM errors are not intercepted.
- Added optional per-invocation capability requirements; missing approved permissions return a blocked result without calling plugin code.
- Made loader/manager state collections safe for concurrent host access.

## Security boundary

This iteration does **not** claim that trusted Java plugins are OS-sandboxed. In-process code can use JDK APIs directly, so permission declarations are host approval/capability contracts, not a replacement for process isolation. `UNTRUSTED` plugins are therefore rejected for in-process execution. A future out-of-process plugin host would be required for stronger isolation of untrusted code.

The `ServiceLoader` convenience path remains explicitly limited to a trusted classpath. External/untrusted plugin discovery should inspect metadata before loading executable plugin classes.

## Acceptance evidence

- Plugin API targeted contract: **22/22 PASS**.
  - permissions visible before enable;
  - explicit approval required and bound to id/version;
  - untrusted in-process activation blocked;
  - network/filesystem capability mismatch blocked;
  - capability-gated invocation fails closed;
  - ordinary exception and `AssertionError` are contained and quarantine the plugin;
  - quarantined plugin can be disabled and requires explicit approval to re-enable;
  - changed plugin version returns to untrusted preview state;
  - incompatible API and silent core-service override remain blocked.
- Affected application dependency suite in the targeted reactor: **241 tests, 0 failures, 0 errors, 1 skipped**.
- `LayerArchitectureTest`: **14/14 PASS**.
- `tools/architecture-check.py`: **PASS**.
- `tools/implementation-completeness-check.py`: **PASS**.
- Full **15-project** offline reactor `test-compile`: **PASS**.
- A monolithic all-module `test` run was attempted but exceeded the execution window while running the unchanged infrastructure suite; no failure was observed before termination, and no newer monolithic full-reactor baseline is claimed. Iteration 55 remains the latest such baseline.

## Boundary

MHL-503 (Plugin SDK/docs/sample plugins) remains OPEN. Strong sandboxing of untrusted third-party code is also outside this in-process API iteration and requires a separate process-isolation design. The six external Windows/GitHub acceptance items remain OPEN.
