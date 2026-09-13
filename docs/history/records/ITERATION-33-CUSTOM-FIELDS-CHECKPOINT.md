# MyHomeLib — Iteration 33 checkpoint: MHL-112 acceptance debt + MHL-115 Custom fields foundation

**Дата:** 09.09.2026  
**База:** Iteration 32 Smart Collections v1

## Реалізовано

### MHL-112 acceptance debt
- Restart regression для shared SQLite operation history.
- Reverse-order guard regression: undo дозволений лише для newest undoable operation.
- Retention boundary regression до 200 записів зі збереженням newest undoable operation.
- Bulk undo current-state guard лишається в production path: undo відмовляється перезаписувати книгу, якщо metadata змінилися після batch operation.
- BOOK_MERGE integration regression підтверджує відновлення logical-book/artifact/user-state relations; `MergeBooksUseCase` синхронізує обидва search documents після merge/undo.

### MHL-115 Custom fields foundation
- Domain: `CustomFieldDefinition`, `CustomFieldType`, `CustomFieldValue`, explicit delete policy.
- Типи: TEXT / NUMBER / BOOL / DATE / ENUM.
- SQLite migration `V55__custom_fields.sql`: definitions, values, FK, indexes.
- Application port/service; UI не звертається напряму до infrastructure або non-value domain custom-field models.
- SQLite adapter: create/edit/delete, typed validation, enum policy, restart-safe values.
- Lucene schema marker `custom-fields-v1`; typed mapping для text/exact/number/bool/date.
- `SearchRequest` підтримує custom-field filters через Lucene query path без full catalogue materialization.
- Portable user-data schema v3: definitions/values export/restore; schema v2 manifests залишаються readable.
- UI: керування definitions та редагування значень вибраної книги; rebuild/index work запускається через application/background path.

## Фінальна перевірка
- `mvn -DskipTests test-compile`: **BUILD SUCCESS**, усі 13 reactor modules.
- Final targeted regression: **29 tests**, **0 failures**, **0 errors**:
  - MHL-112 merge/undo/history: 5 tests;
  - MHL-115 SQLite/Lucene/backup: 12 tests;
  - ArchUnit layer architecture: 12 tests.
- UI wiring regression: **2 tests**, **0 failures**, **0 errors**.
- Релевантні static gates: **PASS** — architecture, implementation completeness, critical UI localization, Lucene audit, Stage 22 versioned user-data, UI reachability, user-data consistency, user-state/search-index consistency.
- Повний монолітний `mvn test` був запущений після змін: shared/domain/application та значна частина infrastructure проходили без помилок, але процес було припинено серед Lucene lifecycle tests через ліміт часу виконання. Це **не рахується як повний green full-suite**; тому результат підтверджено окремими цільовими та архітектурними прогонами вище.

## Логи
`verification/iteration33/logs/` містить compile, targeted regression, MHL-112, UI та monolithic test logs.
