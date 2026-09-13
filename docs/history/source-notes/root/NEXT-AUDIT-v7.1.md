# MyHomeLib Enterprise v7.1 — наступний прохід після WIP checkpoint 2026-08-30

Статус: **WIP / НЕ фінальний реліз**.

Цей файл є актуальним списком продовження після глибинного рефакторингу під великі колекції та Reader. Не повторювати вже закриті cleanup-пункти без нових доказів регресії.

## 1. Що вже закрито на поточному checkpoint

### Великі колекції / SQLite / import
- bounded change tracking для INPX/legacy/directory import;
- configurable threshold selective Lucene -> full rebuild fallback;
- PRAGMA lifecycle restore до фактичних попередніх значень;
- batch-size вирівняний на benchmark-driven 1000;
- schema-safe index suspend/restore під import;
- великі `IN (...)` централізовано chunk-яться приблизно по 400;
- maintenance orphan scan переведено з heap `HashSet` на disk-backed SQLite path index;
- full `DictionaryCache` видалено, autocomplete працює bounded SQL;
- Group Workspace: EXISTS + server pagination; V43 `(group_id, book_id)`;
- Author Workspace: server pagination/filter/sort, без hard cap 1000;
- tree view працює по поточній server-paginated сторінці, без scan 10k;
- publisher/series navigation переведені на server-side query;
- advanced Lucene search — paginated/deep `searchAfter`, без hard cap 1000;
- Search/Book/Cover caches переведені на bounded/weight-based policy;
- Spring CacheManager/generic dead cache layer видалені;
- per-collection Lucene index lifecycle + freshness marker + WAL/checkpoint-aware sealing;
- bootstrap disk Lucene root більше не є одночасно індексом і контейнером — bootstrap index in-memory;
- clean per-collection Lucene може reuse без повного rebuild;
- dirty/stale index не лишається видимим під час rebuild;
- collection shutdown/switch lifecycle централізовано.

### Copy between collections / user data
- книги копіюються у постійний target physical storage, не з тимчасового каталогу, який потім видаляється;
- metadata/state переноситься напряму, без повторного parser/import path;
- N+1 source lookup прибрано;
- target/source full Lucene rebuild на кожне copy прибрано;
- copy має транзакційний hook: book + collection-local user state мають одну commit/rollback межу;
- переносяться `bookmarks`, `reading_progress`, `reading_stats`, `reading_history`, `reader_book_preferences`;
- bookmark ID collision не повинен перепризначати чужі дані;
- physical DB path collection storage централізовано.

### Reader / великі книги
- parsing/materialization open-book винесено з JavaFX thread у cancellable background prepare;
- style-span lookup індексований;
- `chapterIndexAt()` O(log N);
- layout великого абзацу працює bounded window;
- search усередині книги — async/cancellable та bounded;
- image cache byte-bounded, decode орієнтується на display size;
- font cache bounded;
- Reader bookmarks дороблені add/list/go/delete;
- нові bookmarks зберігають точний serialized ReaderPosition, legacy fallback збережено;
- Reader session statistics підключені до runtime;
- V41: `reading_stats` singleton per book + atomic UPSERT;
- V42: per-book Reader preferences у collection SQLite;
- legacy/global Reader JSON compatibility централізовано через спільний codec.

### User data / backup / restore
- один versioned user-data format; старий дубльований adapter видалено;
- schema v2 restore streaming;
- legacy schema v1 restore streaming для великих arrays;
- reader overrides batch/streaming, без глобального O(N) rewrite path;
- LibID ambiguity policy не мапить user data на випадкову книгу;
- restore накопичує bounded change IDs для selective/full Lucene sync;
- backup/restore не імітує derived covers/index backup;
- фальшиві cancel/progress UI paths прибрані;
- Lucene rebuild failure після restore не маскується як success.

### Online / networking
- shared HTTP client reuse;
- Range + If-Range + ETag/Last-Modified sidecar;
- source URI fingerprint;
- shared global/per-host concurrency limiter;
- bounded host-gate lifecycle;
- retry classification + jitter + Retry-After;
- 4xx permanent statuses не повторюються як generic IOException;
- shared throttled progress із cancellation check на кожному read;
- ConnectionScript підключений до спільної policy.

### Completeness / dead code / duplicates
- TODO/FIXME/not-implemented scan;
- public/protected empty method scan;
- sentinel-default interface scan;
- Spring constructor wiring scan;
- output-port implementation/use-case consumer scan;
- bidirectional FXML wiring scan;
- exact cross-file clone scan;
- dead compatibility aliases/facades/helpers системно прибрані;
- runtime settings без consumer або видалені, або дороблені;
- declared-but-unreachable author follow / batch progress / bookmark UI дороблені.

