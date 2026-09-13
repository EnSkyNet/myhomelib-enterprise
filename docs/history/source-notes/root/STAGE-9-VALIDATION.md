# Stage 9 — Table Profiles + Quick Filters — Validation

Дата: 2026-08-25

## PASS

- `tools/stage8-9-filter-table-check.py` — PASS.
- Table profiles persistence paths + per-workspace keys — static regression PASS.
- Table sort policy delegates to `BookLoaderService.setSort()` — PASS.
- Unsupported SQL sort columns explicitly non-sortable — PASS.
- Series grouping helper preserves incoming book order — regression test added.
- V33 fresh migration + V32 upgrade/backfill — PASS.
- Fast INPX writer contract: 29 columns / 29 placeholders and explicit `format/author_sort` setters — PASS.
- Fast INPX author-sort derivation regression test added.
- Legacy HLC2 denormalized refresh path present — PASS.
- No production `authorRepository.findAll()` / `dictionaryCache.loadAuthors()` reintroduced — PASS.
- 25 FXML files parse — PASS.
- uk/en/bg catalogs parse and contain Stage 8/9 keys — PASS.

## Maven limitation

Full Maven/JUnit suite could not run in this runtime because Maven Wrapper requires network access to download Maven 3.9.16 and DNS to `repo.maven.apache.org` is unavailable. Offline/static/SQLite/pure-Java validations above were executed successfully; CI should run `./mvnw verify` in a network-enabled or pre-cached environment.
