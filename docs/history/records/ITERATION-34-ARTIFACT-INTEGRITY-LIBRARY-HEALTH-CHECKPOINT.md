# Iteration 34 checkpoint — Artifact Integrity + Library Health

**Дата:** 09.09.2026  
**База:** Iteration 33

## Реалізовано

### MHL-116 — Artifact integrity/hash audit
- append-only Flyway migration `V56__artifact_integrity_audit.sql`;
- persistent audit baseline/cache у `artifact_integrity_state`;
- інкрементальна перевірка local+AVAILABLE artifacts;
- existence, physical size/mtime signature, content size, SHA-256 та archive readability;
- окремі стани `MISSING`, `UNREADABLE`, `CORRUPT_ARCHIVE`, `SIZE_CHANGED`, `HASH_CHANGED`;
- bounded issue findings і byte/read/reuse counters;
- archive drill-down path містить `!/entry`;
- audit не змінює `book_artifacts.sha256` / `size_bytes` і не виконує destructive auto-repair.

### MHL-117 — Library Health
- application service, DTO та issue taxonomy;
- KPI: missing/corrupt/changed, duplicates, metadata gaps, SQLite, Lucene freshness, backup age;
- configurable backup warning threshold `app.health.backup-stale-hours` (default 168 h, не SLA);
- refresh serialized через `LibraryOperationCoordinator.INTEGRITY_AUDIT`;
- JavaFX refresh через `UiBackgroundExecutor`;
- issue table, drill-down, recommended actions і text export;
- UI не звертається напряму до JDBC/Lucene/infrastructure.

### Performance hardening
- deterministic Lucene benchmark harness;
- default corpus 500,000 synthetic documents;
- cold/warm first page, AND/OR 5/20 rules, numeric range, DocValues sort, bounded maxResults;
- min/median/max latency measurement + heap/RSS/GC telemetry;
- CSV output; вимірювання не перетворюються на latency SLA.

## Acceptance

Source/doc/test зміни завершені. Тестовий етап виконується **лише після завершення всіх змін**, відповідно до робочого правила цієї ітерації.

Результати фінальних тестів будуть записані сюди після прогону.

## Final validation evidence

Фінальний цикл виконувався після завершення source/doc/test changes.

- offline reactor `test-compile`: 13/13 modules — `BUILD SUCCESS`;
- targeted MHL-116/MHL-117 acceptance: 5/5 — 0 failures, 0 errors;
- application regression: 177 tests — 0 failures, 0 errors, 1 skipped;
- infrastructure regression на актуальних Java sources: 261 tests — 0 failures, 0 errors, 4 skipped;
- UI regression: 54/54 — 0 failures, 0 errors;
- ArchUnit: 12/12 — 0 failures, 0 errors;
- Stage 34 / architecture / completeness / UI reachability / localization / executor / user-data/search / Lucene / static release gates — PASS;
- legacy `build-check-v7.py` оновлено так, щоб v7.1 baseline V1..V49 залишався immutable, а append-only migrations V50+ та iteration/task checkpoints не трактувалися як drift.

Монолітний infrastructure-reactor повторно запускався для підтвердження, але довгий хвіст metadata-provider тестів перевищив ліміт окремого execution; тому фінальна перевірка виконана модульно та через наявний повний green infrastructure report на тих самих Java sources.

## Lucene 500k benchmark

Deterministic corpus: **500,000 documents**, 7 warm runs per scenario (cold first-page — 1 run). Це measurement evidence, не latency SLA.

- cold first page: 63.259 ms;
- warm first page median: 8.100 ms;
- AND 5 rules median: 7.279 ms;
- OR 5 rules median: 6.923 ms;
- AND 20 rules median: 9.912 ms;
- OR 20 rules median: 7.614 ms;
- numeric range median: 3.343 ms;
- DocValues sort median: 7.663 ms;
- bounded maxResults=10,000 median: 12.008 ms;
- measured peak heap: 126,451,240 bytes;
- measured peak RSS: 319,213,568 bytes;
- GC collections/time during measured scenarios: 0 / 0 ms.

Raw CSV evidence: `ITERATION-34-LUCENE-BENCHMARK-500K.csv`.
