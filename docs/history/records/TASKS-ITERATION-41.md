# MyHomeLib — Iteration 41 — backlog closure + infrastructure debt

**Дата:** 11.09.2026  
**База:** Iteration 40 DONE  
**Правило:** спочатку всі source/docs/test changes, потім ручна ревізія та freeze, і лише після цього один acceptance/regression цикл.

## 1. MHL-112 — formal shared undo closure

Закрити історичний статус `В роботі` на актуальному reactor без зміни функціонального scope:
- restart-safe `latestUndoable()`;
- reverse-order guard;
- bulk undo current-state guard;
- BOOK_MERGE undo + search synchronization;
- retention 1..200 без втрати newest undoable;
- application dispatch regression для BULK_METADATA / BOOK_MERGE.

## 2. Annotation SQLite transaction debt

- прибрати exact cross-file clone `inTransaction` з annotation repository/query/export adapters;
- один infrastructure helper для transaction against current collection;
- fail closed, якщо current collection/data source відсутній;
- commit/rollback acceptance;
- без зміни application/UI/domain boundaries.

## 3. Reproducible offline acceptance entrypoint

- `tools/offline_acceptance.py` працює з зовнішнім Maven 3.9.6+;
- обов'язковий `--maven-repo` вказує на зовнішній offline dependency repository;
- не використовує `mvn install` і не залежить від `maven-install-plugin`;
- спочатку reactor `test-compile`, потім targeted MHL-112 + annotation transaction acceptance;
- implementation-completeness gate;
- `--full` опційно запускає повний reactor `test`;
- source ZIP як і раніше не містить Maven, wrapper або dependency JAR.

## 4. Final cycle

Після freeze:
1. Python/static syntax + architecture/localization/release gates;
2. offline reactor `test-compile`;
3. targeted MHL-112 acceptance;
4. targeted annotation transaction/integration acceptance;
5. implementation completeness — clone debt має бути 0;
6. Application + Infrastructure regressions модульно;
7. Reader/UI/tail smoke, щоб infrastructure refactor не дав transitive regression;
8. release-tree cleanliness;
9. source ZIP integrity без Maven/dependency binaries;
10. SHA-256.

Зовнішні MHL-010/011/012/017/018/019 не входять у scope: для них потрібні реальні GitHub/Windows evidence gates.


## Результат

**DONE для локально перевірюваного scope.** MHL-112 закрито на актуальному reactor; annotation transaction clone-debt усунуто; offline acceptance entrypoint перевірено без `mvn install`. Targeted acceptance — **18/18 PASS**, ArchUnit — **12/12 PASS**, implementation completeness — **0 exact clones**. Формальний source ZIP пакується без Maven/wrapper/dependency binaries.
