# MyHomeLib Enterprise v7.1 — завдання для продовження повного аудиту

Дата фіксації стану: 2026-08-28
Статус: **WIP / аудит не завершений**. Продовжувати саме з цього snapshot, а не з попереднього release-v7.1 ZIP.

## 1. Головна мета наступного проходу

Довести v7.1 до фінального стану після повної ревізії всього коду, UI та продуктивності. Не обмежуватися upstream parity: знайти і виправити власний legacy/dead/unreachable code, функції без UI-доступу, UI-кнопки без реальної функції, дубльовані реалізації, зайві allocation/SQL/N+1/memory-risk, некоректні lifecycle/cache/invalidation paths, reader UX gaps і release inconsistencies.

Пріоритет незмінний: цілісність каталогів і user data > correctness > performance > cleanup/refactoring.

## 2. P0 — завершити перед будь-яким новим release candidate

### 2.1. Bounded change tracking для імпорту 1M+

Поточні ризикові місця:
- `myhomelib-infrastructure/.../InpxImportPipeline.java`
  - `insertedIds`, `updatedIds`, `deletedIds` — `LinkedHashSet`, що можуть вирости до сотень тисяч/1M ID.
- `myhomelib-infrastructure/.../JdbcCatalogImportAdapter.java`
  - аналогічний change tracking для не-full snapshot.
- `myhomelib-application/.../ImportFileUseCase.java`
- `myhomelib-application/.../UpdateCollectionFromNetworkUseCase.java`
  - формують об'єднаний `LinkedHashSet` змінених ID для selective Lucene.

Потрібно:
1. Створити bounded accumulator/model, наприклад `SearchChangeAccumulator` / `ImportChangeAccumulator`.
2. До configurable threshold зберігати точні stable book IDs для selective Lucene.
3. Після threshold переходити в `fullReindexRequired=true`, очищати/не нарощувати множини.
4. Не втрачати counters inserted/updated/deleted — counters мають бути `long` і незалежними від size множин.
5. Для full snapshot використовувати searchable fingerprint diff, де це можливо; не примушувати full rebuild 1M лише через bounded tracking.
6. Додати regression tests: 10k selective, threshold crossing, 1M synthetic bounded heap, deleted IDs, cancellation.

### 2.2. INPX 1M import — завершити performance hardening

Уже зроблено:
- line count без створення 1M `String`;
- bounded SQL `IN (...)` chunks ~400;
- прибрані зайві CSV round-trips для author/genre links;
- частина fast-path оптимізацій.

Ще перевірити/доробити:
1. `app.import.batch-size` зараз має legacy default 500 у `ImportFileUseCase` та `ImportDirectoryUseCase` — підібрати benchmark-driven default.
2. Не встановлювати довільно 10k без heap/SQLite tests.
3. `JdbcBatchWriter` / `InpxImportPipeline`: перевірити allocations на кожен record, repeated normalization, author/genre map reuse, prepared statements.
4. Перевірити `EXPLAIN QUERY PLAN` для lookup/merge/delete під час import.
5. Перевірити, що жоден batch не перевищує SQLite bind-variable limit.
6. Запустити 100k/500k/700k/1M після всіх нових змін і порівняти з `docs/release/PERFORMANCE-v7.1-SQLITE-RAW.json`.
7. Збирати peak heap/RSS, GC якщо Java benchmark стане доступним.

### 2.3. PRAGMA lifecycle / durability

Файл: `myhomelib-infrastructure/.../SqliteBulkImportOptimizer.java` та пов'язані import paths.

Уже виправлено небезпечний `synchronous=OFF` -> `NORMAL` для bulk mode.

Потрібно:
1. Перед bulk import зчитувати реальні попередні PRAGMA значення.
2. Гарантовано відновлювати `synchronous`, `temp_store`, `cache_size`, `mmap_size` і все, що змінює optimizer, у `finally`.
3. Не hard-code restore до значень, яких користувач/DB не мав.
4. Test: success, exception, cancellation — settings restored.

