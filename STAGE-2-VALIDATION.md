# Stage 2 Validation

Date: 24.08.2026

## Passed offline checks

### Architecture guard

Command:

```bash
python3 tools/architecture-check.py
```

Result: **PASS**

Current ratchet:

- UI direct output-port users: **18 / 18**
- UI non-value domain-model users: **28 / 28**

The guard additionally verifies that:

- the four Stage 2 navigation-core application files exist;
- `NavigationPanelController` does not declare its own `NavigationMode`;
- it depends on `NavigationQueryService`;
- it never generates `SeriesId` values;
- the obsolete parallel navigation API stays removed.

### Targeted Java compilation

The four new production navigation-core classes were compiled with JDK 21
against minimal boundary stubs to validate Java syntax/type usage independently
of Maven dependency downloads.

Result: **PASS**

### Static release check

Command:

```bash
python3 tools/static_release_check.py
```

Result: **PASS**

Validated:

- all POM/FXML XML files;
- FXML handler references;
- all SQLite/Flyway migrations in an offline database;
- SQLite `integrity_check`;
- root shell-script static checks;
- Java/test source inventory.

### Language catalogues

Command:

```bash
python3 tools/validate-language-catalogs.py
```

Result: **PASS**

- `uk`: 144 keys
- `en`: 144 keys
- `bg`: 144 keys
- external `Lang/*.json` and bundled first-run copies remain synchronized.

## Maven/ArchUnit runtime status

A full Maven test run was attempted with:

```bash
./mvnw -o -q -pl myhomelib-application,myhomelib-ui,myhomelib-architecture-tests -am test
```

The Maven Wrapper itself is not cached in this environment and attempted to
resolve Maven 3.9.16 from `repo.maven.apache.org`. DNS/network access is not
available here, so the wrapper cannot bootstrap and the Maven/ArchUnit/JUnit
runtime suite cannot be executed in this container.

This is an environment limitation, not a reported test failure. Run locally:

```bash
./mvnw clean verify
```

before release/merge.

## Recommended runtime smoke checks

1. Start the application and confirm the navigation selector contains Authors,
   Series, Genres and All Books.
2. Switch modes rapidly and confirm an older result never replaces the active
   mode.
3. Select an author and confirm the author workspace opens.
4. Select a series and confirm the paginated book table opens with only that
   series.
5. Select a genre and confirm the paginated book table opens with only that
   genre.
6. Select All Books and confirm the table is paginated and the navigation node
   count matches the active collection.
7. Import a book with a previously unseen series and confirm that series appears
   after the import refresh with a stable ID across subsequent refreshes.
8. Test Back/Forward around series/genre/all-books entries. (A deeper history
   redesign remains scheduled for Stage 5.)
