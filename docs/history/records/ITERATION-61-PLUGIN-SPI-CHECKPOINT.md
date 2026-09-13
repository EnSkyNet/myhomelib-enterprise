# Iteration 61 — Plugin SPI core (MHL-501)

Date: 2026-09-12
Status: DONE locally

## Scope

- Added `myhomelib-plugin-api` as a dedicated public plugin contract module.
- Exposed the nine release-8.0 extension points: `MetadataProvider`, `CoverProvider`, `BookImporter`, `MetadataExtractor`, `ContentExtractor`, `ExportProvider`, `TranslationProvider`, `DictionaryProvider`, `DeviceProvider`.
- Added versioned `PluginManifest`/`PluginApiRange`, `PluginEntrypoint`, validated `PluginLoader` and `LoadedPlugin`.
- Existing mature application SPIs are surfaced through compatibility facades rather than moved, avoiding a breaking refactor.
- A plugin may replace a host/core service only when that service is explicitly listed in `overridesCoreServices`.
- ServiceLoader sample plugin verifies the JAR-style discovery path.

## Acceptance evidence

- Plugin API targeted contract: 9/9 PASS.
  - sample plugin loads through `ServiceLoader`;
  - incompatible API version is rejected;
  - silent core override is rejected; explicit override is accepted;
  - service type/binding mismatch is rejected;
  - exact nine-service public surface is guarded.
- `LayerArchitectureTest`: 14/14 PASS after adding the plugin-api layer/module.
- `tools/architecture-check.py`: PASS.
- `tools/implementation-completeness-check.py`: PASS.
- Latest full-reactor baseline remains Iteration 55 (13-module baseline at that time, 907 tests); Iteration 61 does not claim a new full-reactor run.

## Boundary

MHL-502 (plugin permissions/isolation), MHL-503 (SDK/samples) and downstream device/conversion/AI plugins remain OPEN.
