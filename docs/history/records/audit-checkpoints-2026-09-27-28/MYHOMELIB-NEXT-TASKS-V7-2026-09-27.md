# MyHomeLib — завдання для продовження після V7 checkpoint

Дата фіксації: 2026-09-27

Базовий архів для наступної сесії:
`myhomelib-enterprise-optimized-v7-checkpoint-2026-09-27.zip`

## 1. Мета наступного циклу

Продовжити доведення MyHomeLib до рівня якісного enterprise-коду, але без overengineering.
Основні принципи:

1. correctness і збереження поведінкових контрактів — перший пріоритет;
2. прибирати реальне дублювання алгоритмів/контрактів, а не механічну схожість коду;
3. не створювати generic/framework abstractions, якщо вони погіршують читабельність;
4. кожен реальний дефект — спочатку відтворити regression-тестом, потім мінімально виправити;
5. кожен extraction/refactoring — targeted tests + module/reactor compile + completeness-check;
6. performance/memory зміни приймати лише після A/B вимірювання;
7. не знижувати `-Xmx` і не міняти GC за замовчуванням без повного benchmark великого INPX і packaged UI;
8. не повертати видалені дубльовані або eager-механізми.

## 2. Поточний перевірений стан checkpoint

### Build / static quality

На момент архівації:

- повний Maven reactor (20 modules):
  `./mvnw -o -Dmaven.repo.local=<offline-repo> -DskipTests package` — **BUILD SUCCESS**;
- `tools/implementation-completeness-check.py` — **PASS**:
  - production Java files: **1195**;
  - TODO/FIXME/unsupported: 0;
  - empty public/protected methods: 0;
  - unused explicit imports: 0;
  - unused Spring constructor dependencies: 0;
  - unimplemented output ports: 0;
  - exact cross-file method clones >=180 chars: **0 warnings**;
- `tools/static_release_check.py` — **PASS**:
  - XML/POM/FXML: 49, errors: 0;
  - FXML workspaces: 29;
  - handler references: 261, missing: 0;
  - SQLite migrations: 63, integrity: OK;
  - Java sources: 1909;
  - test sources: 528;
- `tools/xml-archive-security-check.py` — **PASS**;
- `tools/supply-chain-policy-check.py` — **PASS**.

### Test evidence accumulated in this cycle

Важливі вже завершені перевірки:

- full `myhomelib-infrastructure` після Jackson 3 / SQLite / HTTP змін:
  **611 tests / 164 test classes, 0 failures, 0 errors, 9 skipped**;
- JSON / backup / metadata / sync / catalog / MCP targeted set після Jackson API cleanup:
  **69 infrastructure tests + 10 organizer/bootstrap/MCP tests — PASS**;
- HTTP shared-client / resume / provider targeted tests — PASS;
- `BookIdListSupport` та 4 consumers — **21/21 PASS**;
- shared books UPSERT для INPX + neutral importer — **13/13 PASS**;
- cache-aside extraction — **8/8 PASS**;
- `LibraryOperationCoordinator` lifecycle helper + consumers — **21/21 PASS**;
- shared path safety (application/EPUB/converter/workspace) — **21/21 PASS**;
- Reader PDF/Comic paged lifecycle extraction — **10/10 PASS**;
- `ReaderTextProviderCoordinator` static + behavioral contracts — **6/6 PASS**.

### Незакрита перевірка UI

Після extraction із `NewReaderWorkspaceController` широкий `myhomelib-ui` прогін дав:

- **177 tests passed**;
- **1 environment error** у `DialogLocalizationFxTest` через відсутній `DISPLAY`.

Це **не вважати PASS** і не трактувати як regression.
Перший крок наступної сесії: повторити повний UI-suite під `Xvfb`/virtual display.

## 3. Основні архітектурні та quality-зміни, які вже зроблені

Не повертати старі дубльовані реалізації.

### Startup / memory / dependency graph

- Spring lazy initialization використовується для зменшення стартового object graph;
- component scan звужено до реальних Spring package boundaries;
- додано boundary-test, щоб новий `@Component/@Service` поза scan не загубився;
- зайві runtime залежності Actuator/Logstash прибрані;
- Jackson runtime уніфіковано на Jackson 3 (старий Jackson 2 databind/core більше не є паралельним runtime stack);
- Jackson 3 deprecated API у production-коді прибрано;
- HTTP clients консолідовані; на старті замість кількох selector manager threads лишається один shared client;
- backend/UI executor core threads дозволено завершувати після idle timeout;
- OPDS startup ownership уніфіковано; прибрано дубльований lifecycle/autostart;
- optional/server/importer компоненти зроблені lazy там, де це безпечно;
- packaged runtime містить `jdk.management`, Lucene більше не працює в degraded HotSpot-detection mode;
- EPUBCheck залишається у package як external tool, але його JAR-и не входять до main application classpath;
- production runtime profile явно активується packaged launcher-ом.

### Останній підтверджений memory baseline

На Linux packaged UI після full GC (до частини найновіших source-refactorings):

