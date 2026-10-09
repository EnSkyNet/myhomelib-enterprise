# MyHomeLib — продуктові задачі V27+ після стабілізованого V26

**Дата:** 2026-09-29  
**Continuation source:** `myhomelib-enterprise-optimized-v26-checkpoint-2026-09-29.zip`  
**База:** тільки V26. Не повертатися до V25/V24 для нового product work.

## 0. Передумова

V26 є стабілізованою локально перевіреною базою. Перед публічним релізом нової функціональності потрібно закрити зовнішні V26-gates:

- Windows x64 Calibre / batch / process teardown / file-lock acceptance;
- installer / portable / upgrade / DPI / DPAPI;
- candidate-bound GitHub / SBOM / SCA / CodeQL / signing;
- live external integrations, якщо вони входять у release scope.

Нові product-функції можна розробляти паралельно в окремій гілці, але **не змішувати незакритий V26 external acceptance з твердженням, що V27+ уже release-ready**.

## 1. Незмінні engineering rules

1. Defect: red regression → мінімальний fix → targeted tests → downstream tests.
2. Feature: спочатку контракт і acceptance tests, потім production implementation.
3. Refactor: тільки якщо нова функція реально створює спільний stable contract або існуюча межа заважає коректній реалізації.
4. Performance/memory: ніяких claims або tuning без same-environment A/B.
5. Не створювати новий “універсальний framework”, якщо достатньо існуючих Domain/Application/Infrastructure/UI boundaries.
6. Усі schema changes — тільки нові Flyway migrations; released migrations не редагувати.
7. Нові JavaFX actions мають автоматично потрапити в UI action inventory, behavioral registry і user-functions documentation mapping.
8. Новий user-owned state має бути врахований у backup/portable transfer; у sync — тільки після стабілізації локального contract.
9. Secrets — тільки через наявний `SecretStore`; жодних токенів у SQLite/plain JSON/logs.
10. External/network/system інтеграції повинні мати fake/local deterministic contract tests; live acceptance — окремо.

---

# V27 — Reading Lists + Next in Series / On Deck

## 2. Reading Lists — впорядковані списки читання

### Мета

Додати окрему user-owned сутність “Список читання”, яка не дублює Groups або Smart Collections:

- ручний порядок книг;
- книги можуть бути з різних авторів/серій;
- користувач може створювати кілька списків;
- порядок стабільний і явно керований;
- список можна продовжити з першої непрочитаної/незавершеної книги.

### Де реалізовувати

**Domain**
- новий package: `myhomelib-domain/.../model/readinglist/`
- орієнтовні типи:
  - `ReadingListId`
  - `ReadingList`
  - `ReadingListItem`

**Application**
- package: `myhomelib-application/.../readinglist/`
- port:
  - `ReadingListRepository`
- use cases/services:
  - `CreateReadingListUseCase`
  - `RenameReadingListUseCase`
  - `DeleteReadingListUseCase`
  - `AddBooksToReadingListUseCase`
  - `RemoveReadingListItemsUseCase`
  - `ReorderReadingListUseCase`
  - `LoadReadingListUseCase`
  - `ListReadingListsUseCase`
  - `FindReadingListResumeItemUseCase`

**Infrastructure**
- `SqliteReadingListRepository`
- нова Flyway migration, якщо V64 ще вільна:
  - `V64__reading_lists.sql`

**UI**
- новий workspace:
  - `ReadingListsWorkspaceController`
  - FXML `reading-lists-workspace.fxml`
- navigation entry “Списки читання”
- context action для книги/checkbox selection:
  - “Додати до списку читання”
- підтримати:
  - Create
  - Rename
  - Delete
  - Add
  - Remove
  - Move up/down
  - drag-and-drop reorder
  - “Почати/продовжити список”

### Мінімальна схема

`reading_lists`
- `id TEXT PRIMARY KEY`
- `name TEXT NOT NULL`
- `description TEXT`
- `created_at`
- `updated_at`

`reading_list_items`
- `list_id`
- `book_id`
- `position INTEGER NOT NULL`
- `added_at`
- FK list → cascade delete
- FK book → cascade/remove відповідно до чинної book-deletion policy
- unique `(list_id, book_id)`
- unique `(list_id, position)`

