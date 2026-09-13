# Stage 24 Changelog — Performance Baseline

## Scope

Stage 24 implements the roadmap performance-baseline milestone. The goal is measurement and regression protection, not speculative optimization.

## Added

- `tools/stage24-performance-baseline.py`
  - deterministic synthetic catalogues at 100k / 500k / 1M books;
  - applies the real SQLite V1–V33 migration set;
  - measures startup-like DB open/count, author navigation, filtered author navigation, language facet, first/deep/filtered pages, text fallback search and stable LibID lookup;
  - prepared/batched write probe against the fully indexed schema;
  - records `EXPLAIN QUERY PLAN` evidence and process peak RSS;
  - machine-readable JSON output and explicit performance guardrails.
- `docs/performance-baseline.json` with the measured 100k / 500k / 1M baseline.
- `docs/PERFORMANCE_BASELINE.md` with a human-readable baseline table and query-plan findings.
- Opt-in JVM performance regression suite in `myhomelib-benchmark`:
  - real Flyway migrated SQLite fixtures;
  - heap peak sampling through `MemoryMXBean`;
  - GC collection/time observations through `GarbageCollectorMXBean`;
  - Lucene index/query benchmark;
  - large synthetic FB2 and EPUB parser benchmarks;
  - machine-readable `target/performance-baseline-jvm.json`;
  - threshold assertions.
- Maven `performance` profile.
- Weekly/manual `.github/workflows/performance-baseline.yml` workflow.
- `tools/stage24-performance-check.py` fast release guard.

## Release integration

`ci-release.yml` now runs both the Stage 23 release-contract guard and the fast Stage 24 stored-baseline guard. The expensive 100k/500k/1M generation is deliberately kept in the dedicated performance workflow so normal releases do not pay the benchmark cost.

## Measured baseline in this validation environment

| Books | Startup-like p95 | Authors A p95 | Languages p95 | First page p95 | Deep page 50k p95 | Filtered page p95 | LibID p95 | Write probe |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 100,000 | 15.6 ms | 10.7 ms | 76.2 ms | 0.107 ms | 20.9 ms | 38.6 ms | 0.006 ms | 60,910 books/s |
| 500,000 | 68.6 ms | 94.1 ms | 570.2 ms | 0.123 ms | 21.9 ms | 223.6 ms | 0.006 ms | 58,085 books/s |
| 1,000,000 | 189.4 ms | 191.6 ms | 1,063.9 ms | 0.119 ms | 21.0 ms | 400.3 ms | 0.007 ms | 55,943 books/s |

## Findings

- Author-initial navigation uses `idx_authors_navigation_initial` plus `idx_book_authors_author_id`; the large-library fix remains effective at 1M books.
- Stable `LibID` lookup uses `idx_books_lib_id` and remains effectively constant-time at this scale.
- First-page title browsing uses `idx_books_title` and stays sub-millisecond in the SQL baseline.
- The language facet still scans `books`; at 1M its p95 is about 1.06 s. This is below the deliberately conservative 2 s guardrail, so Stage 24 does not add an unproven extra index.
- The combined language/local/year filtered page uses the year index and a sort; at 1M it remains below its 1.5 s guardrail.

No product behaviour was intentionally changed by Stage 24.