### 2.4. Повна UI reachability matrix

Створити `UI-FUNCTION-REACHABILITY-v7.1.md`.

Для кожного user-facing use case/controller/service/action встановити:
- UI button/menu/context-menu/hotkey/reader gesture/MCP/OPDS entry point;
- або `INTENTIONALLY HEADLESS`;
- або `UNREACHABLE` -> видалити чи додати UI.

Особливо перевірити:
- `myhomelib-ui/src/main/java/.../controller`
- `.../service`
- `.../navigation`
- FXML `onAction` handlers;
- context menus;
- toolbar buttons;
- keyboard shortcuts;
- reader actions;
- export/import/maintenance/duplicates/integrity/online functions.

Заборонено залишати:
- handler без FXML/control;
- control без handler/meaningful action;
- button, який лише змінює label, але не behavior;
- hidden control, що продовжує отримувати updates;
- use case, який не має жодного intended entry point.

### 2.5. Reader — завершити AlReaderX gap audit

Уже додано/виправлено:
- real one/two-page spread;
- auto two-page wide/landscape;
- 9 short tap zones;
- 9 long tap zones;
- configurable swipe actions;
- pinch zoom;
- optional clock/status integration;
- day preset;
- runtime settings -> persistent preferences callback;
- видалено невидимі toolbar progress/chapter/page controls;
- видалено dead `ReaderException`;
- `SimpleResourceRepository` перенесено з production у tests.

Наступного разу **повторно перевірити актуальні публічні можливості AlReaderX через веб**, не покладатися лише на цей файл.

Перевірити доцільність desktop-реалізації:
1. Day/night як окремі persistent profiles, а не лише preset.
2. Orientation policy для desktop/window width (без Android-specific fake rotation lock).
3. Footnotes: inline / popup / page-bottom, якщо parser/document model дозволяє.
4. Окремі стилі heading/quote/annotation/epigraph/poem/code/link.
5. Hanging punctuation / typography, якщо JavaFX layout дозволяє без деградації.
6. Тонші color controls для background/text/link/selection; brightness/gamma лише якщо desktop-safe — не додавати фальшиве OS brightness API.
7. Search/navigation by page/%/chapter; verify no duplicate controls.
8. Reader position sync/export foundation — only if architecture clean.
9. Gesture conflict tests: selection vs swipe/tap/long-press/pinch.
10. Keyboard accessibility and focus traversal.

### 2.6. Reader regression/performance

Перевірити:
- FB2 large document;
- one-page/two-page transitions preserving position;
- odd final spread;
- resize wide->narrow->wide;
- font size/pinch repagination;
- selection on left/right page;
- search result navigation;
- bookmarks/position autosave;
- settings restart persistence;
- autoscroll + manual navigation;
- no JavaFX thread blocking on parse/search/large repagination where avoidable.

## 3. P1 — codebase cleanup / optimization

### 3.1. Dead/legacy code scan

Уже видалено/перенесено:
- `CatalogReaderRegistry` — dead;
- `ReaderException` — dead;
- `SimpleResourceRepository` — test-only, перенесено в test sources;
- dead `SqliteBulkImportOptimizer.dropIndexes/createIndexes` — перевірити остаточний diff/видалення;
- застарілі navigation wrappers — частково очищені.

Продовжити symbol usage scan з урахуванням Spring/FXML/reflection:
- НЕ видаляти клас лише через відсутність прямого `new`;
- для `@Component`, `@Service`, `@Configuration`, `@Bean`, `@FXML`, `ServiceLoader`, reflection перевіряти wiring окремо.

Знайти:
- private methods без call sites;
- obsolete adapters/DTOs;
- duplicated parser/normalizer/helper logic;
- unused config properties;
- stale migrations helpers;
- deprecated resources/help/scripts;
- duplicate release/version constants;
- dead CSS/FXML ids;
- test fixture code у production.

### 3.2. Index management during full import

Файл: `InpxImportPipeline.dropIndexes()/createIndexes()`.