- RSS ≈ **393.8 MB**;
- PSS ≈ **372.6 MB**;
- live Java heap ≈ **62.4 MB**.

Висновок: основна пам'ять — не live heap; значна частина припадає на JVM/metaspace/JavaFX/native structures.
Не оптимізувати це простим зменшенням `-Xmx` без великих-data benchmarks.

Окремий Serial GC A/B давав значно нижчий RSS/PSS, але тест був забруднений різними startup conditions / Lucene lock / `user.home`, тому **не використовувати його як доказ** і не робити Serial GC default без нового fair benchmark.

### SQLite

- виправлено реальну проблему: кілька PRAGMA в одному `connectionInitSql` фактично не застосовувалися повністю;
- PRAGMA перенесені на коректні SQLite driver properties / per-connection setup;
- regression перевіряє `foreign_keys`, `busy_timeout`, `synchronous`, `temp_store`, `cache_size`, `mmap_size`;
- мертвий/ризиковий `defaultDataSource` bean прибрано.

### Усунення дублювання / shared boundaries

Серед уже винесених спільних boundary/support:

- `NoLinkPathSafety`;
- `SafeFilePublication` integration;
- `RequestDeadline`;
- `BookIdListSupport`;
- `BookBatchEditSupport`;
- `BookArtifactFileIdentity`;
- `EntityCache` + `CacheAsideRepositorySupport`;
- `CollectionScopedCaffeineCache`;
- `DownloadSpaceSupport`;
- `OnlineHttpClientProvider`;
- `TextProviderHttpSupport`;
- `TextProviderExecutionSupport` / `TextProviderRegistrySupport`;
- `BookRelationBatchLoader`;
- `BookUpsertSql`;
- archive import support;
- EPUB ZIP/graph rewrite support;
- converter external-engine support;
- organizer archive/file integrity support;
- shared ARGB render model/support;
- `PagedRenderCoordinator`, `TrackedTaskGroup`, shared paged reader position;
- `PositionAutosaveCoordinator`;
- `ReaderBookmarkCoordinator`;
- `ReaderTextProviderCoordinator`;
- `ReaderTtsCoordinator`;
- shared `OperationStageText`;
- `LibraryOperationCoordinator` synchronous lifecycle helper.

### Великий UI controller

`NewReaderWorkspaceController` поступово декомпозується по responsibility boundaries.
Вже винесено щонайменше:

- position autosave;
- bookmarks orchestration;
- dictionary/translation/text-provider orchestration;
- TTS orchestration;
- paged PDF/comic rendering lifecycle частково централізовано у reader layer.

Правило: не переписувати controller цілком. Виносити по одному цілісному responsibility з behavioral tests.

## 4. Пріоритет P0 — закрити checkpoint verification

1. Запустити повний `myhomelib-ui` test-suite під `Xvfb` і підтвердити 0 failures/errors.
2. Після цього повторити всі targeted tests модулів, які були змінені після останнього full infrastructure-suite:
   - `myhomelib-application`;
   - `myhomelib-infrastructure` (мінімум cache/HTTP/import/persistence/text-provider/JSON);
   - `myhomelib-reader`;
   - `myhomelib-ui`;
   - `myhomelib-bootstrap`.
3. Запустити один consolidated `./mvnw -o clean verify`, якщо execution environment дозволяє достатній час.
   Якщо tool timeout — не позначати PASS; використовувати контрольовані групи test classes.
4. Повторити Linux `jpackage` app-image smoke після останніх змін.
5. Перевірити stage23 release checker на реально зібраному app-image.
6. Windows x64 packaging/runtime smoke досі бажаний у реальному Windows environment.

## 5. Пріоритет P1 — memory/startup optimization з доказами

### A. Зняти новий чесний baseline після всіх V7 source-refactorings

Один isolated `user.home`, однакова library/index state, однаковий сигнал UI-ready та однаковий idle interval.
Зафіксувати:

- RSS;
- PSS / private dirty / private clean;
- live/committed heap після full GC;
- metaspace;
- loaded classes;
- Java/native thread count;
- NMT categories (`Java Heap`, `Class`, `Thread`, `Code`, `GC`, `Compiler`, `Other`).

### B. Fair G1 vs Serial GC A/B

Повторити тільки після стабільного baseline.
Обидва запуски повинні мати:

- окремі, але однаково підготовлені `user.home`;
- однаковий Lucene/SQLite state;
- однакові JVM options, крім GC;
- однаковий UI-ready/idle момент виміру.

Потім перевірити не лише startup memory, а й:

- повний `flibusta_online_fb2.inpx` (707154 rows);
- Lucene index build/search;
- reader large-book search;
- responsiveness/UI pauses.

Serial GC можна зробити default лише якщо він суттєво зменшує memory footprint і не дає неприйнятних pause/throughput regressions.

### C. Не зменшувати `-Xmx1024m` без доказів

Потрібен завершений великий-data benchmark для 512/768/1024 MB із:

