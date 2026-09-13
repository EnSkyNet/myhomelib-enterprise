# MyHomeLib — Iteration 23: multi-artifact + duplicate foundation (WIP)

Дата checkpoint: 07.09.2026
База: Iteration 22 — Windows host/session binding WIP
Гілка робіт: 7.2, без зміни external acceptance статусів 7.1 Final.

## Статус

Локально завершено:

- MHL-101 — Multi-artifact book: доменна модель.
- MHL-102 — UI вибору формату/артефакту.
- MHL-103 — міграції для multi-artifact semantics.
- MHL-104 — exact duplicate scanner.

В роботі:

- MHL-105 — fuzzy duplicate detection: scoring/evaluation готові; user-facing duplicate review із reason/score ще не інтегрований.

External 7.1 Final items MHL-010/MHL-011/MHL-012/MHL-017/MHL-018/MHL-019 залишаються відкритими до фактичного GitHub/Windows consolidated PASS.

## MHL-101 — multi-artifact domain/application model

Реалізовано:

- `BookArtifact` та `BookArtifactState`;
- `Book` містить повний набір artifacts і `preferredArtifactId`;
- legacy `Book.file` збережено як backward-compatible operational projection;
- add/remove/select preferred operations не видаляють логічну книгу;
- `BookDto` повертає `artifacts` + `preferredArtifactId`, старі storage fields не прибрані;
- SQLite repository гідрує `book_artifacts` + `book_artifact_metadata`.

Acceptance покриває книгу з EPUB + FB2 + PDF та видалення одного representation без видалення книги.

## MHL-102 — artifact selection UI

Book details показує:

- формат/representation badges;
- preferred representation;
- source/location/state;
- `Зробити основним`;
- `Відкрити як`.

Критична семантика:

- `Відкрити як` відкриває саме вибраний локальний artifact;
- дія не запускає preferred-artifact download guard;
- дія не змінює default representation;
- unavailable/remote-only artifact не маскується під локальний файл.

Targeted UI acceptance: 5 tests, 0 failures/errors, BUILD SUCCESS.

## MHL-103 — V50 multi-artifact persistence

Flyway `V50__multi_artifact_semantics.sql`:

- додає `collection_root`, `folder`, `state` до `book_artifacts`;
- backfill станів `AVAILABLE/REMOTE_ONLY/MISSING`;
- створює legacy artifact для старих books без artifact rows;
- додає `(book_id, artifact_id)` unique contract та state index;
- створює `book_artifact_preferences` із composite FK на artifact тієї самої книги;
- переносить preferred representation зі старої `books.*` проєкції;
- зберігає backward-compatible `books.file_name/folder/archive_entry/...` projection.

Migration acceptance:

- 10 000 logical books;
- 30 000 artifacts;
- після V49 -> V50: 10 000 books, 30 000 artifacts, 10 000 preferred mappings;
- representative states `AVAILABLE`, `REMOTE_ONLY`, `MISSING` збережені;
- видалення одного artifact не видаляє logical book.

### Rollback strategy V50/V51

SQLite/Flyway migrations залишаються forward-only: destructive DOWN migration не використовується.

Перед production upgrade потрібен валідний pre-migration backup collection DB. Якщо після V50/V51 потрібен rollback:

1. зупинити MyHomeLib для цієї collection;
2. не редагувати `flyway_schema_history` вручну;
3. відкласти post-migration DB як diagnostic artifact;
4. відновити повний pre-migration DB backup;
5. перевірити `PRAGMA integrity_check`/application integrity gate;
6. запускати попередню application version лише на відновленій DB.

V50/V51 є additive/backward-oriented, але стару binary version не слід запускати на migrated DB як механізм rollback. Джерело rollback — повний DB restore.

## MHL-104 — restart-safe exact duplicate scanner

Реалізовано:

- SHA-256 повного content stream;
- async execution через `ExecutorPort`, без hashing на caller/UI thread;
- snapshot-based durable queue;
- pause/resume із тим самим scan id;
- V51 tables `artifact_duplicate_scans` + `artifact_duplicate_scan_queue`;
- artifact, доданий після start, не потрапляє у поточний snapshot, але потрапляє в наступний scan;
- cancellation посеред hashing не маркує artifact як оброблений — він залишається pending;
- artifact, видалений після snapshot, автоматично фіксується як skipped, тому scan може завершитися;
- duplicate groups будуються за фактичним SHA-256.

Окремий benchmark: 10 000 in-memory artifacts × 4 KiB; останній full regression run показав приблизно 31 466 artifacts/s. Це regression benchmark поточного Linux/JDK test environment, а не production SLA.

## MHL-105 — fuzzy duplicate scoring foundation

Реалізовано:

- exact ISBN пріоритет;
- normalized title/author matching;
- edit similarity + token Jaccard;
- configurable score threshold;
- `FuzzyDuplicateMatch.score`;
- explainable `reasons` (`ISBN_EXACT`, `TITLE_*`, `AUTHOR_*`, `YEAR_MATCH`);
- detector нічого не merge-ить і не мутує автоматично.

Evaluation corpus: 16 labeled pairs, `tp=8`, `fp=0`, `fn=0`, precision=1.000, recall=1.000.

MHL-105 НЕ закрито: наступний крок — інтегрувати score/reasons у реальний duplicate-review UI, а не лише application model/test corpus.

## Regression evidence

Після migration/resume fixes:

- targeted V50/V51 + scan resume: 4 tests, 0 failures/errors, BUILD SUCCESS;
- application/reader/UI reactor: BUILD SUCCESS;
- infrastructure + UI reactor: усі 7 reactor modules SUCCESS, BUILD SUCCESS;
- infrastructure module: SUCCESS;
- UI module: 46 tests, 0 failures/errors;
- MHL-105 evaluation виконується всередині full regression і лишається 1.000 / 1.000 на зафіксованому corpus.

## Real INPX evidence

`flibusta_online_fb2.inpx`:

- 707 154 / 707 154 records imported;
- 0 import errors;
- ~96.9 s import phase;
- ~7 297 records/s;
- SHA-256: `75bebb7a7ccf203bd934ef2af986f17d737ba4c4abfc277956f60bb84a6c7655`.

Це Linux/JDK 21 local evidence і не замінює Windows/GitHub 7.1 release acceptance.

## Що далі

1. MHL-105: user-facing reason/score у duplicate review.
2. MHL-106: side-by-side Merge Books UI, metadata choice, user-data preservation, explicit physical-file policy, undo.
3. Після merge flow — MHL-107 MetadataProvider SPI.
4. 7.1 external acceptance вести тільки за `CONTINUATION-ITERATION-22-FINAL-LIVE-ACCEPTANCE.md` та `ITERATION-22-WINDOWS-HOST-SESSION-BINDING-WIP.md` до реального PASS.
