# Stage 8 — Unified Filter Engine — Changelog

Дата: 2026-08-25

## Реалізовано

- Додано application-level `BookFilterSpec` з persisted global state (`BookFilterStateService`).
- Підтримані критерії: language, year range, format, local/online, read/unread, rating min/max, hide-unrated, quick field/value.
- Додано AND/OR semantics всередині unified filter group.
- `BookQuery` та `SearchRequest` отримали `filterSpec`.
- SQLite table queries і navigation facets використовують один `BookFilterSqlAdapter`.
- Authors/Series/Genres/Year/Language/Archive/Keywords/Groups/Reviews/AllBooks/AlreadyRead/History counts враховують active filter.
- AUTHORS залишився large-library safe: initial-scoped aggregated SQL, без `SELECT * FROM authors`.
- Lucene отримав ті самі structured filter fields: language/year/format/local/read/rating/unrated + quick filter.
- Blank Lucene query дозволений, якщо активний structured filter.
- Search cache key включає serialized filter state.
- Quick filter виконує safe literal substring matching: SQL escaping `%/_/\\`, Lucene escaping wildcard syntax; multi-token input має однакову all-token semantics.
- Зміна global filter автоматично refresh-ить navigation facets і повторно визначає першу доступну літеру AUTHORS.
- Додані локалізаційні ключі uk/en/bg у зовнішні та bundled language catalogs.

## Додаткові виправлення, знайдені під час Stage 8

- Виявлено, що V18 створив `books.format`/`books.author_sort`, але поля не підтримувались усіма write paths.
- Додано V33 backfill та author-name update trigger; повне write-path виправлення завершене разом зі Stage 9.
