# Stage 24 Validation — Performance Baseline

## Result

**PASS for all available offline/static/regression checks.**

## Performance baseline

The offline scale runner was executed against the real migrated V1–V33 SQLite schema for 100,000, 500,000 and 1,000,000 synthetic books. Stored guardrails are PASS.

Key 1M p95 measurements:

- startup-like open + visible-book count: **189.4 ms**;
- Authors `A` navigation facet: **191.6 ms**;
- Languages facet: **1,063.9 ms**;
- first 100 books by title: **0.119 ms**;
- offset-50k page: **21.0 ms**;
- combined filtered page: **400.3 ms**;
- stable `LibID` lookup: **0.007 ms**;
- prepared write probe: **55,943 books/s**.

`EXPLAIN QUERY PLAN` confirms `idx_authors_navigation_initial` for author initials and `idx_books_lib_id` for portable user-data identity lookup.

The 1M fixture is ~803 MiB in this deterministic corpus. Temporary-fixture deletion was deliberately excluded from latency metrics because filesystem cleanup is not application query performance.

## Regression sweep

PASS:

- `tools/static_release_check.py` — 38 XML, 25 FXML, 33 migrations, 633 production Java sources / 58 test sources;
- `tools/architecture-check.py` — dependency graph and UI debt ratchet unchanged;
- `tools/large-library-pre-stage7-check.py`;
- language catalogue validation (`uk/en/bg`, schema 2, 110 genre keys each);
- every Stage 3 through Stage 24 guard;
- OPDS HTTP smoke;
- Reader portable smoke;
- SQLite migration/integrity checks used by the existing regression suite.

## JVM / Maven suite

Stage 24 adds an opt-in Maven performance suite which measures JVM heap/GC, Lucene, and large FB2/EPUB parsing and asserts thresholds. It is wired to the `performance` Maven profile and a weekly/manual GitHub Actions workflow.

It could **not be executed in this container** because Maven is not installed and the Maven wrapper cannot download Maven 3.9.16: DNS/network access to `repo.maven.apache.org` is unavailable (`curl: (6) Could not resolve host`). Therefore this validation does not mislabel Maven/JUnit execution as PASS.

The release CI created in Stage 23 remains responsible for the real JDK/Maven build and the dedicated Stage 24 workflow runs the JVM performance suite on GitHub-hosted infrastructure.
