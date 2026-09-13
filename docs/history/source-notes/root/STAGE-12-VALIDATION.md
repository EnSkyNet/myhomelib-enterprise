# Stage 12 — Validation

## Passed offline checks

- `tools/stage12-13-collection-maintenance-check.py` — PASS.
- Metadata migrations V1..V3 apply successfully to clean SQLite.
- `collection_source_watch` schema contains persisted source/fingerprint/debounce/update state.
- Source watcher contract contains `WatchService`, create/modify/delete event handling and source-name filtering.
- 60-second application default debounce and rescheduling anti-storm implementation verified.
- SHA-256 streaming fingerprinting verified.
- INPX/ZIP readability validation and manual refresh fallback verified.
- Baseline acknowledge after successful import verified by source wiring.
- Collection Workspace FXML handlers resolve; all 25 FXML documents parse.
- Architecture ratchet PASS: UI output-port users remain 18/18 baseline; domain-model users 28/28 baseline.
- Stage 3..11 regression guards PASS.

## Maven limitation

`./mvnw` cannot run in the execution environment because Maven 3.9.16 is not cached and `repo.maven.apache.org` cannot be resolved. Full `mvn clean verify`, JavaFX runtime smoke and packaged desktop smoke therefore remain required on a network-enabled build machine. Offline static release checks pass.
