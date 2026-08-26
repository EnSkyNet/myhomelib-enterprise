# STAGE 7 VALIDATION

Date: 2026-08-25

## Passed offline/static checks

### Release structure

`python3 tools/static_release_check.py`

PASS:
- 37 XML POM/FXML files parsed;
- 25 FXML workspaces checked;
- 141 FXML handler references checked, 0 missing;
- 32 SQLite migrations parsed/integrity checked;
- shell static checks passed.

### Architecture

`python3 tools/architecture-check.py`

PASS: dependency graph and UI debt ratchet remain within the documented baseline.

### Language catalogues

`python3 tools/validate-language-catalogs.py`

PASS:
- bg: 173 keys;
- en: 173 keys;
- uk: 173 keys;
- root drop-in catalogues equal bundled first-run copies.

### Regression checks for previous stages

PASS:
- `tools/stage3-navigation-check.py`
- `tools/stage4-navigation-check.py`
- `tools/stage5-history-check.py`
- `tools/stage6-online-update-check.py`

Stage 6 regression check still confirms:
- stable source identity;
- no false updates for repeated identical sync;
- exactly one update for a changed downloaded book;
- user/local state survives catalog UPSERT;
- followed-author new-book detection;
- successful download baseline acknowledgement.

### Large-library stabilization

`python3 tools/large-library-pre-stage7-check.py`

PASS:
- AUTHORS navigation is SQL-initial-scoped;
- author initial expression index exists;
- no production `authorRepository.findAll()` / `dictionaryCache.loadAuthors(...)` remains;
- INPX progress/status callback chain is connected;
- selected/active collection state is separated.

### Stage 7

`python3 tools/stage7-online-updates-ui-check.py`

PASS:
- Updates navigation + pending counter;
- Author -> New/Updated -> Book snapshot;
- deterministic followed co-author grouping;
- force-download path for `UPDATED_DOWNLOADED_BOOK`;
- successful download acknowledgement path;
- author/book deep links;
- empty state;
- Updates FXML parsing.

## Maven validation limitation

Attempted:

`./mvnw -q -DskipTests compile`

The wrapper cannot bootstrap Maven because this execution environment cannot resolve `repo.maven.apache.org` (`curl: (6) Could not resolve host`). There is no preinstalled `mvn` and no populated local Maven cache in the runtime.

Therefore `mvn clean verify`, JavaFX runtime smoke testing and generated MapStruct/Lombok compilation could not be executed here. This is an environment/network limitation, not a reported project compilation failure.

## Recommended first check on a normal development machine

```bash
./mvnw clean verify
```

Then launch the application against a large/sanitized collection and verify:

1. AUTHORS startup selects the first initial without loading all authors;
2. switching initials performs bounded SQL queries;
3. 700k INPX import reports count/progress/statistics without a post-import author heap spike;
4. Updates shows `Author -> New/Updated -> Book`;
5. downloading an updated local book fetches fresh bytes and removes its pending event;
6. downloading the final pending event moves Updates to the empty state and sets the navigation counter to 0.
