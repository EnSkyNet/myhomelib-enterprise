# Stage 4 Validation

Date: 24.08.2026

## Passed offline checks

### Stage 4 SQLite semantics

```bash
python3 tools/stage4-navigation-check.py
```

Result: **PASS**

A temporary SQLite database is migrated through all project Flyway SQL and seeded with keyword variants, ratings, reviews, active/deleted books, built-in groups and a custom group.

Validated:

- comma/semicolon/pipe keyword tokenization;
- keyword counts exclude deleted books;
- exact keyword filtering (`space` does not match `spacecraft`);
- group/Favorites active-book counts;
- empty groups remain visible;
- Rated / Reviewed / Rated & Reviewed subset counts.

### Stage 3 regression

```bash
python3 tools/stage3-navigation-check.py
```

Result: **PASS**

Year, Language and Archive facet/filter semantics from the previous stage remain intact.

### Architecture guard

```bash
python3 tools/architecture-check.py
```

Result: **PASS**

Current debt ratchet remains:

- UI direct output-port users: **18 / 18**
- UI non-value domain-model users: **28 / 28**

Stage 4 guard additionally verifies that:

- `KEYWORDS`, `GROUPS`, `REVIEWS` remain application-level modes;
- `NavigationFacetRepository` owns all three new facet queries;
- `BookQuery` retains keyword/rated/reviewed filters;
- keyword filtering uses exact split-token semantics;
- details-pane deep links exist for keyword/group/review navigation;
- no new UI architecture debt was added.

### Targeted JDK 21 compilation

Changed application navigation/query classes were compiled with:

```bash
javac --release 21
```

against minimal external-boundary stubs.

Result: **PASS**

`BookQueryBuilder` and `SqliteNavigationFacetRepository` were also compiled separately against minimal Spring/JDBC/collection-manager stubs.

Result: **PASS**

A dependency-less `javac -proc:none` syntax scan was also run across the changed JavaFX/UI source files. External symbols are intentionally unresolved without the Maven/JavaFX classpath, but there were **0 Java syntax/parser diagnostics**.

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
- 7 root shell scripts, static issues: 0;
- 500 Java sources / 28 test sources scanned.

### Language catalogues

```bash
python3 tools/validate-language-catalogs.py
```

Result: **PASS**

- `uk`: 154 keys
- `en`: 154 keys
- `bg`: 154 keys
- external and bundled first-run copies are synchronized.

### Python / shell syntax

`py_compile` for Stage 4 / architecture / release / language tools and `bash -n` for root shell scripts: **PASS**.

## Maven / JUnit / ArchUnit runtime status

Attempted:

```bash
./mvnw -o -q \
  -pl myhomelib-application,myhomelib-infrastructure,myhomelib-ui,myhomelib-architecture-tests \
  -am test
```

The Maven Wrapper distribution is not cached in this execution environment. It attempts to bootstrap Maven 3.9.16 from `repo.maven.apache.org`, but DNS/network access is unavailable (`curl: (6) Could not resolve host`). Therefore the complete Maven, JUnit and ArchUnit runtime suite could not be executed here.

This is an environment limitation, not a reported test failure. Before release/merge, run locally:

```bash
./mvnw clean verify
```

## Recommended runtime smoke checks

1. Open navigation and confirm the selector contains Keywords, Groups and Reviews in addition to all Stage 1–3 modes.
2. In Keywords, verify counts and select keywords containing spaces and non-Latin text.
3. Verify selecting `space` does not include a book whose only keyword is `spacecraft`.
4. In Groups, verify Favorites, To Read and custom groups appear; empty groups should show count 0.
5. Add/remove a book from Favorites or a custom group, refresh navigation and verify the count changes.
6. In Reviews, verify Rated, Reviewed and Rated & Reviewed counts and book subsets.
7. Change pages inside a Keyword, Group and Review subset; the filter must remain active.
8. Select a book with keywords/group membership/rating/review and use the details-pane deep links.
9. Confirm a deep link switches the sidebar mode and highlights the corresponding node without opening the workspace twice.
10. Test Back/Forward across Keyword -> Group -> Reviews -> Year/Language/Archive selections.
11. Rapidly switch among all ten navigation modes and verify stale asynchronous results do not replace the active mode.
