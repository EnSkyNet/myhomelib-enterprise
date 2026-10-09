# MyHomeLib — V34 External Test R14 validation

**Дата:** 2026-10-04  
**База:** V34 External Test R13  
**Мета:** провести release-аудит обіцяних R12/R13 виправлень і закрити підтверджений regression, де full-text «Оновлення пошукового індексу» блокувало завантаження книги з online library.

## 1. Підтверджений defect: full-text INDEX блокував DOWNLOAD

R13 уже мав правильний shared INDEX/DOWNLOAD контракт для catalogue Lucene rebuild. Але окремий full-text rebuild (`ContentIndexMaintenanceService`) залишався на exclusive `operationCoordinator.acquire(INDEX)`.

У UI цей процес теж відображається як «Оновлення пошукового індексу», тому реальний сценарій користувача був таким:

- full-text rebuild отримує exclusive INDEX lease;
- online DOWNLOAD не може приєднатися до collection operation barrier;
- навіть після дозволу DOWNLOAD синхронний `contentIndexScheduling.schedule(...)` міг чекати на Lucene full-text write lock;
- stale/fresh protection використовував `Set`, тому rebuild і concurrent committed download не мали незалежних stale holds.

### RED evidence

Додані regression-тести до fix відтворили три помилки:

1. `ContentIndexMaintenanceLifecycleTest` — DOWNLOAD під active full-text INDEX отримував `LibraryOperationConflictException`;
2. `DownloadBookUseCaseIndexConcurrencyTest` — post-download full-text scheduling викликався синхронно під INDEX;
3. `ContentIndexingQueueServiceTest` — один completion міг очистити STALE іншого паралельного flow.

RED run: **12 tests, 2 failures + 1 error**.

## 2. R14 fix

- Full-text rebuild використовує `acquireDetachedShared(INDEX)`, як і catalogue rebuild: SWITCH/DELETE/RESTORE лишаються заблокованими, але DOWNLOAD тієї самої активної колекції може приєднатися.
- `DownloadBookUseCase` під active INDEX після authoritative physical/storage commit відкладає **обидві** derived-index синхронізації (catalogue Lucene + full-text) у background executor; user download thread більше не чекає Lucene rebuild lock.
- Deferred repair тримає shared DOWNLOAD lease, щоб collection SWITCH не змішав repair з іншою active collection.
- `ContentIndexingQueueService.staleHolds` переведено із Set на reference-count map. Rebuild і concurrent committed download мають незалежні holds.
- `LuceneContentIndexService.rebuild()` більше не очищає STALE marker самостійно. Freshness публікується тільки application queue/orchestration після rebuild publication та завершення/постановки concurrent incremental repair.
- Додано infrastructure regression: successful Lucene rebuild не має очищати application-owned STALE marker до explicit `markFresh`.

GREEN evidence для нового дефекту:

- `ContentIndexingQueueServiceTest`: **9/9 PASS**;
- `ContentIndexMaintenanceLifecycleTest`: **2/2 PASS**;
- `DownloadBookUseCaseIndexConcurrencyTest`: **1/1 PASS**;
- `LuceneContentIndexServiceIntegrationTest`: **9/9 PASS**;
- `UiBackgroundExecutorCollectionScopeTest`: **10/10 PASS**;
- affected reactors: `BUILD SUCCESS`.

## 3. Аудит R13: знайдено ще одну незавершену зміну

R13 додав Flyway V68, але три ширші migration regressions досі очікували current schema **67**:

- `ConversionProjectionRepairV63Test`;
- `ReaderScopedPreferencesMigrationV65Test`;
- `DatabaseMigrationMatrixTest`.

Через це перший повний R14 root run коректно зупинився в infrastructure module: **688 tests / 3 failures / 0 errors / 13 skipped**. Це був test-baseline defect R13, а не failure самої V68 migration.

Expectations оновлено до **68**. Повторний targeted run: **3/3 PASS**.

## 4. Повторна перевірка всіх користувацьких R12/R13 виправлень

Одним regression matrix повторно перевірено:

- `BookMultiArtifactTest`: **6/6 PASS**;
- `ConverterSelectionPolicyTest`: **3/3 PASS**;
- `ContentIndexingQueueServiceTest`: **9/9 PASS**;
- `ContentIndexMaintenanceLifecycleTest`: **2/2 PASS**;
- `ConvertBookUseCaseTest`: **19/19 PASS**;
- `DownloadBookUseCaseIndexConcurrencyTest`: **1/1 PASS**;
- `ConverterToolLocatorTest`: **7/7 PASS**;
- `ConversionRoutePlannerTest`: **2/2 PASS**;
- `SqlitePreferredArtifactSelectionTest`: **4/4 PASS**;
- `ConversionProjectionRepairV68Test`: **2/2 PASS**;
- `LuceneContentIndexServiceIntegrationTest`: **9/9 PASS**;
- `EpubParserTest`: **8/8 PASS**;
- `BookInspectionServiceTest`: **3/3 PASS**.

Отже збережено:

- original artifact не стає secondary автоматично після conversion/SQLite round-trip;
- V68 repair лишається green;
- EPUB OPF metadata fallback лишається green;
- FBC route/locator contract лишається green;
- R12 lazy FB2 image loading лишається green;
- online download тепер не блокується full-text index rebuild на application/UI lifecycle boundary.

## 5. Exact-current full root regression

Після виправлення concurrency та migration-test baseline виконано повний root:

```text
xvfb-run -a <maven-3.9.16>/bin/mvn -B -o \
  -Dmaven.repo.local=<supplied-offline-repo> clean verify
```

Environment: JDK 21, Maven 3.9.16, `C.UTF-8`, Xvfb, supplied offline repository.

Результат:

- **2097 tests**;
- **0 failures**;
- **0 errors**;
- **23 skipped**;
- Surefire XML reports: **629**;
- test/product modules: **20/20**;
- reactor projects: **21/21 SUCCESS**;
- `BUILD SUCCESS`;
- elapsed: **02:38 min**.

Ключові модулі:

- Application: **511 / 0 / 0 / 1 skipped**;
- Infrastructure: **688 / 0 / 0 / 13 skipped**;
- Reader: **152 / 0 / 0 / 1 skipped**;
- UI: **373 / 0 / 0 / 0 skipped**;
- Bootstrap: **57 / 0 / 0 / 4 skipped**;
- E2E: **15/15**;
- Architecture JUnit: **20/20**.

## 6. Що локально підтверджено, а що ще потребує Windows evidence

Локально закрито code-level regression: DOWNLOAD може приєднатися до full-text INDEX, а derived index repair не блокує caller thread і не публікує false READY.

Ще потрібен Windows live scenario на exact R14 portable:

1. запустити full-text «Оновлення пошукового індексу» на великій бібліотеці;
2. під час rebuild завантажити remote-only книгу з online library;
3. download має фізично завершитися без очікування завершення rebuild;
4. після rebuild/deferred repair книга має знаходитися catalogue search і full-text search (де формат підтримує full-text);
5. Operation Center не має показувати blocking conflict для DOWNLOAD;
6. SWITCH/DELETE/RESTORE під active INDEX залишаються захищеними operation barrier.

Так само лишаються зовнішні R13 acceptance: real Windows V68 repair на користувацькій data copy, bundled FBC wall-clock/route, конкретний generated EPUB Book Details.

## 7. Scope discipline

R14 не відкриває V35 scope і не змінює memory tuning. Зміни обмежені index/download concurrency correctness, stale/fresh lifecycle та виправленням V68 regression-test baseline, знайденим повним release-аудитом.