- wall-clock;
- peak heap/RSS;
- GC pauses/collections;
- imported/skipped/errors;
- DB/index reconciliation.

## 6. Пріоритет P2 — продовжити структурну дедуплікацію

Не орієнтуватися лише на literal clone checker — він уже показує 0 exact clones >=180 chars.
Потрібен normalized token/AST-style structural review.

Брати тільки дублікати, де є спільний складний контракт або ризик розходження.
Не виносити:

- прості constructors/DI boilerplate;
- дворядкові mappings;
- різні domain orchestration у generic framework;
- код, де абстракція вимагатиме багато type parameters/callbacks і погіршить локальну читабельність.

Кандидати для наступного аудиту:

1. залишкові великі UI/application controller/service orchestration blocks;
2. import/persistence batch lifecycle;
3. filesystem transaction/rollback patterns;
4. async cancellation/current-context guards;
5. JSON snapshot/manifest traversal/restore validation;
6. repeated Lucene/SQLite resource lifecycle patterns.

Після кожного extraction:

- unit/contract test нового support;
- existing consumer tests;
- reactor compile із `-am` для cross-module changes;
- completeness-check.

## 7. Пріоритет P3 — подальша декомпозиція великих класів

Продовжувати по одному responsibility boundary.

Основні кандидати:

1. `NewReaderWorkspaceController` — наступні великі залишкові UI responsibilities після вже винесених bookmarks/text-provider/TTS/autosave;
2. `DesktopIntegrationBackend` — sync/export/plugin/settings orchestration, але не створювати god-service support;
3. `SearchWorkspaceController` — query state/result mapping/presentation;
4. `VersionedUserDataTransferAdapter` — manifest/traversal/restore validation/transaction rollback;
5. `InpxImportPipeline` — parsing/batching/persistence/progress/index sync;
6. `ApplyLibraryOrganizationUseCase` — filesystem plan/apply/rollback boundaries.

Не переписувати жоден з цих класів цілком.

## 8. Пріоритет P4 — correctness / hidden defects

Продовжити пошук дефектів у:

- Unicode offsets/canonical equivalence/surrogate pairs/combining chars;
- parser overflow (`offset + length`, `start + count`, `position + size`);
- malformed/truncated ZIP/EPUB/FB2/MOBI/INPX;
- HTTP resume/redirect/validator changes/truncated body;
- temp-file publication + atomic move + rollback;
- cancellation/shutdown/executor ownership;
- SQLite busy/retry transaction boundaries при import/edit/index update concurrency;
- plugin JAR validation/security boundaries.

Для кожного знайденого дефекту: regression test → minimal fix → downstream tests.

## 9. Release / packaging continuation

Перевірити, що після кожної packaging зміни зберігаються такі інваріанти:

- `jdk.management` присутній у custom runtime;
- Lucene не показує warning про відсутність HotSpot diagnostic API;
- EPUBCheck доступний як packaged external tool, але не входить до main classpath;
- production Spring profile реально активний;
- optional `mobitool/libmobi` не блокує звичайну desktop build;
- strict/full release fail-closed без повного ліцензованого libmobi runtime/source bundle;
- Windows/Linux scripts поводяться симетрично.

## 10. Критерії наступного checkpoint

Не формувати нову baseline-версію, доки:

- кожен новий defect має regression test;
- повний UI-suite під virtual display — 0 failures/errors;
- змінені module suites — 0 failures/errors;
- completeness/static/XML/archive/supply-chain checks — PASS;
- production reactor package — BUILD SUCCESS;
- якщо можливо — consolidated clean verify — PASS;
- Linux packaged app smoke — PASS;
- clean ZIP не містить `target/`, `dist/`, `.mvn/repository/`, temp/log/cache files;
- ZIP проходить CRC test;
- SHA-256 зафіксований;
- новий memory baseline і зміни від цього V7 checkpoint задокументовані.

## 11. Готовий текст для старту наступної сесії

> Продовж глибокий технічний аудит і оптимізацію MyHomeLib, використовуючи `myhomelib-enterprise-optimized-v7-checkpoint-2026-09-27.zip` як єдину baseline-версію. Спочатку прочитай `MYHOMELIB-NEXT-TASKS-V7-2026-09-27.md`. Код має бути рівня enterprise-продукту: максимальна коректність, мінімальне реальне дублювання, оптимальні алгоритми й ресурси, але без overengineering. Не переписуй стабільний код без вимірюваної або тестом підтвердженої причини. Для кожного дефекту: regression test → мінімальне виправлення → targeted/downstream tests. Для refactoring: один responsibility boundary за раз. Для memory/performance: тільки A/B вимірювання. Першим кроком закрий повний UI-suite під Xvfb після останнього `ReaderTextProviderCoordinator` extraction, потім повтори acceptance/checks і зніми новий packaged memory baseline. Після цього продовж structural clone audit та поетапну декомпозицію великих класів. Наприкінці сформуй новий clean source ZIP, звіт про зміни та оновлений список залишкових задач.
