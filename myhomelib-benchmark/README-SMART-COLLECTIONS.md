# Smart Collections / Custom Fields Lucene benchmark

The benchmark is measurement-only: it does **not** define a latency SLA.

Default corpus: 500,000 deterministic Lucene documents. Scenarios:

- cold first page;
- warm first page;
- AND 5 rules / OR 5 rules;
- AND 20 rules / OR 20 rules;
- numeric range;
- DocValues sort;
- bounded `maxResults=10000`;
- heap, Linux RSS (when `/proc/self/status` is available), GC collections/time.

Example after the reactor is compiled:

```bash
java -cp "myhomelib-benchmark/target/classes:<lucene classpath>" \
  com.myhomelibcorp.benchmark.search.SmartCollectionLuceneBenchmark \
  --documents 500000 \
  --runs 7 \
  --index verification/benchmarks/smart-500k-index \
  --output verification/benchmarks/smart-500k.csv
```

Use `--reuse-index` for repeated query-only runs against an already generated corpus.
Here `cold_first_page` means the first query after opening a fresh `DirectoryReader`; it does **not** claim an OS page-cache-cold measurement. Heap/RSS values are samples taken around query executions, while GC counters/time are JVM MXBean deltas. `reported_total_hits` must be interpreted together with `total_hits_relation`: `GREATER_THAN_OR_EQUAL_TO` is a Lucene lower bound, not an exact count.
The CSV is the evidence artifact; compare measurements on the same JVM, heap settings and host.
