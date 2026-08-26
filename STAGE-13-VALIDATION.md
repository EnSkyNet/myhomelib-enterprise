# Stage 13 — Validation

## Passed offline checks

- `tools/stage12-13-collection-maintenance-check.py` — PASS.
- Runtime SQLite smoke verifies deterministic duplicate query, `PRAGMA quick_check`, `VACUUM INTO` backup and safe `local=0` repair semantics.
- Mandatory backup-before-apply markers verified.
- Physical orphan auto-delete is absent from maintenance implementation.
- Legacy destructive repair call is absent from UI and disabled in application/infrastructure.
- Foreign keys explicitly enabled for default/per-collection and metadata SQLite connections.
- All 33 library migrations apply successfully; SQLite integrity is `ok`.
- All 3 metadata migrations apply successfully.
- All 25 FXML files parse; handler references resolve in offline release check.
- Language catalog validation PASS for uk/en/bg (200 keys each).
- Architecture check PASS.
- Large-library pre-Stage-7 guard PASS.
- Stage 3, 4, 5, 6, 7, 8+9, 10+11, 12+13 regression checks PASS.
- `tools/static_release_check.py` — PASS.

## Maven limitation

Full Maven/JUnit validation could not run in this environment: the Maven wrapper needs to download Maven 3.9.16 and DNS/network access to `repo.maven.apache.org` is unavailable (`curl: (6) Could not resolve host`). The new JUnit tests are included for execution in CI/a normal development environment:

- `CollectionSourceMonitorAdapterTest`
- `CollectionMaintenanceAdapterTest`

A network-enabled build should run `./mvnw clean verify` before distribution.