## 2. Що перевірено саме на цьому WIP snapshot

Короткий контроль перед пакуванням 2026-08-30:

- `python3 tools/build-check-v7.py` — PASS;
- `python3 tools/implementation-completeness-check.py` — PASS;
- `python3 tools/inpx-search-index-consistency-check.py` — PASS;
- `python3 tools/user-data-consistency-check.py` — PASS;
- `python3 tools/stage19-20-reader-check.py` — PASS;
- `python3 tools/stage25c-search-sync-refactor-check.py` — PASS.

Останній повний checkpoint **до найновіших directory-import змін** був: 40/40 offline guards PASS + standalone Java smoke PASS.

Не заявляти 40/40 для саме цього ZIP, доки пункт 3.1 нижче не виконано.

## 3. Що доробити наступного разу — у пріоритетному порядку

### P0 / correctness перед релізом

1. **Завершити directory-import останній пакет**:
   - один streaming filesystem walk без попереднього full count;
   - child import не скидає aggregate progress;
   - `ImportResult.status=CANCELLED` при cancellation;
   - 100%/"completed" тільки після Lucene sync;
   - якщо `imported > 0`, але exact change IDs відсутні — safe full rebuild;
   - додати regression guard/fixtures саме на ці semantics.

2. **Copy-between-collections E2E на двох реальних SQLite DB**:
   - physical file існує після завершення;
   - target book metadata/state збігаються;
   - bookmarks/progress/history/stats/Reader overrides перенесені;
   - rollback при state-transfer error;
   - duplicate/skip не лишає orphan copied file;
   - source і target Lucene marker/freshness після операції правильні.

3. **Per-collection Lucene runtime acceptance**:
   - cold open -> rebuild;
   - clean close -> reopen -> reuse без rebuild;
   - switch A -> B -> A -> reuse обох індексів;
   - DB-only mutation/crash-like stale marker -> rebuild;
   - WAL checkpoint після close не створює false dirty;
   - benchmark warm switch для 100k/500k/1M.

4. **Повний контроль після останніх змін**:
   - усі актуальні `tools/*check.py`;
   - `tools/v71-standalone-java-smoke.py`;
   - XML/FXML/YAML/shell;
   - V1–V36 immutable SHA-256;
   - upgrade through V43;
   - release-tree cleanliness.

### P1 / великі колекції

5. Повторний системний scan після directory-import/copy changes:
   - `OFFSET` у великих sequential paths;
   - hard caps 1000/10000/100000;
   - `findAll()/toList()` на full catalog;
   - `readAllBytes()` для зовнішніх/великих даних;
   - unbounded `HashMap/HashSet/List`;
   - SQL N+1;
   - SQLite bind-variable overflow;
   - per-record allocations/normalization у import hot path.

6. **OPDS/MCP deep pagination**:
   - визначити, де OFFSET допустимий лише для невеликої сторінки;
   - для deep sequential browsing перейти на keyset/cursor, якщо потрібне масштабування до 1M;
   - не ламати OPDS semantics.

7. **JVM/Lucene performance evidence**:
   - 100k/500k/700k/1M;
   - full rebuild;
   - selective 10k/50k;
   - per-collection warm reopen/switch;
   - peak heap/RSS/GC;
   - index size/segments;
   - writer RAM/merge tuning — тільки після вимірювань.

8. **INPX/legacy/directory import benchmark після останніх змін**:
   - 100k/500k/700k/1M;
   - peak heap/RSS;
   - cancellation latency;
   - selective-vs-full Lucene threshold evidence;
   - index suspend/restore crash/cancel behavior.

### P1 / Reader runtime

9. Завершити JavaFX runtime regression matrix:
   - large FB2/EPUB open cancellation;
   - close/switch while parse is running;
   - one/two-page position preservation;
   - wide->narrow->wide resize;
   - giant paragraph layout;
   - large inline-style document;
   - async book search + stale-result cancellation;
   - selection vs swipe/tap/long-press/pinch;
   - bookmarks exact position;
   - session statistics and position autosave;
   - autoscroll + manual navigation;
   - decoded image memory budget.

10. Reader parity залишки не робити блокерами без доказаної користі:
   - editable day/night profiles;
   - richer colors/typography;
   - optional position sync foundation.

### P1 / Online acceptance

11. Local embedded HTTP acceptance:
   - 10 MB / 100 MB / 1 GB synthetic downloads;
   - resume after interruption;
   - stale ETag/Last-Modified;
   - cancellation latency;
   - concurrent same-host/different-host;
   - retry/backoff/Retry-After;
   - proxy;
   - custom truststore/TLS;
   - no trust-all.

