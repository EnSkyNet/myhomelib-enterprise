# Smart Collections v1 — performance contract (MHL-114)

**Iteration 32 · 08.09.2026**

## Target

Smart Collections must remain usable on a catalog of **500,000 books** without materializing the complete collection in Java or falling back to an unbounded SQL scan.

## Implementation constraints

- Typed Smart Collection rules are compiled into Lucene queries; AND/OR composition is performed in Lucene.
- Sortable fields use Lucene DocValues so result ordering does not require loading all matching books into application memory.
- A Smart Collection accepts at most **20 rules** and `maxResults` is bounded to **1..100,000**.
- Search results continue through the existing search workspace/page flow instead of creating a second catalog-reading path.
- Lucene schema versioning was changed together with the DocValues mapping, so an older index is rebuilt rather than silently reused with incompatible sort fields.

## Evidence available in this checkpoint

- `SmartCollectionSpecTest`: typed validation/defaults/limits.
- `SmartCollectionUseCasesTest`: UI-safe definition mapping and search-request construction.
- `LuceneSmartCollectionTest`: AND/OR, numeric ranges, format/progress/contains, NOT_EQUALS, sort and maxResults.
- `SqliteSavedSearchRepositorySmartCollectionTest`: persistence, legacy row defaults and pin ordering.
- Stored project scale baseline `docs/performance-baseline.json` contains a **500,000-book** offline SQLite profile and is validated by `tools/stage24-performance-check.py`.

## Important limit of the evidence

The stored 500k baseline is an SQLite/catalog scale baseline, **not a newly measured 500k Lucene Smart Collection benchmark**. Iteration 32 therefore documents the 500k design/acceptance target and verifies that Smart Collections stay on the bounded Lucene path; it does not claim a new p95 latency number for a 500k Lucene corpus.

A dedicated 500k Lucene Smart Collection benchmark remains a useful performance-hardening task, especially before declaring a final 7.2 release candidate.

## Відтворюваний Lucene benchmark

Benchmark є **measurement-only** і не визначає latency SLA. За замовчуванням він створює 500 000 deterministic Lucene documents та вимірює cold/warm first page, AND/OR з 5 і 20 правилами, numeric range, DocValues sort, bounded `maxResults=10000`, heap/RSS і GC deltas.

Після компіляції reactor приклад запуску:

```bash
java -cp "myhomelib-benchmark/target/classes:<lucene classpath>" \
  com.myhomelibcorp.benchmark.search.SmartCollectionLuceneBenchmark \
  --documents 500000 \
  --runs 7 \
  --index verification/benchmarks/smart-500k-index \
  --output verification/benchmarks/smart-500k.csv
```

`--reuse-index` дозволяє повторні query-only runs. `cold_first_page` означає перший query після відкриття нового `DirectoryReader`, а не гарантує OS page-cache-cold стан. CSV є evidence artifact; порівняння коректне лише на тому самому JVM/heap/host. `reported_total_hits` треба читати разом із `total_hits_relation`: `GREATER_THAN_OR_EQUAL_TO` є lower bound, а не exact count.

