# MyHomeLib — продовження з V27

**Дата handoff:** 2026-09-30  
**База:** `myhomelib-enterprise-optimized-v26-checkpoint-2026-09-29.zip`  
**Статус бази:** V26 локально/детерміністично стабілізований; Windows/live/connected acceptance залишається зовнішнім gate і не повинен змішуватися з V27 product work.

## Головна задача наступної сесії

Реалізувати **тільки V27: Reading Lists + Next in Series / On Deck**. Не починати V28 у цьому checkpoint.

## Незмінні правила

1. Defect: RED regression → мінімальний fix → targeted tests → downstream tests.
2. Feature: спочатку contract/acceptance tests, потім production implementation.
3. Не створювати новий універсальний framework; використовувати чинні Domain/Application/Infrastructure/UI boundaries.
4. Schema changes — тільки нова Flyway migration; released migrations не редагувати.
5. Нові JavaFX actions мають потрапити в UI action inventory, behavioral registry та user-functions mapping.
6. Reading Lists є user-owned state: одразу включити backup/portable transfer.
7. Next in Series — тільки derived query, без нової persistence.
8. Ніяких performance claims/tuning без same-environment A/B.

## Пакет A — Reading Lists contracts + persistence

Можна виконувати паралельно з пакетом B після characterization current behavior.

- Characterization tests для Groups / Smart Collections / reading progress / series behavior, щоб довести відсутність cross-feature mutation.
- RED tests для:
  - create/list/rename/delete;
  - duplicate-name policy;
  - add one/many books;
  - повторного add існуючої книги;
  - deterministic reorder;
  - invalid reorder membership → no mutation;
  - delete list не видаляє книги;
  - delete book не залишає corrupted item;
  - empty list;
  - 1000+ items reorder bounded по пам'яті.
- Domain: `model/readinglist/` з `ReadingListId`, `ReadingList`, `ReadingListItem`.
- Application: `ReadingListRepository` + use cases create/rename/delete/add/remove/reorder/load/list/find-resume-item.
- Flyway: використати `V64__reading_lists.sql` **лише якщо V64 реально вільна**; інакше взяти наступний вільний номер.
- Persistence schema:
  - `reading_lists(id, name, description, created_at, updated_at)`;
  - `reading_list_items(list_id, book_id, position, added_at)`;
  - unique `(list_id, book_id)`;
  - unique `(list_id, position)`;
  - contiguous positions `0..N-1`;
  - reorder однією транзакцією.

## Пакет B — Backup / portable transfer

Можна розробляти паралельно з UI після стабілізації repository contract.

- Додати Reading Lists у `PortableUserDataService` / `VersionedUserDataTransferAdapter`.
- Restore резолвить книги тільки за чинною stable identity policy, не за title/fuzzy matching.
- Невідновлювана книга не повинна бути підмінена іншою.
- RED/green acceptance: export → restore з тим самим membership і порядком.

## Пакет C — Reading Lists UI

Після стабільних Application contracts.

- `ReadingListsWorkspaceController`.
- `reading-lists-workspace.fxml`.
- Navigation entry: **«Списки читання»**.
- Context/selection action: **«Додати до списку читання»**.
- Дії: Create, Rename, Delete, Add, Remove, Move up/down, drag-and-drop reorder, **«Почати/продовжити список»**.
- Behavioral tests для кожної user action.
- Окремо перевірити keyboard move up/down і observable DnD reorder effect.

## Пакет D — Next in Series / On Deck

Можна вести паралельно з пакетом C, бо це окремий derived-query path.

- Новий Application service: `NextInSeriesService`.
- DTO: `NextInSeriesItemDto`.
- Не створювати recommendation engine.
- Використати існуючі `ReadingProgressRepository`, `BookQueryRepository`, `Book.series`, `sequenceNumber`, `ContinueReadingService`.
- Правила V1:
  1. серія має хоча б одну completed книгу;
  2. якщо в серії є книга з progress `>0 && <100`, серію не показувати в On Deck;
  3. candidate — мінімальний unread valid `sequenceNumber` після останньої completed;
  4. `null` sequence не використовувати для order inference;
  5. ambiguous/duplicate sequence — deterministic tie handling або fail-safe exclusion;
  6. книга не може дублюватися між Continue Reading та On Deck.
- Додати `List<NextInSeriesItemDto> nextInSeries` у `DashboardData`.
- Dashboard UI: секція **«Далі у серіях»**, actions Open / Show series.
- Tests: #1→#2, #1/#2→#3, in-progress hides On Deck, unread series hidden, null sequence hidden, deleted/missing hidden, bounded limit, no duplicate with Continue Reading.
- Query має бути без N+1.

## Пакет E — Cross-cutting closure

Після production implementation, але документацію/registry можна оновлювати паралельно з UI, щоб не накопичувати борг.

Оновити:
- `docs/current/USER-FUNCTIONS-UA.md`;
- `docs/current/UI-ACTION-COVERAGE.json`;
- `docs/current/UI-ACTION-COVERAGE-UA.md`;
- behavioral action coverage registry;
- F1 help topic + UK/EN/BG localization для нового workspace/dialog;
- `ARCHITECTURE.md`, якщо реально змінено boundary/data ownership;
- `docs/current/FEATURES.md`;
- `docs/history/HISTORY-UA.md`;
- current validation / next tasks;
- backup/portable-transfer contract.

## Gate V27

Обов'язково перед видачею наступного checkpoint:

- targeted tests;
- full suites модифікованих модулів;
- UI action inventory;
- action-specific behavioral coverage;
- localization/help checks;
- architecture-check + ArchUnit;
- static release check;
- supply-chain checks;
- full Maven `clean verify`;
- production package;
- clean source ZIP;
- unpacked ZIP self-check;
- SHA-256.

Якщо Dashboard query стане важким — провести same-environment A/B перед індексами/оптимізацією. Локальні тести не заміняють зовнішній Windows/live acceptance V26.

## Definition of Done V27

- Reading Lists: повний CRUD + deterministic reorder.
- Membership і порядок переживають restart.
- Backup/restore зберігає membership і порядок.
- 100% нових user actions мають behavioral evidence.
- Groups/Smart Collections не змінюються від Reading Lists operations.
- Delete Reading List не видаляє book records/files.
- On Deck коректний, bounded, без N+1 і без дублювання Continue Reading.
- Жодної нової persistence для On Deck.
- Немає нових architecture violations.
- Released migrations не змінені.
- Full clean verify green.
- Clean ZIP + updated docs/history/next-tasks + SHA-256.

## Рекомендований порядок паралельної роботи

**Хвиля 1:** characterization + RED contracts Reading Lists + RED contracts On Deck.  
**Хвиля 2:** repository/migration + NextInSeriesService паралельно.  
**Хвиля 3:** portable transfer + Reading Lists UI + Dashboard On Deck паралельно.  
**Хвиля 4:** behavioral coverage + docs/help/localization + query-plan check.  
**Хвиля 5:** downstream/full gates, clean package, SHA-256.

## Не робити в цьому checkpoint

- V28 reader profiles;
- PWA/Kobo/updater/Readwise/local recommendations/comic duplicate pages;
- generic recommendation/integration framework;
- floating-point reorder keys;
- title-based restore matching;
- persistence для On Deck;
- speculative performance tuning без A/B.