### Formal acceptance / release

12. **Maven/JVM verify**, коли мережа/dependencies доступні:

```bash
./mvnw clean verify -Pproduction
```

Не називати Maven PASS без фактичного green run.

13. Після останньої зміни оновити:
- `CURRENT-AUDIT-STATE-v7.1.txt`;
- `CONTINUE-AUDIT-v7.1.md`;
- `CODE-COMPLETENESS-AUDIT-v7.1.md`;
- `UPSTREAM-PARITY-MATRIX-v7.1.md`;
- `ARCHITECTURE-UPGRADE-v7.1.md`;
- `PERFORMANCE-v7.1.md`;
- `GITHUB-CI-v7.1.md`;
- `UPGRADE-FROM-v7.md`;
- release notes / validation.

14. Лише після цього:
- package final ZIP + v7->v7.1 patch;
- extract ZIP у clean temp;
- run offline gates з extracted tree;
- apply patch до чистого baseline v7;
- SHA-256 + mode-bit parity patch-tree vs ZIP;
- SHA-256 самого ZIP;
- Maven + GitHub Ubuntu/Windows/macOS green для formal acceptance.

## 4. Не повторювати без нової регресії

- bounded import accumulator;
- PRAGMA restore lifecycle;
- dead `DictionaryCache` cleanup;
- duplicate `NavigationHistoryService` cleanup;
- user-data duplicate adapter cleanup;
- Reader bookmark exact-position fix;
- author-follow UI;
- batch progress UI;
- dynamic DataSource delegation;
- per-collection Lucene basic architecture;
- V41/V42/V43 migrations;
- full-table maintenance orphan `HashSet` cleanup;
- weight-based Search/Book/Cover/Reader image cache conversion.

## 5. Незмінні правила

Цілісність user data > correctness > performance > cleanup.
Не змінювати V1–V36.
Не додавати index без EXPLAIN + benchmark.
Не повертати великі OFFSET scans, full-table heap caches або `List<1_000_000>`.
Не блокувати JavaFX thread великим parse/search/repagination.
Не залишати UI/config/API, які виглядають реалізованими, але не мають runtime behavior.
Не називати Maven/CI/release PASS без фактичної перевірки.

## 6. Checkpoint 2026-08-30 після directory/copy/Lucene/MCP scale pass

Закрито на цьому проході:
- directory-import: один lazy walk, aggregate progress без reset, коректний CANCELLED, 100% тільки після Lucene finalization, safe full rebuild коли imported>0 і exact IDs відсутні, deleted-only changes не губляться;
- copy-between-collections: physical file не видаляється після успішного DB commit через post-commit Lucene failure; state-transfer failure до commit прибирає orphan file; dirty/absent target index rebuild перед selective sync;
- per-collection Lucene: failed selective/full sync позначає index DIRTY і close/seal більше не може помилково зробити stale index fresh;
- MCP search_books: прибрано full-catalog outer JOIN+GROUP BY; author/genre filter через EXISTS; author/genre enrichment тільки для page rows; ORDER BY lower(title) використовує наявний idx_books_title_lower;
- додано regression guards `directory-import-lifecycle-check.py`, `copy-between-collections-check.py`, `mcp-search-scale-check.py` та розширено collection search lifecycle guard.

Фактичний контроль поточного дерева:
- усі актуальні `tools/*check.py`: **43/43 PASS**;
- `tools/v71-standalone-java-smoke.py`: **PASS**;
- Maven/JVM green **не підтверджено** через недоступність dependency download.

Synthetic SQLite 1M MCP evidence (limit 50):
- offset 0: median ~0.493 ms;
- offset 50k: ~10.393 ms;
- offset 500k: ~101.989 ms;
- offset 900k: ~184.692 ms;
- negative full substring scan: ~2.846 s.

Не додавати новий index заради MCP без окремого EXPLAIN+benchmark. OFFSET лишається protocol-compatible; для deep browse вимір уже <200 ms на synthetic 1M, тому keyset не вводити ціною зміни MCP semantics без окремого API/cursor design.

Наступні дії: тільки connected/runtime acceptance — Maven/JVM, Lucene large-library, Reader JavaFX matrix, local HTTP acceptance. Після останньої зміни — final docs/ZIP/patch parity.


## 7. Checkpoint 2026-08-30 після standalone network-policy runtime pass