Потрібно:
1. Порівняти список dropped/recreated indexes з усіма актуальними V1–V40/V41 migrations.
2. Не відновлювати «ручний старий піднабір» і не губити нові indexes.
3. Краще перейти на metadata-driven/centralized index definitions або перестати drop indexes, якщо benchmark не показує суттєвої користі.
4. Benchmark import with/without index drop для 100k/500k/1M.
5. Crash/cancel test — indexes гарантовано повертаються.

### 3.3. Lucene

Уже зроблено:
- keyset traversal;
- bounded batches;
- no N+1 author/genre enrichment;
- separate writer factory/executor/metrics;
- searchable metadata fingerprints;
- rollback/atomic behavior;
- performance telemetry foundation.

Ще:
1. Реальний Maven/JVM benchmark v7 vs v7.1: 100k/500k/700k/1M.
2. RAM buffer, merge policy/scheduler, stored fields/doc values audit.
3. Annotation size impact.
4. Selective reindex 10k of 700k — actual docs/sec/time.
5. Peak heap, GC, segment count, index size.
6. Verify index refresh/reopen cost and no needless commits.

### 3.4. Duplicate search

Уже:
- physical identity для integrity duplicate scan;
- bounded `DuplicateDetector` cache;
- structured key, no delimiter identity.

Ще:
1. `EXPLAIN QUERY PLAN` duplicate queries.
2. Оцінити composite index `(lib_id, collection_root, folder, file_name, archive_entry)` — додавати лише якщо benefit > import penalty.
3. Окремо розрізняти physical duplicate vs probable metadata duplicate vs content fingerprint duplicate.
4. Не зливати stable BookId/user state автоматично.

### 3.5. Online download

Уже:
- shared `HttpClient` reuse;
- configurable 32–1024 KiB buffer, default 256 KiB;
- throttled progress;
- Range + If-Range + validator sidecar;
- persistent queue;
- archive dedup;
- semantic validation before atomic move;
- secure ConnectionScript interpreter;
- high-reliability ZIP validation.

Ще:
1. Measure throughput on local embedded HTTP large payloads (10MB/100MB/1GB synthetic if feasible).
2. Retry/backoff jitter and retryability classification.
3. Connection pool behavior under concurrent archive requests.
4. Per-host/global concurrency limits.
5. Cancellation latency.
6. Proxy + custom truststore tests.
7. Ensure progress throttling does not delay cancellation.

## 4. P1 — upstream/other-project useful mechanisms

Повторно звірити:
- `MyHomeLib-2.7.0_pre2` — behavioral parity;
- `metabib-main` — structural/provenance/bounded workers/fingerprint/manifest ideas;
- AlReaderX — reader UX/settings only, не копіювати Android-specific або ліцензійно несумісний код.

Правило: переносити корисну **поведінку/архітектурну ідею**, не копіювати GPLv3 metabib code буквально.

Оновлювати `UPSTREAM-PARITY-MATRIX-v7.1.md` після кожного реально закритого gap.

## 5. P2 — після P0/P1

- archive rollup foundation;
- compilation/content dedup enhancements;
- advanced artifact occurrence normalization;
- optional reader position sync;
- deeper typography refinements.

P2 не повинен блокувати реліз, якщо foundation clean і limitations documented.

## 6. Обов'язкові перевірки після кожного великого рефакторингу

Запускати всі доступні offline guards, а не лише один:

```bash
python3 tools/build-check-v7.py
python3 tools/v71-standalone-java-smoke.py
# потім усі tools/*check.py, які є executable/current
```

Також:
- XML/FXML parse;
- FXML handler reachability;
- Flyway V1–V36 immutable SHA-256;
- V37+ upgrade;
- existing v7 DB -> v7.1 user-data preservation;
- shell/YAML syntax;
- no `.git`, `target`, IDE cache, secrets у release tree.

Якщо Maven/network доступні:

```bash
./mvnw clean verify -Pproduction
```

Не називати Maven/CI PASS без фактичного green run.

## 7. Фінальна процедура після завершення нового аудиту

