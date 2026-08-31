# MyHomeLib Enterprise v7.1 — Performance

Status date: 2026-08-30. Measurements below distinguish **executed offline SQLite measurements** from **JVM/Lucene measurements that are implemented but not executable in the current isolated environment**.

## Architecture changes intended to remove progressive slowdown

- full catalog persistence remains bounded-batch/streaming;
- full-snapshot seen-state uses SQLite rather than a 700k-ID Java set;
- Lucene source traversal is keyset based rather than progressive `OFFSET` paging;
- author/genre enrichment is batched with SQLite-safe chunk sizes, avoiding per-book N+1 queries;
- full rebuild does not create one future/task per book;
- manifest fast-check avoids reparsing unchanged compatible datasets;
- versioned searchable fingerprints allow unchanged Lucene documents to be skipped;
- Lucene has one final commit for full rebuild, rollback on failure/cancel, and the old committed index remains usable until success;
- `LuceneIndexWriterFactory` centralizes writer tuning; `LuceneIndexMetrics`/`SearchIndexPerformanceReport` record rebuild telemetry.

## Executed v7.1 SQLite scale measurements

These runs use CPython `sqlite3` against the current V1–V40 migrated schema. They are deterministic regression probes, not a hardware-independent SLA. Environment observed in this container: Linux x86_64, SQLite/Python versions are recorded in the generated JSON reports under `target/`.

| Books | Import write probe | Authors(A) p95 | First page p95 | Result |
|---:|---:|---:|---:|---|
| 100,000 | 71,882 books/s | 11.2 ms | 0.096 ms | PASS |
| 500,000 | 68,309 books/s | 82.2 ms | 0.099 ms | PASS |
| 700,000 | 66,281 books/s | 120.1 ms | 0.110 ms | PASS |
| 1,000,000 | 65,304 books/s | 170.1 ms | 0.101 ms | PASS |

The 2026-08-30 run was executed in two completed invocations (100k/500k/700k and 1M) so a tool-window timeout could not be misreported as a benchmark result. All configured Stage 24 guardrails passed. Raw evidence is retained in `docs/release/PERFORMANCE-v7.1-SQLITE-RAW-2026-08-30.json`; the 2026-08-28 evidence remains in `docs/release/PERFORMANCE-v7.1-SQLITE-RAW.json` for comparison. The environment signature is the same in both reports (Python 3.13.5, SQLite 3.46.1, Linux x86_64, 5 reported CPUs), but these synthetic timings can still vary between runs, so the deltas are regression evidence rather than a claim that Java v7.1 is a specific percentage faster.

## INPX batch-size and index-maintenance probe

A separate SQLite-only probe uses WAL + `synchronous=NORMAL` + the current bulk cache/mmap settings and compares `app.import.batch-size` candidates 500/1000/5000/10000 at 100k and 500k catalog sizes. With normal indexes intact, the median write throughput across the two sizes was 74,944 / 75,656 / 73,256 / 73,846 rows/s respectively. `1000` is therefore the selected default: it is the measured winner in the aggregate probe while keeping Java-side lists materially smaller than 5k/10k. Production/development configuration and application/UI fallbacks are aligned to `1000`.

The same probe shows that suspending only `idx_books_title`, `idx_books_series`, and `idx_authors_last_name` can improve the 500k write probe from about 72.3k rows/s to 79.5k rows/s at batch 1000. The optimization is retained, but restore is now schema-safe: the exact live `CREATE INDEX` definitions are captured from `sqlite_master` and restored in `finally`; the obsolete named relation indexes are no longer created. Raw evidence is `docs/release/PERFORMANCE-v7.1-INPX-BATCH-RAW.json`. This remains SQLite evidence only; JVM heap/GC and real `JdbcTemplate` allocation measurements are still required before formal release acceptance.

## Physical-duplicate composite-index probe

The P1 candidate index `books(lib_id, collection_root, folder, file_name, archive_entry)` was measured before any migration was added. The current duplicate-statistics query groups by `lib_id` plus `COALESCE(...)` expressions. On both 100k and 500k fixtures, `EXPLAIN QUERY PLAN` continued to use `idx_books_lib_id` and a temporary B-tree for the `GROUP BY`; the candidate composite index was not selected for that query.

Measured medians:

| Books | Duplicate scan without candidate | With candidate | Query delta | Insert probe delta |
|---:|---:|---:|---:|---:|
| 100,000 | 103.6 ms | 101.3 ms | ~2.3% faster | ~12.4% slower |
| 500,000 | 538.1 ms | 508.7 ms | ~5.5% faster | ~4.0% slower |

Decision: **do not add this index in v7.1**. The plan does not consume it for the actual expression-based grouping, while the write/import penalty is measurable. If duplicate-statistics latency becomes a real user bottleneck later, evaluate a matching expression/partial index or a materialized physical-identity key separately, again with import-cost evidence. Raw evidence is `docs/release/PERFORMANCE-v7.1-DUPLICATE-INDEX-PROBE.json`; the reproducible probe is `tools/duplicate-index-benchmark.py`.

## JVM/Lucene benchmark contract

`myhomelib-benchmark/PerformanceBaselineTest` uses a disk-backed Lucene `FSDirectory` and accepts `mhl.performance.sizes=100000,500000,700000,1000000`. It records, per Lucene size:

- full index duration and docs/s;
- selective update document count, duration and docs/s;
- query latency;
- peak heap delta and GC collection delta;
- final index size;
- segment count.

The production rebuild telemetry additionally exposes DB-read, document-build, Lucene-write, merge-wait and commit durations plus GC time delta. These metrics are intentionally not inferred from Python SQLite timings.

## Missing required before/after figures

A connected Maven/JDK run is still required to fill the v7 vs v7.1 table for approximately 700k books:

| Metric | v7 | v7.1 | Status |
|---|---:|---:|---|
| Full Lucene rebuild | — | — | NOT MEASURED HERE |
| Selective reindex (~10k changed) | — | — | NOT MEASURED HERE |
| Lucene docs/s | — | — | NOT MEASURED HERE |
| DB read | — | — | instrumentation implemented |
| Document construction | — | — | instrumentation implemented |
| Lucene write | — | — | instrumentation implemented |
| Merge wait | — | — | instrumentation implemented |
| Commit | — | — | instrumentation implemented |
| Peak heap / GC | — | — | instrumentation implemented |
| Final index size / segments | — | — | instrumentation implemented |

No statement such as “v7.1 is X% faster” is made until those measurements are executed on the same machine/profile.

## Reproduction

Offline SQLite baseline:

```bash
python3 tools/stage24-performance-baseline.py --sizes 100000,500000,700000,1000000 --out target/performance-baseline-v71-sqlite.json
```

JVM/Lucene/reader suite:

```bash
./mvnw -B -ntp -pl myhomelib-benchmark -am test -Pperformance \
  -Dmhl.performance.sizes=100000,500000,700000,1000000
```

The scheduled GitHub workflow runs the same Maven performance profile and uploads the JSON report.
