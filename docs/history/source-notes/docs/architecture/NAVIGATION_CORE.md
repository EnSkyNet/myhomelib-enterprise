# Navigation Core — Stage 2

Date: 24.08.2026

## Goal

Make catalogue navigation an application capability instead of JavaFX code.
The UI may choose how to display/filter nodes, but it must not query repositories
or manufacture domain identifiers.

## Public application API

- `NavigationMode` — stable list of supported navigation dimensions.
- `NavigationNodeDto` — UI-neutral node (`mode`, stable `id`, `label`, optional
  `bookCount`).
- `NavigationQueryService` — asynchronous query API used by desktop adapters.
- `DefaultNavigationQueryService` — repository-backed implementation.

Stage 2 established the first four modes (`AUTHORS`, `SERIES`, `GENRES`,
`ALL_BOOKS`). Stage 3 added `YEARS`, `LANGUAGES`, `ARCHIVES`; Stage 4 added
`KEYWORDS`, `GROUPS`, `REVIEWS`; Stage 5 adds:

11. `ALREADY_READ`
12. `HISTORY`

No second navigation API was introduced.

## UI contract

`NavigationPanelController` is now a thin adapter:

```text
mode selector
    -> NavigationQueryService.load(mode)
    -> List<NavigationNodeDto>
    -> local text/alphabet filtering
    -> selected NavigationNodeDto
    -> NavigationService
```

The mode selector is a `ComboBox`, intentionally replacing the fixed three
buttons. Adding Stage 3–5 modes therefore does not require redesigning the
navigation panel.

## Stable series identity

The previous navigation panel obtained distinct series names and created a new
`SeriesId.generate()` value for every name on every refresh. Those IDs were UI
artifacts rather than catalogue identity.

Stage 2 uses `SeriesRepository.findAll()` and returns the persisted ID of each
series. Selection can therefore use the normal `BookQuery.seriesId` path and
history restoration remains stable.

## Book-list workspaces

Series, genre and all-books navigation now open the existing paginated
`book-table.fxml` workspace and load through `BookLoaderService`:

- series -> `loadBooksBySeries`
- genre -> `loadBooksByGenre`
- all books -> `loadAllBooks`

This removes the old navigation behavior that materialized up to 10,000 books
only to build an in-memory search result for a selected series.

## Concurrency

Navigation queries are asynchronous. `NavigationPanelController` uses a
monotonic load generation so a slow response from an old mode cannot overwrite
a newer mode after rapid switching.

## Architecture ratchet impact

Stage 1 baseline:

- UI output-port users: 19
- UI non-value domain-model users: 29

Stage 2 baseline:

- UI output-port users: 18
- UI non-value domain-model users: 28

The removed class in both categories is `NavigationPanelController`.

## Stage 3 facet extension

Year/language/archive lists are catalogue facets, not domain aggregate lists.
`NavigationFacetRepository` therefore performs database-side `GROUP BY` queries
and returns only stable keys, display-neutral labels and book counts.

- years are sorted newest-first;
- language codes are normalized through `LanguageCode` and rendered using the
  current UI locale;
- archives are grouped by physical container (`collectionRoot + archivePath`),
  use an encoded `ArchiveNavigationKey` for history, and disambiguate duplicate
  file names with the parent folder.

Selecting any Stage 3 facet opens the same paginated `book-table.fxml` used by
series/genre/all-books. `BookQuery` now has first-class `year`,
`archiveCollectionRoot` and `archivePath` filters.


## Stage 4 metadata/user-data extension

Stage 4 keeps the same `NavigationQueryService` and `book-table.fxml` workflow.
`NavigationFacetRepository` now aggregates keyword, group and review/rating
facets without loading the catalogue into JavaFX.

- keywords are split on the existing flat metadata delimiters `,`, `;` and `|`,
  grouped case-insensitively and selected through an exact-token `BookQuery.keyword`;
- groups use persistent numeric `GroupId` values and include empty groups so
  built-ins such as Favorites/To Read remain discoverable;
- review navigation exposes stable Rated, Reviewed and Rated & Reviewed subsets;
- details-pane hyperlinks can reveal/select the corresponding sidebar node and
  open the same paginated workspace;
- workspace history stores keyword text, group ID or stable review-filter ID.


## Stage 5 Recent / AlreadyRead / History extension

Stage 5 keeps the same navigation contract and adds two synthetic modes:

- `ALREADY_READ` opens the standard paginated table with `BookQuery.onlyRead`;
- `HISTORY` opens the standard paginated table with `BookQuery.onlyInHistory`
  and deterministic `last_opened_at DESC, book_id` ordering.

Recent books are deliberately a menu rather than a separate navigation mode.
The menu is populated on demand from application `ReadingHistoryService` and
shows the last-opened timestamp. Selecting a recent item resumes it in the
built-in Reader.

A dedicated `reading_history` table is separate from `reading_progress`. V30
backfills it from older reading statistics/progress data. Clearing History
deletes only this journal, preserving resume position, bookmarks, ratings and
`progress = 100` AlreadyRead state. Successful Reader opens UPSERT the journal.

Workspace Back/Forward understands both new workspace types. The existing
toolbar controls remain authoritative and `Alt+Left` / `Alt+Right` provide the
keyboard equivalent.
