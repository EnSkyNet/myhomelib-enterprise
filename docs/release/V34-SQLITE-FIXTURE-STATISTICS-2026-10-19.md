# SQLite performance fixture statistics, 2026-10-19

Base: current master `cc69f26b86a34a391d0ebce2fe36e84a7e206512`, preserving the Xvfb workflow commit from PR #30 (`2567b543`).

## Reproduction and cause

Performance jobs [#39236375077](https://github.com/EnSkyNet/myhomelib-enterprise/actions/runs/39236375077) and [#39289699108](https://github.com/EnSkyNet/myhomelib-enterprise/actions/runs/39289699108) both pass the JVM suite under Xvfb, then reach the 45-minute job deadline during SQLite setup/query measurements. On #30, the 100k profile took about 115 seconds and 500k about 32 minutes; 700k and 1M did not finish.

A native Windows diagnostic executing the existing migration/synthesize/import/query functions with SQLite 3.45.1 reproduced missing `sqlite_stat1` entries for books, authors and book_authors after fixture setup. `PRAGMA optimize` did not analyze these freshly populated tables. The filtered author page consequently took 20,941.424 ms at 100k books (one measured invocation after one warmup). The same diagnostic with SQLite 3.50.4 populated statistics and completed quickly. This is a SQLite version/setup difference, not a claim about Java production import performance.

SQLite documents that optimize is conditional and changed substantially in 3.46: [ANALYZE guidance](https://www.sqlite.org/lang_analyze.html). An explicit full ANALYZE is appropriate for deterministic synthetic benchmark setup; it is not added to application startup or user imports.

## Change and measurements

Replace fixture-only optimize with ANALYZE after population, before the measured import probe and query loops. Report `statistics_mode=full-analyze` and `statistics_ms` within fixture_generation; total fixture time still includes this cost. Keep schema, query SQL, import transaction/rollback, dataset sizes, repeats and thresholds unchanged. Preserve Xvfb from PR #30 and trigger this workflow when benchmark/test sources change.

Native Windows / SQLite 3.45.1, separately generated 100k synthetic fixtures, identical diagnostic (one warmup + one measured invocation):

| Query | Before, ms | After, ms |
|---|---:|---:|
| filtered author page | 20941.424 | 13.283 |
| first author page | 6.400 | 4.574 |
| first catalogue page | 16.902 | 0.215 |
| exact author count diagnostic | 7.561 | 125.675 |
| filtered catalogue page | 0.254 | 61.116 |

These are diagnostic samples, not p95 acceptance or an application-wide speedup. Some plans are slower after full statistics; the complete CI baseline must be evaluated. Historical stored baseline numbers were not replaced. No RSS claim comes from this Windows probe (the benchmark's Linux resource API was not used).

## Tests

`python -B tools/stage24-fixture-statistics-test.py`: 2 tests. Before the fix, SQLite 3.45.1 fails the persisted-statistics assertion. After the fix, both pass on SQLite 3.45.1 and 3.50.4: actual migrated schema, primary-key cardinalities after connection reopen, quick_check, and exact book/link contents preserved after measured import rollback. Test connections close explicitly on Windows. All databases are synthetic temporary fixtures.

Full Linux 100k/500k/700k/1M performance CI is required; no successful result is assumed before it completes. SCA and Windows installer/GUI acceptance remain separate release gates.