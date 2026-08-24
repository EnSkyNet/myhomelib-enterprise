# Navigation Facets — Stage 3

Date: 24.08.2026

## Scope

Stage 3 adds three catalogue navigation dimensions without creating new JavaFX
workspace types: **Years**, **Languages** and **Archives**.

## Data flow

```text
NavigationPanelController
        |
        v
NavigationQueryService
        |
        v
NavigationFacetRepository
        |
        v
SQLite GROUP BY (year / language / archive container)
        |
        v
NavigationNodeDto + bookCount
        | selection
        v
WorkspaceManager -> book-table.fxml -> BookLoaderService -> BookQuery
```

The navigation list is therefore O(number of distinct facets) at the application
boundary instead of O(number of books).

## Years

Only active books with a positive non-null year are exposed. Nodes contain the
exact count and are ordered newest-first. Selecting a year creates a paginated
`BookQuery.year` filter.

## Languages

The SQLite adapter groups language values case-insensitively. The application
normalizes them through the existing `LanguageCode` value object and ignores
invalid legacy values rather than exposing navigation entries that the rest of
the domain cannot query safely. The JavaFX adapter renders the language name in
the current UI locale plus its code.

## Archives

An archive facet means a physical container that actually owns one or more
`archive_entry` rows. The stable identity is the pair:

```text
(collectionRoot, archivePath)
```

`ArchiveNavigationKey` encodes this pair for navigation history. Duplicate file
names are disambiguated with their parent directory. The matching `BookQuery`
requires a non-empty `archive_entry` and normalized container path, so a normal
file with the same path text cannot leak into the result.

## UX details

The existing mode ComboBox automatically exposes all three modes. When the user
changes mode, the alphabet selection resets to `*`; this prevents an old author
letter filter from hiding numeric years or unrelated archive/language entries.
Text filtering remains available.

## Stage boundary

Stage 3 intentionally does **not** implement Keywords/Groups/Reviews (Stage 4),
History/Recent/AlreadyRead (Stage 5), or the unified filter engine (Stage 8).
