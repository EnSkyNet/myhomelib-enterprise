# V31 — атомарне завершення журналу Undo

## Причина зміни

V30 захищала кожен рядок `library_organization_items` умовним SQL CAS, але фінальне `requireJournalSetFullyRolledBack()` було окремим читанням перед безумовним `updateOperation(..., ROLLED_BACK, ...)`. Конкурентний записувач міг додати/підмінити елемент або змінити стан операції між цими командами. Звичайний Java lock не захищає від зовнішнього SQLite-записувача.

## Контракт V31

- `OrganizationJournalPort.finalizeRollbackIfUnchanged(expectedOperation, expectedItemIds, summaryJson)` має виконувати порівняння й перехід стану **атомарно**. Базова реалізація кидає `UnsupportedOperationException` (fail closed).
- `SqliteOrganizationJournalAdapter` виконує один параметризований `UPDATE` з умовами на повний знімок рядка `library_organization_operations`, кількість рядків книг, припустимість станів `ROLLED_BACK` / `SKIPPED`, а також точний набір UUID елементів через SQLite `json_each(?)`.
- Результат `false` означає, що операцію **не** позначено як `ROLLED_BACK`. Вимога унікальних UUID перевіряється до SQL. Якщо фіналізація не вдалася, Workspace не видаляється.
- Нових SQL-міграцій немає. Формат crash-manifest не змінюється. Для виконання потрібен SQLite з JSON1 (у перевіреному офлайновому JDBC SQLite тест пройшов).

## Межі доказів

Цей механізм не створює атомарної транзакції між фізичними переміщеннями файлів і SQLite. Якщо конкурентний записувач змінить запис після файлового кроку або фізичне I/O зависне, потрібні recovery/manual review. SQLite CAS не усуває зависання NTFS/UNC, не замінює тест Windows DPAPI або фактичне вимкнення живлення. Не змінювати робочу `Flib.db` для тестів.