Не додавати notes/tags/sharing у першу версію.

### Порядок

`position` має бути deterministic.

Для reorder:
1. завантажити поточний membership;
2. перевірити, що request містить рівно той самий набір item IDs;
3. виконати reorder однією транзакцією;
4. записати contiguous positions `0..N-1`;
5. у разі failure попередній порядок не повинен частково змінитися.

Не використовувати floating-point sort keys у першій реалізації.

### Backup / portable user data

Reading Lists — user-owned data.

У цій же задачі:
- додати export/import у `PortableUserDataService` / `VersionedUserDataTransferAdapter`;
- restore повинен резолвити книги за чинною stable identity policy, а не за title;
- невідновлювана книга не повинна підмінятися іншою за “схожою назвою”.

### Тести

Обов’язково:
- create/list/rename/delete;
- duplicate name policy;
- add one/many books;
- повторне add існуючої книги;
- deterministic reorder;
- invalid reorder membership → no mutation;
- delete list не видаляє книги;
- delete book не залишає corrupted item;
- empty list;
- 1000+ items reorder bounded по пам’яті;
- backup/export → restore;
- UI create/add/remove/reorder;
- keyboard move up/down;
- drag-and-drop observable effect;
- action inventory/documentation gate.

### Acceptance

- 100% Reading List user actions мають behavioral tests;
- list membership і порядок переживають restart;
- backup/restore зберігає порядок;
- жодна операція не змінює Groups/Smart Collections;
- видалення Reading List не видаляє book records/files.

---

## 3. Next in Series / On Deck

### Мета

На Dashboard показувати наступну книгу у вже розпочатій серії.

Це **derived query**, а не новий persisted state.

### Використати існуюче

- `LoadDashboardDataUseCase`
- `DashboardData`
- `ContinueReadingService`
- `ReadingProgressRepository`
- `BookQueryRepository`
- існуючі `Book.series` / `sequenceNumber`

### Новий Application contract

Орієнтовно:
- `NextInSeriesService`
- DTO `NextInSeriesItemDto`
- repository/query port тільки якщо чинних query methods недостатньо.

Не робити окрему “recommendation engine” для цього.

### Правила V1

Серія потрапляє в On Deck, якщо:
1. є щонайменше одна книга серії, яку користувач уже завершив;
2. немає іншої книги цієї серії з progress `>0 && <100` — така книга вже має йти в Continue Reading;
3. є наступна доступна книга з валідним `sequenceNumber`;
4. candidate — найменший unread sequence після останньої завершеної;
5. `null` sequence не використовувати для автоматичного order inference;
6. одна книга не повинна одночасно дублюватися у Continue Reading та On Deck.

### Dashboard

`DashboardData` додати:
- `List<NextInSeriesItemDto> nextInSeries`

`DashboardController` / `DashboardViewModel`:
- нова секція “Далі у серіях”;
- Open;
- Show series.

### Тести

- #1 completed → #2 shown;
- #1/#2 completed → #3;
- #2 in progress → On Deck series hidden;
- unread series → not shown;
- missing sequence → no invented order;
- duplicate/ambiguous sequence → deterministic tie handling або fail-safe exclusion;
- deleted/missing book → not shown;
- pagination/limit bounded;
- no duplicate with Continue Reading.

### Acceptance

- query не робить N+1;
- dashboard load залишається bounded;
- без нової persistence;
- no measurable regression у Dashboard load; якщо query стає важким — A/B до індексів.

---

# V28 — Reader profiles per format / series / book

## 4. Scoped Reader Preferences

### Поточна база

V26 вже має:
- global preferences через `ReaderPreferencesPort`;
- per-book override через `ReaderBookPreferencesPort`;
- `ReaderSettingsStateService`.

### Ціль

Precedence:

`Book override > Series override > Format override > Global`

Приклад:
- усі comics → Fit Width;
- конкретна manga-series → RTL + dual page;
- одна конкретна книга → індивідуальний override.

### Як робити

