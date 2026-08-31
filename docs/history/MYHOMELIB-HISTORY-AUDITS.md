# MYHOMELIB — History: Audits, Validation and Upgrades

This file summarizes historical audit/release documents that are no longer active specifications. Exact source notes are preserved under `docs/archive/source-notes/root/` and `docs/archive/source-notes/docs/`.

## Architecture upgrade audits

The v7 and v7.1 architecture passes established the source-neutral catalogue model, additive Flyway upgrade policy, stable IDs/user-data preservation, online revision state, safe networking/download boundaries, Lucene compatibility/fingerprints and the modular dependency rules now consolidated in `ARCHITECTURE.md`.

Sources: `ARCHITECTURE-UPGRADE-v7.md`, `ARCHITECTURE-UPGRADE-v7.1.md`, old `docs/architecture/*` notes.

## Parity and completeness audits

Parity matrices compared the project with earlier MyHomeLib behavior/reference sources while explicitly avoiding literal GPL source reuse. Code-completeness passes searched for no-op/sentinel implementations, misleading compatibility surfaces, dead/unreachable UI/use-case wiring and duplicated behavior. Where a test reflected an obsolete contract, the test was updated instead of reverting correct production code.

Sources: `PARITY_AUDIT.md`, `UPSTREAM-PARITY-MATRIX-v7.1.md`, `CODE-COMPLETENESS-AUDIT-v7.1.md`, `UI-FUNCTION-REACHABILITY-v7.1.md`.

## Functional regression audits

A dedicated functional pass compared later refactors with older working snapshots/FXML/UI paths. It found and repaired real regressions while establishing static/behavior ratchets around author/series navigation, online open/download flows and user-visible reachability.

Source: `FUNCTIONAL-REGRESSION-AUDIT-v7.1.md`.

## Online/download audits

Networking audits covered shared `HttpClient` policy, TLS/proxy secret handling, Range/If-Range resume safety, anti-splice sidecars, cancellation/retry and credential non-leakage. The detailed `ConnectionScript` grammar/macro document has been merged into `MYHOMELIB-OPERATIONS.md` / `MYHOMELIB-DEVELOPMENT.md`.

Sources: `ONLINE-DOWNLOAD-AUDIT-v7.1.md`, `ONLINE-LIBRARY-CONNECTION-SCRIPT-v7.1.md`.

## Performance audit

Performance work separated executed SQLite scale evidence from JVM/Lucene tests that require a connected Maven environment. Stored 100k/500k/1M SQL profiles and query-plan/index guards remain machine-readable; the old prose baseline has been merged into `MYHOMELIB-DEVELOPMENT.md`. Synthetic numbers are regression evidence for their environment, not universal performance claims.

Source: `PERFORMANCE-v7.1.md`, old `docs/PERFORMANCE_BASELINE.md`, raw JSON under `docs/release/`.

## CI/release audit

Cross-platform release work established the JDK 21 Ubuntu/Windows/macOS Maven matrix, `jpackage` app-image creation, headless packaged-launcher `--release-smoke`, tag gating and SHA-256 publication. These requirements are now in `MYHOMELIB-RELEASE.md` and `MYHOMELIB-DEVELOPMENT.md`.

Sources: `GITHUB-CI-v7.1.md`, old `docs/release/CROSS_PLATFORM_RELEASE.md`.

## Upgrade history

The v6 -> v7 and v7 -> v7.1 notes established the backup-first, additive, forward-only Flyway policy and the rule that rollback is performed from a pre-upgrade backup rather than by deleting migrations. Current operational upgrade guidance is in `MYHOMELIB-OPERATIONS.md` and `MYHOMELIB-RELEASE.md`.

Sources: `UPGRADE-FROM-v6.md`, `UPGRADE-FROM-v7.md`.

## Release notes / roadmap checkpoints

The old 1.0.0 and 7.1.0 release notes plus roadmap-completion checkpoint captured state at particular milestones. They are retained as historical evidence only. Current supported behavior is defined by `MYHOMELIB-FEATURES.md`; current validation boundaries are defined by `MYHOMELIB-RELEASE.md`.

Sources: `RELEASE_NOTES_1.0.0.md`, `RELEASE_NOTES_7.1.0.md`, `ROADMAP-COMPLETION.md`, `CONTINUE-AUDIT-v7.1.md`, `NEXT-AUDIT-v7.1.md`.

## Validation principle retained

Historical audit notes repeatedly distinguished "source/static check passed" from "full compiled release validated". That principle remains normative: a connected `./mvnw clean verify -Pproduction` plus real platform CI is required before a formal production release claim.
