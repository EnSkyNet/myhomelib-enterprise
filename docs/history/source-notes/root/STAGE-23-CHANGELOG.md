# Stage 23 Changelog — Cross-platform CI/release

Date: 2026-08-25

## Goal

Make Windows/Linux/macOS release candidates reproducible and gated by the real Maven reactor, platform-native `jpackage`, packaged-launcher smoke tests and checksums.

## Added

- `.github/workflows/ci-release.yml` with a Windows/Linux/macOS JDK 21 matrix.
- Full `./mvnw clean verify -Pproduction` gate on every platform before packaging.
- Linux offline architecture, migration, large-library and Stage 3–22 regression guards in CI.
- `package-portable.sh` and `package-portable.ps1`:
  - create `jpackage --type app-image` output;
  - archive platform app images as `.tar.gz`/`.zip`;
  - platform + architecture artifact naming.
- `smoke-desktop.sh` and `smoke-desktop.ps1` for native packaged-launcher validation.
- `--release-smoke` application path that runs before JavaFX startup and checks cross-module packaged resources.
- `ReleaseSmokeCheck` with required FXML/Flyway/help/language resource checks.
- CI artifact upload for portable desktop archives plus bootstrap/MCP JARs and checksums.
- Tag-gated GitHub Release assembly after **all** matrix jobs succeed.
- Idempotent tagged-release upload (`gh release upload --clobber` on rerun).
- `docs/release/CROSS_PLATFORM_RELEASE.md`.
- `tools/stage23-cross-platform-release-check.py`.

## Changed

- `package-desktop.sh` / `.ps1` now support `MHL_SKIP_BUILD=1` so CI can package the exact jar already validated by `clean verify` instead of recompiling a different candidate.
- `release.sh` / `.ps1` now run verify -> portable jpackage -> packaged-launcher smoke -> checksums.
- README and architecture documentation now describe the cross-platform release boundary and dependency-download-free runtime packaging.

## Runtime network contract

The portable app-image includes a JDK runtime and the already-built application/dependencies. Normal application startup does not invoke Maven or download dependencies. Explicit online-library/network features remain opt-in user functionality and are not a startup prerequisite.