Спочатку characterization tests для поточного:
- global load/save/reset;
- book override precedence;
- clear book override.

Потім додати нові scopes.

Не переписувати Reader renderer.

### Application

Рекомендований контракт:
- `ReaderPreferenceScopeType { BOOK, SERIES, FORMAT }`
- `ReaderPreferenceScope`
- `ReaderScopedPreferencesPort`

Оскільки після цієї задачі вже буде три однакові collection-scoped override contracts, **дозволено** об’єднати існуючий per-book port у scoped port, але тільки після characterization tests і одним responsibility-boundary refactor.

`ReaderSettingsStateService` повинен отримувати:
- bookId
- series identity/name
- canonical format

і повертати resolved preferences + source scope.

### Persistence

Якщо generic scoped persistence обрана:
- next Flyway migration;
- таблиця `reader_preference_overrides`
  - `scope_type`
  - `scope_key`
  - `preferences_json`
  - `updated_at`
  - PK `(scope_type, scope_key)`
- migrate current `reader_book_preferences` rows у scope `BOOK`;
- стару таблицю не видаляти в тій самій migration, якщо немає потреби; cleanup окремою наступною release migration після доказу.

Альтернатива: додати лише series/format tables і не рефакторити book storage. Обрати варіант із меншим regression surface після tests.

### UI

У Reader Settings:
- “Застосувати глобально”
- “Зберегти для цього формату”
- “Зберегти для цієї серії”
- “Зберегти тільки для цієї книги”
- “Очистити override”

UI має показувати, звідки зараз прийшов effective profile.

### Тести

- exact precedence;
- clear one scope reveals lower scope;
- series A не впливає на series B;
- EPUB format profile не впливає на PDF/comic;
- per-book override highest;
- migration existing book overrides;
- backup/portable transfer;
- collection switch isolation;
- malformed stored JSON fail-safe;
- Reader open/reopen.

---

# V29 — Web Library як installable PWA + offline reading

## 5. Мета

Поточний Web Library уже працює через:
- `myhomelib-web`
- `myhomelib-opds`
- `WebLibraryRenderer`
- `WebReaderRenderer`
- `/web/read/.../progress`

Не створювати окремий web backend.

### Фаза 1 — installable PWA shell

У `myhomelib-web`:
- manifest model/renderer;
- service-worker source;
- icons/resources release pipeline.

У `JdkOpdsServer`:
- `/web/app.webmanifest`
- `/web/sw.js`
- versioned static assets routes.

Додати в `<head>`:
- manifest
- theme color
- PWA metadata
- service-worker registration.

### Security

- Service Worker працює тільки в secure context;
- localhost допустимий;
- LAN PWA — тільки через чинний HTTPS/TLS contract OPDS;
- не послаблювати current non-loopback TLS policy;
- authentication/session/token material не кешувати в Cache Storage;
- HTML/API responses з sensitive data мають explicit cache policy.

### Фаза 2 — offline app shell

Cache only:
- CSS/JS/app shell;
- manifest/icons;
- offline fallback page.

Не кешувати автоматично всю бібліотеку.

### Фаза 3 — explicit offline book

Кнопка:
- “Зберегти для offline reading”

Необхідно визначити offline representation.

Для першої версії **не писати браузерний EPUB/FB2 parser**.

Кращий bounded варіант:
- сервер формує версійований sanitized web-reading package/document projection;
- service worker кешує тільки явно обрані книги;
- package має stable book ID + content revision/fingerprint;
- update invalidates old cache only після успішного отримання new version.

### Progress sync

Онлайн:
- залишити існуючий `/progress`.

Offline:
- browser IndexedDB queue:
  - book id
  - chapter/offset
  - client timestamp
  - monotonic local sequence
- при reconnect відправити queued progress;
- conflict policy має бути explicit.

Для V1:
- latest-progress update тільки якщо incoming timestamp/version не старіший;
- server response повертає accepted canonical state;
- не вигадувати multi-master CRDT.

### Тести

