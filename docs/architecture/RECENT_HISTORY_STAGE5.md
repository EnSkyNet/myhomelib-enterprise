# Recent / AlreadyRead / History — Stage 5

Date: 24.08.2026

## Scope

Stage 5 exposes reading history that previously existed only indirectly through reader progress/statistics. It adds Recent, AlreadyRead and History UX without changing the reader engine or starting the Stage 6 online-revision model.

## Data model

Flyway `V30__create_reading_history.sql` creates:

```text
reading_history
  book_id         PK/FK -> books.id
  last_opened_at  timestamp text
  open_count      >= 1
```

The migration backfills one row per book from the newest available `reading_stats.last_read_at` or `reading_progress.updated_at` value. This preserves useful history for upgraded collections.

The table is intentionally separate from `reading_progress`. The **Clear reading history** action deletes only `reading_history`; it does not delete resume position, bookmarks, ratings, reviews or the `books.progress = 100` read marker.

## Application boundary

`ReadingHistoryPort` owns persistence operations:

- recent entries with timestamp;
- active-history count;
- successful Reader-open UPSERT;
- clear history.

`ReadingHistoryService` maps history entries to `BookDto` while preserving history order. JavaFX depends on this application service instead of adding a new direct UI -> output-port dependency.

## Navigation modes

`NavigationMode` now includes:

- `ALREADY_READ` — synthetic node with exact count from `BookQuery.onlyRead(true)`;
- `HISTORY` — synthetic node with count from `ReadingHistoryPort`.

Both modes open the existing `book-table.fxml` workspace. `BookQuery.onlyInHistory` joins the dedicated history table, and `BookQueryBuilder` orders History by `last_opened_at DESC` with a stable book-ID tie-break. Pagination preserves the history filter.

## Recent menu

The View menu contains **Recent books**. It is rebuilt whenever opened, contains up to 12 items, renders `title — dd.MM.yyyy HH:mm`, and resumes the selected book in the built-in Reader. An empty disabled item is shown when there is no history.

A history row is recorded only after `NewReaderWorkspaceController.openBook(...)` succeeds, so missing/corrupt books do not become false Recent entries.

## Back / Forward

`WorkspaceManager` can restore `already-read` and `history` entries through the existing Back/Forward stacks. The toolbar buttons keep their existing enable/disable semantics, and `Alt+Left` / `Alt+Right` trigger the same `NavigationHistoryService` methods.

## Clear history

The View menu exposes **Clear reading history** with confirmation. On success it:

1. clears only `reading_history`;
2. refreshes navigation counts;
3. rebuilds the Recent menu;
4. reloads the History workspace when it is active.

This operation does not alter AlreadyRead state or reader resume data.
