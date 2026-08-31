# Stage 22 Validation — Versioned user-data backup/restore

Date: 2026-08-25

## Result

**PASS for all available offline/static/runtime-smoke checks.**

A complete Maven/JUnit build was **not** executed in this packaging environment: `mvn` is not installed and the Maven Wrapper has no cached Maven distribution/dependencies available without external dependency access. This limitation is not reported as a test pass. The added JUnit tests are present for execution in a normal connected/cached build environment.

## Stage 22 dedicated check

Command:

```bash
python3 tools/stage22-versioned-user-data-check.py
```

Result: **PASS**.

Validated:

- portable manifest schema v2 and all required user-data sections;
- sequential v1 -> v2 migration markers;
- future/unsupported schema rejection contract;
- LibID-first lookup with internal-ID fallback;
- bounded restore identity cache;
- dynamic access to the active collection datasource;
- WAL-safe SQLite `VACUUM INTO` snapshot, including an actual WAL-mode runtime smoke test and `PRAGMA integrity_check`;
- staged `.restore.tmp` database replacement and atomic-move path;
- guaranteed close/reopen orchestration plus sequential database migration after full restore;
- legacy database-only backup compatibility;
- full restore vs user-data-only UI contract;
- existing legacy HLC2 LibID/rating/progress mapping;
- JUnit fixtures for changed internal IDs, idempotent restore and previous v1 manifest.

The checker additionally applies **all 33 SQLite migrations to a clean database**, executes the portable export SELECT shapes against the resulting schema, verifies `PRAGMA integrity_check`, and confirms that LibID restore lookup uses `idx_books_lib_id` via `EXPLAIN QUERY PLAN`.

## Full offline regression sweep

### Static release check

```bash
python3 tools/static_release_check.py
```

Result: **PASS**.

- 38 POM/FXML XML files parsed;
- 25 FXML workspaces parsed;
- 161 handler references resolved;
- 33 SQLite migrations applied successfully;
- resulting SQLite integrity: `ok`;
- root shell-script static checks: PASS;
- 631 Java sources / 57 test sources detected at validation time.

### Architecture

```bash
python3 tools/architecture-check.py
```

Result: **PASS**.

- modular dependency baseline intact;
- UI output-port debt ratchet remains 18/18 baseline;
- UI non-value domain-model debt ratchet remains 28/28 baseline;
- Stage 22 introduced no `UI -> infrastructure` dependency.

### Large-library and previous-stage regression guards

All passed:

- `large-library-pre-stage7-check.py`;
- Stage 3 navigation;
- Stage 4 navigation;
- Stage 5 history;
- Stage 6 online update data model;
- Stage 7 online updates UI;
- Stage 8+9 unified filters/table profiles;
- Stage 10+11 rich details/extra-format metadata;
- Stage 12+13 collection maintenance;
- Stage 14+15 ActionRegistry/book actions;
- Stage 16 export/device profiles;
- Stage 17+18 OPDS including loopback HTTP smoke;
- Stage 19+20 reader portable smoke and reader checks;
- Stage 21 help/genre-localization check;
- Stage 22 versioned user-data check.

### Language catalogues

```bash
python3 tools/validate-language-catalogs.py
```

Result: **PASS**.

- `uk`: schema 2, 200 UI keys, 110 genre keys;
- `en`: schema 2, 200 UI keys, 110 genre keys;
- `bg`: schema 2, 200 UI keys, 110 genre keys.

## Maven/JUnit gate still required externally

On a machine with Maven/dependency access or a populated Maven cache, run:

```bash
./mvnw clean verify
```

This remains the required external compile/JUnit/ArchUnit gate before treating a binary package as fully build-validated.
