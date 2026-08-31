# Stage 1 — Validation Report

Date: 24.08.2026

## Passed offline checks

### Architecture guard

Command:

```bash
python3 tools/architecture-check.py
```

Result: **PASS**.

Validated direct production graph:

```text
myhomelib-shared             -> -
myhomelib-domain             -> myhomelib-shared
myhomelib-application        -> myhomelib-domain, myhomelib-shared
myhomelib-infrastructure     -> myhomelib-application, myhomelib-domain, myhomelib-shared
myhomelib-reader             -> myhomelib-shared
myhomelib-ui                 -> myhomelib-application, myhomelib-domain, myhomelib-reader, myhomelib-shared
myhomelib-bootstrap          -> myhomelib-application, myhomelib-domain, myhomelib-infrastructure, myhomelib-shared, myhomelib-ui
myhomelib-mcp                -> myhomelib-shared
```

Debt ratchet at packaging time:

- UI -> `application.port.out`: 19 / baseline 19;
- UI -> non-value domain model: 29 / baseline 29;
- no new architecture-debt classes.

### Offline release sanity check

Command:

```bash
python3 tools/static_release_check.py
```

Result: **PASS**.

- POM + FXML XML files: 36; errors: 0;
- FXML workspaces: 24;
- FXML handler references: 139; missing: 0;
- SQLite migrations: 29; errors: 0;
- SQLite `PRAGMA integrity_check`: `ok`;
- root shell scripts: 7; static issues: 0;
- Java sources: 489;
- test sources: 23.

### Language catalogue validation

Command:

```bash
python3 tools/validate-language-catalogs.py
```

Result: **PASS**.

- `uk.json`: 141 keys;
- `en.json`: 141 keys;
- `bg.json`: 141 keys.

### Script syntax

Commands:

```bash
python3 -m py_compile tools/*.py
for f in *.sh; do bash -n "$f"; done
```

Result: **PASS**.

### POM XML parse

All 12 root/module `pom.xml` files parsed successfully with the standard XML
parser.

## Maven / ArchUnit runtime validation

Attempted command:

```bash
./mvnw -version
```

The included wrapper is now executable, but the packaging environment cannot
resolve Maven Central:

```text
curl: (6) Could not resolve host: repo.maven.apache.org
```

Therefore these network/cache-dependent gates could not be executed here:

```bash
./mvnw -pl myhomelib-architecture-tests -am test
./mvnw clean verify
```

The source-level offline architecture guard was added specifically so module
and source boundary regressions are still detected when Maven dependencies are
unavailable. A machine with Maven Central access or a populated Maven cache
should run the two commands above before producing a binary release.