1. Оновити:
   - `UPSTREAM-PARITY-MATRIX-v7.1.md`
   - `ARCHITECTURE-UPGRADE-v7.1.md`
   - `PERFORMANCE-v7.1.md`
   - `GITHUB-CI-v7.1.md`
   - `UPGRADE-FROM-v7.md`
   - release notes / validation report.
2. Перегенерувати ZIP і patch **після останньої зміни**.
3. Розпакувати ZIP у новий temp directory.
4. Запустити gates саме з extracted tree.
5. Застосувати patch до чистого baseline v7.
6. Порівняти patch-tree з ZIP по SHA-256 + mode bits.
7. Згенерувати SHA-256 ZIP.
8. Лише після цього позначати source release готовим.
9. Formal acceptance — тільки після реального Maven + GitHub Ubuntu/Windows/macOS green.

## 8. Важливі заборони

- Не втрачати stable BookId, favorites, read state, rating, notes, history, tags, downloads, credentials.
- Не змінювати V1–V36.
- Не використовувати `trust-all` TLS.
- Не зберігати plaintext passwords/tokens.
- Не повертати delimiter-based author identity.
- Не створювати `List<1_000_000>` або 1M futures/IDs без необхідності.
- Не робити `OFFSET` scan для великих послідовних проходів, якщо можливий keyset.
- Не додавати SQLite index «наосліп» без EXPLAIN/benchmark.
- Не блокувати JavaFX thread важкими DB/import/Lucene/statistics operations.
- Не залишати псевдокнопки або UI, який не має реальної поведінки.
- Не видаляти Spring/FXML/reflection classes лише за простим textual reference count.

## 9. З чого почати наступного разу

Перші дії без додаткових уточнень:
1. Відкрити цей файл.
2. `git status --short` і не скидати WIP changes.
3. Завершити bounded change accumulator для 1M import (P0).
4. Виправити PRAGMA restore lifecycle (P0).
5. Запустити всі offline checks.
6. Продовжити UI reachability + dead-code scan.
7. Повторно звірити reader з актуальним AlReaderX і завершити reader tests/settings gaps.
8. Запустити 1M benchmark після оптимізацій.
9. Лише потім оновлювати release artifacts.


## 10. Актуальний checkpoint 2026-08-30 — використовувати замість старого стартового списку §9

Після snapshot 2026-08-28 виконано P0/P1 пакети bounded import tracking, PRAGMA restore, import/index lifecycle, Lucene consistency/deep paging, user-state selective reindex, collection/Lucene switch lifecycle, Unicode author normalization, online resume/retry/concurrency hardening, UI reachability та reader parity audit.

Окремий completeness/duplication pass зафіксований у `CODE-COMPLETENESS-AUDIT-v7.1.md`:

- `tools/implementation-completeness-check.py` — PASS;
- 0 TODO/FIXME/not-implemented markers;
- 0 empty public/protected methods;
- 0 sentinel interface defaults;
- 0 unused Spring constructor dependencies;
- 0 application use cases без production consumer;
- 0 application output ports без implementation;
- 0 exact cross-file method clones >=180 chars;
- пакетний progress UI та реальний book year UI дороблені;
- dynamic collection `DataSource` contract повністю делегований;
- dead/misleading compatibility APIs і duplicate helpers прибрані.

Після цього пакета всі доступні `tools/*check.py`: **36/36 PASS**, standalone Java smoke: **PASS**.

Наступні пріоритетні дії без повторення вже закритого:

1. Connected Maven/JVM verify, коли Maven distribution/dependencies доступні; не називати green до фактичного run.
2. JVM/Lucene 100k/500k/700k/1M benchmark + heap/GC/index-size evidence.
3. Online download local HTTP throughput/cancellation/proxy/custom truststore integration.
4. Завершити runtime reader regression matrix (large FB2, spread/resize/selection/search/autoscroll/persistence).
5. Лише після останньої зміни оновити release docs, перепакувати ZIP/patch і перевірити extracted-tree + SHA-256/mode parity.