- manifest validity;
- secure-context policy;
- service-worker route/content type;
- cache version bump;
- offline shell;
- explicit offline book only;
- logout/token revocation → protected content inaccessible online;
- no credentials in cache;
- stale offline content revision;
- queued progress replay idempotency;
- duplicate replay;
- server-newer vs client-older;
- browser E2E бажано через headless Chromium тільки якщо toolchain додається виправдано.

### Acceptance

- installable PWA на підтримуваному browser;
- app shell відкривається offline;
- explicitly saved book читається offline;
- progress після reconnect синхронізується;
- жодна книга не кешується без user action;
- cache bounded і очищуваний з UI.

---

# V30 — Kobo native sync

## 6. Передумови

Починати тільки після:
- стабільного PWA/progress model;
- Windows/device export acceptance;
- окремого протокольного research/probe.

Не вбудовувати Kobo-specific logic у `ExportToDeviceUseCase`.

### Архітектура

Application:
- `KoboSyncService`
- `KoboSyncPort`
- DTOs/contracts для:
  - device registration
  - library projection
  - metadata/content revision
  - reading progress
  - sync cursor/checkpoint

Infrastructure:
- Kobo protocol adapter.

UI:
- Integration Center → Kobo Sync;
- enable/disable;
- device status;
- regenerate/remove credentials;
- last sync/error;
- optional “sync now”.

### Identity

Book mapping:
- stable MyHomeLib book ID + artifact/content fingerprint;
- не title-based.

Device identity:
- generated opaque ID;
- secrets через `SecretStore`.

### Content

Reuse:
- current artifact selection;
- converter toolchain;
- EPUB/KEPUB conversion тільки якщо реально потрібне protocol/client compatibility.

Не створювати окремий converter stack.

### Progress

Mapping між Kobo progress і MyHomeLib progress має бути окремим pure translator із tests.

Conflict:
- explicit deterministic policy;
- не перезаписувати новіший progress старішим;
- server/device timestamps самі по собі не вважати безумовно trustworthy без version/cursor logic.

### Tests

- protocol fixtures;
- authentication;
- incremental cursor;
- book add/update/remove;
- unchanged book no retransmit;
- conversion failure;
- partial sync;
- retry idempotency;
- progress both directions;
- stale progress rejection;
- secret redaction;
- device revoke.

### Live acceptance

Обов’язково на реальному Kobo:
- first sync;
- library appearance;
- open/read;
- progress → MyHomeLib;
- MyHomeLib progress → Kobo;
- cover/metadata;
- reconnect;
- device revoke.

---

# V31 — Secure application auto-update

## 7. Мета

Оновлення програми без ручного скачування, але fail-closed.

### Не робити

- не оновлювати JAR “на місці” запущеним процесом;
- не довіряти одному SHA-256 без authenticated signature;
- не виконувати downloaded executable до перевірки;
- не зберігати update channel URL як довільний executable endpoint без policy.

### Application contracts

- `UpdateChannel { STABLE, BETA, MANUAL }`
- `ReleaseManifest`
- `CheckForApplicationUpdateUseCase`
- `ApplicationUpdatePort`
- `UpdateVerificationService`

### Infrastructure

- HTTP manifest client;
- bounded download;
- signature verification;
- SHA-256 verification;
- staged artifact directory;
- update journal.

### Bootstrap / launcher

Окремий updater helper/launcher boundary:
1. основна app перевіряє manifest;
2. download у staging;
3. hash + signature;
4. запис update plan;
5. app clean shutdown;
6. helper замінює installation atomically/best-effort platform-specific;
7. launch new version;
8. health marker / rollback on failed startup.

### Signature

Manifest має містити:
- product ID;
- version;
- channel;
- platform/arch;
- artifact URL;
- artifact length;
- SHA-256;
- signature/key id;
- minimum updater version.

Public verification key в app; private key ніколи не входить у repo.

### UI

Settings → Updates:
- channel;
- check now;
- automatic check toggle;
- download/install policy;
- release notes;
- “Restart and update”.

### Tests

- valid manifest;
- wrong signature;
- wrong hash;
- truncation;
- downgrade blocked by default;
- wrong platform;
- revoked/unknown key;
- interrupted staging;
- restart rollback;
- no update while destructive operation active;
- portable mode policy;
- Windows installer mode live acceptance.

