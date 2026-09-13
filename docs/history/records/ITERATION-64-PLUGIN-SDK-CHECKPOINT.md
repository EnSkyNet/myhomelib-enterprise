# Iteration 64 — Plugin SDK, samples and contract harness (MHL-503)

Date: 2026-09-12
Status: DONE locally

## Scope

- Bumped the additive public Plugin API minor from 1.1 to 1.2.
- Added public `PluginTestHarness` for plugin developer/CI contract checks.
- Added the `myhomelib-plugin-samples` reactor module with three compilable reference plugins:
  - offline dictionary — no capabilities;
  - metadata provider — `NETWORK_ACCESS`;
  - export provider — `FILESYSTEM_READ` + `FILESYSTEM_WRITE`.
- Added a real `META-INF/services/com.myhomelibcorp.plugin.api.PluginEntrypoint` descriptor for all samples.
- Added SDK authoring and compatibility documentation under `docs/plugin-sdk/`.
- Documented the exact boundary: the harness uses a synthetic exact-version trusted approval only during contract validation and does not grant runtime trust or create an OS sandbox.

## Acceptance evidence

- Affected application suite in the targeted reactor: **241 tests, 0 failures, 0 errors, 1 skipped**.
- Plugin API: **25/25 PASS**.
- Plugin SDK samples: **2/2 PASS**.
- Sample module compiles all three reference entrypoints and validates them through `ServiceLoader` + `PluginTestHarness`.
- `LayerArchitectureTest`: **14/14 PASS**.
- `tools/architecture-check.py`: **PASS**.
- `tools/implementation-completeness-check.py`: **PASS**.
- Full **16-project** offline reactor `test-compile`: **16/16 SUCCESS**.
- No newer monolithic all-module test baseline is claimed; Iteration 55 remains the latest monolithic full-reactor baseline.

## Boundary

MHL-504+ remain OPEN. The sample module is developer/reference material and is not wired into the desktop runtime. Strong isolation of untrusted plugins remains a separate out-of-process design problem. External MHL-010/011/012/017/018/019 remain OPEN and require real Windows/GitHub evidence.
