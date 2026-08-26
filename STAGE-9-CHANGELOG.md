# Stage 9 — Table Profiles + Quick Filters — Changelog

Дата: 2026-08-25

## Реалізовано

- Persisted table profile per workspace/view: width/order/visibility/sort.
- Окремі profiles для series, genre, year, language, archive, keyword, group, reviews, all-books, already-read, history.
- Quick filter UI з полями: all/title/author/series/genre/keyword/publisher/file.
- Active filter indicator + filtered total count.
- Column chooser + JavaFX table menu button + reset profile.
- Page size control і stable server-side paging.
- Supported table sorting перенесено в SQL `ORDER BY`; JavaFX більше не сортує лише поточну сторінку.
- Stable `b.id` tie-break для offset paging.
- Author sort використовує indexed `books.author_sort`.
- `SeriesGrouping` вставляє presentation headers без зміни SQL result order.

## Data-layer correctness

- V33 backfill `books.format` та `books.author_sort` для існуючих каталогів.
- `BookQueries`, `BookBatchWriter`, `SqliteBookCommandRepository` пишуть обидва поля для normal save/update.
- `updateStorage()` синхронізує `format` після зміни локального файлу.
- Fast INPX `JdbcBatchWriter` тепер пише `format` і `author_sort` без повного author cache / post-import table scan.
- Remote INPX UPSERT зберігає `format` локального downloaded file, якщо `books.local=1`.
- Legacy HLC2 migration виконує bounded post-link refresh denormalized fields, тому attach після V33 не залишає їх порожніми.
