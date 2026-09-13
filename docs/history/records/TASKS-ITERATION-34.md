# MyHomeLib — Iteration 34: MHL-116 + MHL-117 + performance hardening

**Дата:** 09.09.2026  
**База:** Iteration 33 (MHL-112 acceptance + MHL-115 Custom Fields)

## Scope

### MHL-116 — Artifact integrity/hash audit
- incremental local artifact scan;
- exists / size / SHA-256 / readable archive checks;
- persistent audit baseline/cache independent of catalog baseline;
- separate missing / corrupt-unreadable / changed reporting;
- no destructive auto-repair and no implicit rewrite of `book_artifacts.sha256`/`size_bytes`.

### MHL-117 — Library Health dashboard
- KPI: missing, corrupt, changed, duplicates, metadata gaps, Lucene freshness, backup age;
- async refresh;
- actionable issue table + drill-down;
- text export;
- audit serialized through the library operation coordinator.

### Performance hardening
- deterministic 500k Lucene corpus;
- cold/warm first page;
- AND/OR 5 and 20 rules;
- numeric range;
- DocValues sort;
- bounded maxResults;
- heap/RSS/GC telemetry;
- measurements only, no invented latency SLA.

## Acceptance sequence

Після завершення **всіх** source/doc/test changes:
1. offline `test-compile` for reactor;
2. targeted MHL-116/MHL-117 tests;
3. UI + architecture tests;
4. static gates including `stage34-artifact-health-check.py`;
5. broader modular regression if time budget permits;
6. package clean source archive without `target/`.

## Status

**Completed.** Source/doc/test changes are finished; final acceptance is recorded in `ITERATION-34-ARTIFACT-INTEGRITY-LIBRARY-HEALTH-CHECKPOINT.md`.
