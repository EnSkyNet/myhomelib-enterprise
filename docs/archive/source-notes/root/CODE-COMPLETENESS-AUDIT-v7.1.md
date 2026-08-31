# MyHomeLib Enterprise v7.1 — code completeness / duplication audit

Дата проходу: **2026-08-30**  
Статус: **WIP checkpoint; не final release acceptance**

## Мета

Перевірити разом три класи ризику:

1. дубльований production-код, який може розійтися за поведінкою;
2. оголошені API/функції без реальної реалізації або з sentinel/no-op поведінкою;
3. dead/unreachable wiring — use case, port, Spring dependency або UI action, що оголошені, але реально не використовуються.

## Що виправлено

### Реально недореалізована поведінка

- `UpdateProgressBatchUseCase` був реалізований, але не мав UI entry point. Додано `handleBatchProgress`, валідацію `0..100`, FXML-кнопку та refresh path.
- Колонка `Рік` у `AuthorWorkspaceController` показувала `createdAt`; додано `BookViewModel.year`, mapping `BookDto/BookListItem -> BookViewModel` і binding до `yearProperty()`.
- `SessionService.clearCurrentSession()` очищав не той Preferences namespace; тепер делегує в реальний `SessionRepository.clearSession(collectionId)`.
- `BookImporterPort.countBooks()` більше не має sentinel default `-1`; ZIP importer має bounded реальну реалізацію.
- Динамічний `collectionDataSource` більше не має no-op/constant DataSource methods: username/password connection, log writer, login timeout, unwrap/isWrapperFor і parent logger делегуються в активний DataSource.

### Dead API / misleading compatibility surface

Видалено або звужено API без production caller/реальної ролі:

- `ImportReader` + dead FB2/INPX implementations;
- `ReaderBookResourcePort` + adapter/config;
- `BookMapperHelper` duplicate mapper;
- `NavigationService.clearSearch()` no-op;
- `TextStorageImpl.endParagraph()` no-op та його виклики;
- legacy `SqliteBookCommandRepository.setPragmaForBulkInsert/resetPragma` (PRAGMA lifecycle централізований в bulk optimizer);
- `LuceneSearchService.rebuildIndexParallel()` compatibility wrapper без caller;
- `MemoryMonitor.logMemoryUsage()` без caller;
- `CollectionManager.isSwitching()` / misleading `isDatabaseLocked()` без caller;
- `LuceneSearchService.isClosed()` без caller;
- unbounded dead `Fb2CoverParser.parseImageOnly()`;
- semantically incorrect/unused `DatabaseConnectionCleanup.isFullyCleaned()`;
- unused saved-search methods `executeRecent/executeMostUsed/executeByName` разом із зайвими port/adapter methods;
- unused encryption compatibility sentinel API `isInitialized()/isFallbackMode()` та unused public key-generator wrapper.

### Дублювання, зведене до одного джерела поведінки

- `BookMapperHelper` -> `BookMapper`;
- RAR/7z supported-entry logic -> `ArchiveImportSupport`;
- online network error sanitizing -> `OnlineRetryPolicy.safeNetworkFailure`;
- import change limit fallback -> `ImportChangeAccumulator.normalizeLimit`;
- UTF-8 strict validation -> `Utf8Validator` у shared module;
- SQLite reading-state datetime codec -> `SqliteDateTimeCodec`;
- async UI exception unwrapping -> `UiExceptionSupport.unwrapAsync`;
- FB2 converter format detection -> `Fb2ConversionSupport`;
- generic parser metadata snapshot -> real default implementation у `BookParser.readMetadata`; FB2/ZIP exact clones видалено;
- filename extension parsing -> `FileNameSupport.extension`;
- executor graceful shutdown -> `ExecutorShutdown.gracefully`.

Також видалено **49** підтверджених unused explicit imports та зайві constructor dependencies у Spring-компонентах.

## Постійний guard

Додано `tools/implementation-completeness-check.py`.

Поточний результат:

- production Java files scanned: **615**;
- TODO/FIXME/Unsupported/not-implemented markers: **0**;
- empty public/protected methods: **0**;
- sentinel-only interface defaults: **0**;
- unused explicit imports: **0**;
- unused Spring constructor dependencies: **0**;
- application use cases without production consumer: **0**;
- application output ports without production implementation: **0**;
- exact cross-file method clones >= 180 normalized chars: **0**;
- batch-progress UI path: reachable;
- Author year UI path: real field, not timestamp substitute;
- collection DataSource delegated contract: complete.

Exact-clone scan навмисно не заявляє, що семантичних/renamed near-duplicates у коді математично не існує; він блокує повернення довгих 1:1 копій, а architecture/reachability scans продовжують покривати structural duplication.

## Regression gates

Після cleanup-пакета виконано всі наявні `tools/*check.py`:

- **36/36 PASS**;
- `tools/v71-standalone-java-smoke.py`: **PASS**;
- reader portable smoke у Stage 19/20: **PASS**;
- FXML handler references: **165**, missing handlers: **0**.

Це **offline/static/runtime-smoke evidence**, а не Maven/CI acceptance.

## Що ще не можна називати завершеним

- `./mvnw clean verify -Pproduction` фактично не green у цьому середовищі, бо Maven distribution/network недоступні;
- GitHub Ubuntu/Windows/macOS CI не запускалися з цього WIP tree;
- JavaFX end-to-end runtime та package/jpackage acceptance ще потрібні;
- JVM/Lucene connected performance benchmark та online throughput/proxy/custom-truststore integration залишаються окремими acceptance пунктами.

Фінальний ZIP/patch не перепаковувати до останньої зміни та завершення цих acceptance кроків.


## Checkpoint 2026-08-30 13:xx — runtime reachability cleanup

Додатково після основного completeness pass:

- видалено redundant `NavigationHistoryService`; `MainController` використовує вже наявний `WorkspaceManager` напряму;
- прибрано legacy reader aliases `showReaderWorkspace`, `togglePageMode`, `isPageModeEnabled`;
- legacy `export.postCommand` / `export.runPostCommand` прибрані з загальних settings UI, але залишені як migration/compatibility keys;
- видалено public UI/service methods без Java/FXML/EventListener caller, разом із зайвими Spring dependencies;
- reader bookmarks дороблені end-to-end: add + list + navigate + delete;
- виправлено bookmark-position correctness: нові закладки зберігають exact `ReaderPosition` у `paragraph_id` з префіксом `rp:`, поле `position` містить реальний 0..100 percent; legacy `pN` bookmarks залишаються навігабельними через percentage fallback;
- `BookmarkRepository` звужено до реально споживаного контракту; мертві `findById/deleteByBookId` прибрані;
- architecture UI output-port debt: **17/18 baseline**;
- reachability: **165 FXML handlers**, **50 application use cases** з direct UI/MCP/OPDS entry.

Після цих змін повторний повний контроль:

- **36/36 `tools/*check.py` PASS**;
- `tools/v71-standalone-java-smoke.py`: **PASS**;
- Stage 19/20 reader portable smoke: **PASS**;
- Stage 25B reader refactor check: **PASS**.
