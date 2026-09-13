# Stage 24 Performance Baseline

Measured on the validation container with CPython sqlite3 against the real migrated V1–V33 schema. These SQL numbers are a regression baseline, not a promise for every machine. JVM heap/GC, Lucene and reader measurements are provided by the Maven performance suite.

| Books | Startup-like p95 | Authors A p95 | Languages p95 | First page p95 | Deep page 50k p95 | Filtered page p95 | LibID p95 | Import probe |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 100,000 | 15.6 ms | 10.7 ms | 76.2 ms | 0.107 ms | 20.9 ms | 38.6 ms | 0.006 ms | 60,910 books/s |
| 500,000 | 68.6 ms | 94.1 ms | 570.2 ms | 0.123 ms | 21.9 ms | 223.6 ms | 0.006 ms | 58,085 books/s |
| 1,000,000 | 189.4 ms | 191.6 ms | 1063.9 ms | 0.119 ms | 21.0 ms | 400.3 ms | 0.007 ms | 55,943 books/s |

## Query-plan findings

- Author initial uses `idx_authors_navigation_initial` and `idx_book_authors_author_id`; no `SELECT * FROM authors` regression.
- Stable LibID restore lookup uses `idx_books_lib_id`.
- Title first-page browsing uses `idx_books_title`.
- Language facet still scans the books table; at 1M it remains below the Stage 24 2s guardrail but is now explicitly measured.
- The combined language/local/year filtered page uses `idx_books_year` plus a sort; at 1M it remains below the 1.5s guardrail.

## Guardrails

Overall: **PASS**.

Guardrails are intentionally looser than this machine baseline and are meant to catch large regressions. They are not used to justify micro-optimizations that benchmarks do not support.
