# MyHomeLib Enterprise v7.1 — GitHub CI

Status date: 2026-08-28.

## Required CI

`.github/workflows/ci-release.yml` runs the required matrix on:

- `ubuntu-latest`;
- `windows-latest`;
- `macos-latest`.

Every platform uses JDK 21 and executes the Maven Wrapper with:

```text
clean verify -Pproduction
```

The required build does not use `continue-on-error`, `-DskipTests`, module exclusion or disabled compilation. Linux additionally executes the offline architecture/regression guards. After verification each platform creates a `jpackage --type app-image` portable archive, executes the release smoke path and uploads platform artifacts plus SHA-256 data.

Tagged `v*` builds depend on all matrix jobs and only then assemble/publish release assets.

## Performance CI

`.github/workflows/performance-baseline.yml` is manual and scheduled weekly. It runs the Maven `performance` profile on JDK 21 with synthetic sizes 100k, 500k, 700k and 1M, plus the deterministic SQLite scale baseline and Stage 24 contract check. Reports are uploaded even when a benchmark job fails so regressions remain inspectable.

## Workflow audit performed locally

Available local checks confirm:

- workflow YAML parses;
- `actions/checkout@v4` and `actions/setup-java@v4` are present;
- JDK 21 and Maven cache are configured;
- Unix wrapper/script execute bits are set in CI;
- Windows uses `mvnw.cmd`/PowerShell;
- the production profile is referenced;
- required offline checks and packaging scripts exist;
- no required-build failure masking is configured.

## Offline source-artifact gate

`tools/package-v71-source.py` has been executed locally and verifies the working source, a clean copied tree and a freshly extracted source ZIP with migration/hash, XML/FXML, architecture/lifecycle, standalone JDK v7.1 runtime smoke, Stage 8+9, Stage 24, Stage 25C, workflow YAML and shell-syntax gates. This is **not** a substitute for the required GitHub matrix.

## What is **not** claimed

The current execution environment cannot access GitHub Actions or Maven Central. No GitHub workflow was actually launched from this environment. Therefore the following mandatory release-report fields are currently unavailable and must remain `NOT VERIFIED`:

| Field | Current value |
|---|---|
| Release commit SHA | NOT VERIFIED / working tree is not a published release commit |
| GitHub run ID | NOT VERIFIED |
| GitHub run URL | NOT VERIFIED |
| Ubuntu result | NOT VERIFIED |
| Windows result | NOT VERIFIED |
| macOS result | NOT VERIFIED |

A v7.1 release must not be called CI-green until a real run records these values. The recommended release sequence is: commit the reviewed source → push branch/tag → wait for all three `verify-package` jobs → record run ID/URL and platform results here → publish/tag assets only from the successful workflow.