---

# V32 — Readwise annotation connector

## 8. Мета

Додати прямий export highlights/notes без зміни annotation model.

### Reuse

Існуючі:
- `AnnotationExportQueryPort`
- `AnnotationExportService`
- `KnowledgeMarkdownExportService`

### Application

Новий port:
- `AnnotationRemoteExportPort`

Request:
- selection scope;
- selected/filtered books;
- dedup/export cursor.

Service:
- `ExportAnnotationsToReadwiseUseCase`

### Infrastructure

`ReadwiseAnnotationExportAdapter`
- HTTPS only;
- token через `SecretStore`;
- bounded batch;
- retry тільки для safe/idempotent cases;
- rate-limit handling;
- no annotation text in logs.

### Export state

Щоб не дублювати export:
- зберігати remote fingerprint/export checkpoint;
- fingerprint від stable annotation ID + updated version/hash;
- changed annotation resend/update according to provider contract.

Не використовувати title+quote як identity, якщо є stable annotation id.

### UI

Integration Center:
- token/config;
- test connection;
- export selected annotations;
- export filtered;
- last export summary.

### Tests

- request mapping;
- batching;
- rate limit;
- timeout;
- auth failure;
- duplicate rerun;
- changed annotation;
- deletion policy;
- token never logged/plain persisted.

---

# V33 — Local Recommendations

## 9. Мета

“Схожі книги у вашій бібліотеці” без cloud dependency.

### V1 тільки metadata-based

Features:
- genres;
- authors;
- keywords;
- series;
- language;
- rating optionally as weak factor.

Не використовувати user-sensitive behavioral profiling у V1.

Не додавати vector DB / embeddings / AI.

### Application

- `FindSimilarBooksUseCase`
- `BookSimilarityPolicy`
- explainable result:
  - score
  - top reasons

Наприклад:
- same series;
- shared authors;
- genre overlap;
- keyword overlap;
- same language.

### Infrastructure/query

Спочатку використати існуючий SQLite/Lucene metadata.

Не будувати materialized recommendation index без A/B evidence.

### UI

Book Details:
- секція “Схожі книги”

Dashboard optional:
- “Може зацікавити” тільки після окремого UX test; не змішувати одразу з On Deck.

### Tests

- deterministic score;
- same book excluded;
- deleted books excluded;
- no recommendation from empty metadata;
- explainability;
- tie ordering deterministic;
- large-library benchmark.

### Performance gate

На production-like dataset:
- baseline Book Details open;
- variant with recommendations;
- latency + allocations/memory;
- якщо regression суттєвий — async/load-on-demand або окремий index тільки після вимірювання.

---

# V34 — Duplicate pages/images for comics/scans

## 10. Мета

Знаходити повторні сторінки у CBZ/CBR/CB7 та, за потреби, image-based scan collections.

### Не змішувати з book duplicate detector

Існуючий `ExactDuplicateScanner` працює на рівні book artifacts.

Новий feature — **page-level evidence**.

### Reader/shared boundary

Використати:
- `ComicPageSource`
- `ComicDocumentSession`
- bounded image decode contracts.

Не тягнути JavaFX Image у Application.

### Алгоритм V1

Спочатку exact duplicate pages:
1. stream compressed/or decoded-safe bytes;
2. cryptographic hash;
3. group same hash;
4. optional dimensions/size sanity check.

Потім, тільки окремою задачею:
- perceptual hash для visually-near duplicate.

Не починати з computer vision framework.

### Application

- `ScanComicDuplicatePagesUseCase`
- `DuplicatePageGroup`
- progress/cancel.

### UI

Comic Reader або Library Health:
- “Перевірити повторні сторінки”
- результат:
  - page numbers;
  - file entry;
  - exact/near kind;
  - preview.
- V1 лише report; **не auto-delete**.

### Tests

- exact duplicate bytes;
- different image same size;
- same pixels different metadata/encoding — окремо для future perceptual mode;
- huge archive bounded;
- corrupt entry;
- cancel;
- archive traversal/path safety unchanged;
- deterministic groups.

---

