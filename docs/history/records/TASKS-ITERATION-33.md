# MyHomeLib — що продовжувати після Iteration 32

**Дата плану:** 08.09.2026  
**База:** Iteration 32 Smart Collections v1

## 0. Короткий acceptance debt — MHL-112

Перед або паралельно з основним Iteration 33 додати прямий integration regression для shared operation history/undo:

1. SQLite restart: запис reversible operation → reopen collection DB → `latestUndoable()` повертає ту саму операцію.
2. Reverse-order guard: старішу операцію не можна undo, поки існує новіша reversible operation.
3. Bulk undo current-state guard: зовнішня зміна metadata після batch не повинна бути затерта undo.
4. BOOK_MERGE undo: перевірити відновлення logical-book/artifact зв’язків та search-index synchronization.
5. Retention 1..200: prune не видаляє активну/новішу історію помилково.

Після цього MHL-112 можна перевести з **«В роботі»** у **«Виконано»**.

## 1. Основна Iteration 33 — MHL-115 Custom fields foundation (13 SP)

### Scope з backlog

- Custom field definitions і values.
- Типи: **text / number / bool / date / enum**.
- Створення/редагування/видалення definition з чіткою policy для вже заповнених values.
- Значення індексуються та доступні для filtering/search.
- Backup/restore зберігає definitions і values.
- UI для керування полями і редагування значень книги.

### Рекомендований технічний порядок

1. Domain: `CustomFieldDefinition`, type enum, validation і delete policy.
2. DB migration V55: definitions + values, FK/cascade policy, indexes.
3. Application ports/use cases без прямого UI→infrastructure/domain debt.
4. SQLite adapter + migration matrix + backup/export/import version bump.
5. Lucene mapping/filter path для text/number/bool/date/enum; schema marker bump.
6. UI: custom-fields settings + book editor controls.
7. Tests: domain validation, SQLite round-trip/migration, backup, Lucene filtering, UI source contract.
8. Regression: повний `test-compile`, canonical 76 static checks, targeted/full modular tests.

### Acceptance

- Field create/edit/delete policy однозначна й протестована.
- Values round-trip у SQLite після restart.
- Значення filterable через search path без full catalog materialization.
- Backup/restore не губить definitions/values.
- Міграція з V54 → V55 і migration matrix PASS.
- Architecture/localization gates не послаблені.

## 2. Після MHL-115

- **MHL-116 — Artifact integrity/hash audit (8 SP):** incremental exists/size/hash/readable-archive scan, report missing/corrupt/changed, без destructive auto-repair.
- **MHL-117 — Library Health dashboard (5 SP):** KPI missing/corrupt/duplicates/metadata gaps/stale index/backup age, actionable drill-down, async refresh, export report.

## 3. Performance hardening, не підміняти функціональний backlog

Додати окремий reproducible Lucene benchmark для Smart Collections на 500k corpus: cold/warm first page, AND/OR 5/20 rules, numeric range, DocValues sort, bounded maxResults, RSS/heap/GC. До вимірювання не фіксувати вигадані latency SLA як факт.
