# Stage 3 Validation

Date: 24.08.2026

## Passed offline checks

### Architecture guard

```bash
python3 tools/architecture-check.py
```

Result: **PASS**

Current debt ratchet remains:

- UI direct output-port users: **18 / 18**
- UI non-value domain-model users: **28 / 28**

Stage 3 guard additionally verifies that:

- `YEARS`, `LANGUAGES`, `ARCHIVES` remain application-level modes;
- `NavigationFacetRepository` exists;
- the SQLite aggregation adapter exists;
- navigation facets do not fall back to `streamAll()`;
- `BookQuery` keeps the year/archive filters;
- JavaFX presentation handles all three new modes.

### Stage 3 SQLite semantics

```bash
python3 tools/stage3-navigation-check.py
```

Result: **PASS**

A temporary database is migrated through all project Flyway SQL and seeded with
active/deleted books, multiple years/languages and duplicate archive file names.
Validated:

- year grouping/counts and newest-first semantics;
- case-insensitive language grouping;
- physical archive-container grouping;
- deleted rows excluded;
- year filter;
- language filter;
- archive collection-root/path filter.

### Targeted JDK 21 compilation

New/changed application navigation and query classes plus `BookQueryBuilder`
were compiled with `javac --release 21` against minimal external-boundary stubs.

Result: **PASS**

`SqliteNavigationFacetRepository` was also compiled separately against minimal
Spring JDBC/collection-manager stubs.

Result: **PASS**

### Static release check

```bash
python3 tools/static_release_check.py
```

Result: **PASS**

Validated:

- 36 POM/FXML XML files, errors: 0;
- 24 FXML workspaces;
- 136 FXML handler references, missing: 0;
- 29 SQLite migrations, errors: 0;
- SQLite `integrity_check`: `ok`;
- 7 root shell scripts, static issues: 0.

### Language catalogues

```bash
python3 tools/validate-language-catalogs.py
```

Result: **PASS**

- `uk`: 147 keys
- `en`: 147 keys
- `bg`: 147 keys
- external and bundled first-run copies are synchronized.

### Python / shell syntax

`py_compile` for release/architecture/language/Stage-3 tools and `bash -n` for
root shell scripts: **PASS**.

## Maven / JUnit / ArchUnit runtime status

Attempted:

```bash
./mvnw -o -q \
  -pl myhomelib-application,myhomelib-infrastructure,myhomelib-ui,myhomelib-architecture-tests \
  -am test
```

The Maven Wrapper is not cached in this execution environment. It attempts to
bootstrap Maven 3.9.16 from `repo.maven.apache.org`, but DNS/network access is
unavailable (`curl: (6) Could not resolve host`). Therefore the full Maven,
JUnit and ArchUnit runtime suite cannot be executed here.

This is an environment limitation, not a reported test failure. Before a
release/merge, run locally:

```bash
./mvnw clean verify
```

## Recommended runtime smoke checks

1. Open navigation and confirm the selector contains Authors, Series, Genres,
   Years, Languages, Archives and All Books.
2. Select Years and verify newest years appear first with counts.
3. Select a year and page forward/backward; all rows must keep that year.
4. Select Languages and verify human-readable localized language names plus
   stable codes are shown.
5. Select a language and page through its books.
6. Select Archives and verify only physical archive containers appear, with
   counts and disambiguated labels for duplicate file names.
7. Select an archive and verify only entries from that archive appear.
8. Test Back/Forward across Year -> Language -> Archive selections.
9. Select an alphabet letter in Authors, then switch to Years; the year list
   must remain visible (alphabet filter resets).
10. Rapidly switch among all seven modes and verify an older async response does
    not replace the active mode.