# 11. Порядок виконання

| Версія | Scope | Причина |
|---|---|---|
| V27 | Reading Lists + Next in Series | максимальна user value при мінімальному external risk |
| V28 | Reader profiles per format/series/book | використовує стабільний Reader settings boundary |
| V29 | PWA/offline Web Library | великий UX-крок, але поверх уже наявного Web/OPDS |
| V30 | Kobo Sync | залежить від зрілого progress/device/web integration contract |
| V31 | Secure auto-update | release/platform-sensitive, робити окремим checkpoint |
| V32 | Readwise connector | вузький adapter поверх готової annotation export моделі |
| V33 | Local recommendations | додавати тільки з benchmark/performance evidence |
| V34 | Comic duplicate pages | окрема diagnostics feature без зміни book duplicate model |

Readwise можна переставити раніше V31, якщо є реальна продуктова потреба. Не об’єднувати Kobo, updater і PWA в один checkpoint.

---

# 12. Обов’язкові cross-cutting updates для кожного feature checkpoint

Після кожної нової user-facing функції:

1. `docs/current/USER-FUNCTIONS-UA.md`
2. `docs/current/UI-ACTION-COVERAGE.json`
3. `docs/current/UI-ACTION-COVERAGE-UA.md`
4. behavioral action coverage registry
5. F1 help topic + UK/EN/BG localization, якщо є новий workspace/dialog
6. `ARCHITECTURE.md`, якщо змінено boundary/dependency/data ownership
7. `docs/current/FEATURES.md`
8. `docs/history/HISTORY-UA.md`
9. current validation / next tasks
10. backup/portable-transfer contract для нового user-owned persisted state.

Не створювати окремі root-level audit Markdown файли. Поточні docs → `docs/current/`, release evidence → `docs/release/`, історія → `docs/history/`.

---

# 13. Gate після кожного checkpoint

Мінімум:

- targeted tests;
- modified module full suites;
- UI action inventory;
- action-specific behavior coverage;
- localization/help checks;
- architecture-check + ArchUnit;
- static release check;
- supply-chain checks;
- full Maven `clean verify`;
- production package;
- clean source ZIP;
- unpacked ZIP self-check;
- SHA-256.

Для performance-sensitive feature:
- same-environment A/B.

Для external/platform feature:
- локальний deterministic contract PASS не замінює live acceptance.

---

# 14. Чого не робити у V27–V34 без окремого обґрунтування

- не переходити на microservices;
- не вводити event bus/framework заради нових features;
- не створювати generic “integration platform” замість вузьких ports;
- не додавати Elasticsearch/vector DB;
- не додавати cloud account як обов’язкову умову;
- не міняти SQLite як primary store;
- не переписувати JavaFX UI;
- не робити multi-user ACL;
- не додавати social network/lending/storefront;
- не зберігати secrets у DB/plain config;
- не маскувати platform defects retries/sleeps;
- не робити “AI recommendations” до deterministic local V1 і вимірюваного product need.

---

# 15. Перший конкретний наступний task

Після V26 external acceptance почати **тільки V27**:

1. Characterize current Groups/Smart Collections/reading-progress/series behavior tests.
2. Написати RED contracts для Reading Lists persistence/order/backup.
3. Додати migration `V64__reading_lists.sql` (якщо номер вільний).
4. Реалізувати domain/application/repository.
5. Додати Reading Lists workspace і context actions.
6. Закрити UI behavioral coverage/documentation.
7. Реалізувати derived `NextInSeriesService` без persistence.
8. Додати On Deck у `DashboardData` / `LoadDashboardDataUseCase`.
9. Провести query plan/A-B тільки якщо Dashboard query виявиться важким.
10. Full downstream gates + clean V27 checkpoint.
11. Не починати V28 у тому ж checkpoint.

## Definition of Done V27

- Reading Lists fully CRUD + deterministic reorder;
- backup/portable restore;
- 100% user-action behavioral coverage;
- On Deck коректний і не дублює Continue Reading;
- no N+1;
- no new architecture violations;
- migrations immutable;
- full clean verify green;
- clean ZIP + updated docs/history/next-tasks.