Додатково закрито без Maven:
- `tools/v71-standalone-java-smoke.py` тепер реально виконує `OnlineHttpPolicy`;
- custom PKCS12 truststore завантажується у JVM `SSLContext`;
- plaintext truststore password відхиляється;
- локальний HTTP proxy routing проходить реальний `HttpClient.send`;
- trust-all TLS режим не додавався.

Standalone Java smoke після зміни: **PASS**.

Все ще не називати повним HTTP acceptance: adapter 10MB/100MB/1GB, resume/cancel та HTTPS server certificate-chain integration потребують повного JVM test runtime. Maven wrapper у поточному середовищі повторно заблокований DNS/мережею до `repo.maven.apache.org`.

## 8. IDEA/JAVAC compile checkpoint 2026-08-30

User-side Maven compilation provided real javac evidence and exposed 34 source errors in `myhomelib-infrastructure` that the offline source guards could not prove away. The current WIP tree contains fixes for all 34 reported errors.

Current evidence after the fixes:
- all `tools/*check.py`: **43/43 PASS**;
- `tools/v71-standalone-java-smoke.py`: **PASS**;
- Maven compile in this sandbox: still blocked before Maven distribution/dependencies by DNS to `repo.maven.apache.org`.

Immediate next action is not another cleanup pass: re-run `./mvnw clean verify -Pproduction` (or Windows `mvnw.cmd clean verify -Pproduction`) on the corrected tree. Any new compiler/test failure from that run becomes the next P0 item. Do not declare Maven/CI green until that real run succeeds.

## 9. Functional regression checkpoint 2026-08-30

Після user-side runtime/IDEA перевірок виконано окремий feature-preservation audit, а не лише dead-code/reachability scan.

Порівняно поточне дерево з WIP checkpoint-ами 16:40/18:20/19:05 та старішим `myhomelib-enterprise-master`:
- 0 втрачених production Java/FXML files від останніх WIP checkpoint-ів;
- 0 втрачених FXML action bindings;
- 0 втрачених `fx:id`;
- старі Backup/Restore Cancel controls були фальшивою cancellation і свідомо не повертаються;
- старі Add/Remove book-to-collection link APIs замінені physical copy-between-collections і не повинні відновлюватися.

Знайдена реальна регресія з large-collection refactor: Author Workspace втратив `Series -> numbered books -> no-series books` grouping. Відновлено bounded/server-paged варіант без повернення full-author heap materialization.

Online/open UX посилено: `WorkspaceManager.showNewReaderWorkspace(BookId)` тепер є центральним guarded entry point. Якщо physical resource відсутній, Reader не відкривається до user confirmation + successful download/persist. Це покриває звичайне читання, Recent Books, back/forward Reader history і programmatic UI navigation.

Додано:
- `FUNCTIONAL-REGRESSION-AUDIT-v7.1.md`;
- `docs/release/FUNCTIONAL-UI-BASELINE-v7.1.json`;
- `tools/functional-regression-check.py`.

Фактичний контроль після цих змін:
- усі актуальні `tools/*check.py`: **46/46 PASS**;
- `tools/v71-standalone-java-smoke.py`: **PASS**.

З user report залишаються runtime-specific Reader питання, які не можна оголосити fixed без конкретного failing book/runtime evidence: intermittent merged words та конкретний compilation/nested-TOC case. Quick-theme persistence, full Reader settings, file-size columns, text-file language config та downloaded-author update grouping присутні в current source і зафіксовані ratchet-check-ом.

## Runtime checkpoint 2026-08-30 22:xx — fix-9

За фактичним Windows/JavaFX runtime логом виправлено:
- legacy MyHomeLib ConnectionScript: окремий bare HTTP/HTTPS URL рядок тепер сумісно ігнорується як upstream 2.5 `Code=-1`, інші невідомі команди лишаються validation error;
- remote INPX більше не записує `cache/catalog-updates` як `collection_root`; використовується permanent collection root або `AppPaths.downloadsDir()/collectionId`;
- Lucene reuse validation виконується до derived `syncSeriesFromBooks()`, тому non-search series normalization не повинна провокувати зайвий full rebuild; після normalization reusable marker reseal-иться;
- Lucene activation тепер логує точну причину rebuild/reuse;
- Windows JavaFX DirectoryChooser у Collection Properties має safe fallback без invalid initial folder.

Після змін: 46/46 актуальних `tools/*check.py` PASS (запущені пакетами через timeout shell), `v71-standalone-java-smoke.py` PASS, окремий javac/runtime legacy ConnectionScript parser smoke PASS. Maven у sandbox не запускається через DNS до Maven Central; перевірити `mvnw.cmd clean verify -Pproduction` на connected Windows.