## 11. Checkpoint 2026-08-30 після runtime/dead-API pass

Останній завершений пакет:

- redundant navigation-history facade видалено;
- reader compatibility aliases без caller видалено;
- legacy export post-command UI прибрано, migration compatibility збережена;
- dead public UI/service API без caller видалено;
- bookmark feature завершено end-to-end (add/list/go/delete);
- bookmark exact position виправлено через `rp:<ReaderPosition.serialize()>`, legacy percentage fallback збережено;
- repository contracts звужено від мертвих методів;
- architecture ratchet: UI output-port users **17/18**, non-value domain-model users **26/28**;
- reachability: **165 FXML handlers**, **50 application use cases** з direct UI/MCP/OPDS entry;
- повний offline контроль після пакета: **36/36 PASS**; standalone Java smoke: **PASS**.

Не повторювати ці cleanup-пункти. Наступний змістовний блок — тільки acceptance/runtime evidence:

1. Maven/JVM verify, коли Maven/dependencies реально доступні.
2. JVM/Lucene large-library benchmark + heap/GC/index size.
3. Local HTTP 10MB/100MB/1GB throughput + cancellation/proxy/custom truststore integration.
4. JavaFX reader runtime regression matrix.
5. Release docs + ZIP/patch тільки після останньої зміни.

## 12. Актуальний WIP checkpoint 2026-08-30 після large-collection / Reader deep refactor

Детальний актуальний список продовження винесено в `NEXT-AUDIT-v7.1.md`. Наступного разу **починати з нього**, а не зі старих §9–11.

Короткий статус перед WIP-пакуванням:
- per-collection Lucene + freshness/WAL-aware lifecycle реалізовано;
- Group/Author workspaces переведені на server pagination;
- large-reader async/bounded hot paths реалізовані;
- cache/full-dictionary heap risks прибрані або переведені на weight budgets;
- copy-between-collections переносить physical book + collection-local user state в одній DB transaction;
- directory import переведений на bounded selective Lucene; останній пакет cancellation/progress/one-pass walk ще треба завершити/зафіксувати regression tests;
- короткі поточні gates: build/completeness/INPX-user-data/Reader/Stage25C PASS;
- останній повний 40/40 + standalone smoke був до найновішого directory-import пакета.

Не називати цей checkpoint фінальним release candidate.

## 13. Checkpoint 2026-08-30 після directory/copy/Lucene/MCP scale pass

Актуальні зміни після §12 зафіксовані в `NEXT-AUDIT-v7.1.md` §6. Поточний offline стан: **43/43 tools checks PASS + standalone Java smoke PASS**.

Нові закриті P0/P1:
- directory-import cancellation/progress/Lucene finalization;
- copy-between-collections post-commit physical-file safety;
- Lucene failed-sync DIRTY sealing;
- MCP `search_books` page-first enrichment/EXISTS + indexed title ordering.

Maven/CI/formal runtime acceptance не оголошувати green: connected dependency/runtime evidence ще відсутній. Наступний прохід не повинен повторювати ці cleanup/SQL зміни без нової регресії.

## 14. Functional-regression checkpoint 2026-08-30

Окремо перевірено, чи compile/online/refactor packages не видалили раніше наявні user-facing functions.

Результат:
- останні WIP/compile/online merge не втратили Java/FXML feature surface;
- реальна старіша regression знайдена в Author Workspace series grouping і виправлена bounded/server-paged реалізацією;
- missing-online-book open flow тепер централізовано gated у `WorkspaceManager`: confirmation -> download/persist -> Reader;
- додано feature-preservation baseline + `tools/functional-regression-check.py`, який ловить навіть одночасне видалення FXML control + handler;
- повний актуальний offline run: **46/46 PASS**; standalone Java smoke: **PASS**.

Деталі: `FUNCTIONAL-REGRESSION-AUDIT-v7.1.md`.

Не оголошувати runtime Reader observations `merged words` / specific nested compilation TOC fixed без reproducer. Maven/CI formal green як і раніше потребує user/connected environment.
