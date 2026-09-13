# MyHomeLib — Iteration 24 duplicate review + merge/undo checkpoint

Дата checkpoint: 07.09.2026

## Scope

Завершено локально:

- MHL-105 — fuzzy duplicate detection + user-visible duplicate review;
- MHL-106 — Merge Books UI + transaction-safe undo.

MHL-101/MHL-102/MHL-103/MHL-104 залишаються завершеними з Iteration 23.

External 7.1 Final items MHL-010/MHL-011/MHL-012/MHL-017/MHL-018/MHL-019 не змінювалися: без фактичного GitHub/Windows consolidated PASS вони залишаються відкритими.

## MHL-105 — bounded fuzzy duplicate review

Реалізовано:

- `FuzzyDuplicateCandidateLookup` для bounded candidate retrieval замість O(N^2) full-library pair scan;
- блокування кандидатів за exact ISBN / normalized author + title token;
- жорсткий candidate limit перед fuzzy scoring;
- `ReviewBookDuplicatesUseCase` повертає user-facing suggestion model;
- для кожної пропозиції доступні score, explainable reasons та artifacts обох книг;
- UI показує score/reasons, формати й artifact state;
- автоматичного merge немає.

Evaluation corpus лишається conservative:

- tp=8;
- fp=0;
- fn=0;
- precision=1.000;
- recall=1.000.

Ці значення є regression evidence для зафіксованого test corpus, а не універсальною production-метрикою для довільних бібліотек.

## MHL-104 production consumer

Exact duplicate scanner тепер має production UI entrypoint у duplicate workspace:

- SHA-256 scan запускається поза UI thread через managed executor;
- показуються progress та duplicate groups;
- cancel/resume використовує durable V51 snapshot queue;
- `REMOTE_ONLY`/missing artifacts не маскуються під локальні файли;
- scan result не виконує destructive action автоматично.

## MHL-106 — Merge Books + undo

Application contract:

- explicit survivor book;
- explicit metadata source;
- merge plan не видаляє фізичні файли;
- Lucene refresh виконується після committed DB mutation як derived-state step.

Persistence:

- Flyway `V52__undoable_book_merge_journal.sql`;
- durable merge journal;
- logical duplicate soft-delete;
- artifact/user-data relations консолідуються в одній SQLite transaction;
- authors/genres/groups/keywords/bookmarks зберігаються за union/preservation policy;
- reading state/history/statistics мають deterministic merge policy;
- undo відновлює вихідні книги та перенесені relations за journal snapshot;
- physical filesystem delete відсутній у merge/undo adapter.

UI:

- side-by-side duplicate comparison;
- користувач явно обирає survivor;
- користувач явно обирає metadata source;
- destructive file deletion не пропонується як implicit merge behavior;
- після merge доступний undo.

## Search-index consistency ratchet

Під час фіналізації Iteration 24 оновлено `tools/user-state-search-index-check.py` під фактичну після-Iteration-23 архітектуру без послаблення семантики:

- `RemoveLocalBookCopyUseCase` використовує `CommittedCatalogMutationService.executeSynchronized(...)`;
- `CommittedCatalogMutationService` планує `SearchIndexSynchronizer.synchronizeAfterCommit(ids)` усередині collection transaction;
- FolderSync INPX має один guarded full-rebuild fallback через `rebuildSafelyNow()`;
- complete bounded INPX changes використовують selective `synchronizeSafelyNow(...)`.

Фінальний user-state/search-index static check: PASS.

## Local regression evidence

Актуальні Surefire reports у checkpoint tree:

- myhomelib-shared: 12 tests;
- myhomelib-domain: 12 tests;
- myhomelib-application: 139 tests, 1 skipped;
- myhomelib-infrastructure: 282 tests, 6 skipped;
- myhomelib-reader: 40 tests;
- myhomelib-ui: 48 tests;
- myhomelib-opds: 14 tests;
- myhomelib-bootstrap: 15 tests;
- myhomelib-mcp: 6 tests;
- myhomelib-architecture-tests: 12 tests;
- myhomelib-e2e-tests: 10 tests;
- myhomelib-benchmark: 1 test, 1 skipped.

Разом у поточних reports: **591 tests, 0 failures, 0 errors, 8 skipped**.

Завершені split-reactor runs після Iteration 24 змін дали BUILD SUCCESS. Спроба одного суцільного 13-module `mvn test` повторно перевищила доступне foreground execution window на OPDS-етапі, тому саме цей incomplete run не класифікується як PASS. Це не замінює зовнішні Windows/CI release gates.

## Final local static gates

07.09.2026 повторно виконано:

- `tools/architecture-check.py` — PASS;
- `tools/managed-executor-check.py` — PASS;
- `tools/static_release_check.py` — PASS;
- `tools/supply-chain-policy-check.py` — PASS;
- `tools/implementation-completeness-check.py` — PASS;
- `tools/ui-function-reachability-check.py` — PASS;
- `tools/user-data-consistency-check.py` — PASS;
- `tools/user-state-search-index-check.py` — PASS.

Static release check бачить 52 SQLite migrations, 979 Java source files та 211 test source files; XML/FXML/migration integrity issues = 0.

## External 7.1 gate — без змін

Не переводити у `Виконано` без реального evidence:

- MHL-010;
- MHL-011;
- MHL-012;
- MHL-017;
- MHL-018;
- MHL-019.

Для live acceptance джерелом істини лишається:

- `CONTINUATION-ITERATION-22-FINAL-LIVE-ACCEPTANCE.md`;
- `ITERATION-22-WINDOWS-HOST-SESSION-BINDING-WIP.md`.

## Наступний 7.2 крок

Після цього checkpoint наступна залежність backlog — MHL-107 `MetadataProvider SPI`, потім MHL-108 Open Library provider і MHL-109 Google Books provider.
