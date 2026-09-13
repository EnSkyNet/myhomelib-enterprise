# Stage 3 — Year / Language / Archive Navigation

Date: 24.08.2026
Base: `myhomelib-enterprise-stage-2-navigation-core.zip`

## Scope

This stage implements only roadmap item **3 — Year / Language / Archive
navigation**. It does not start Stage 4 Keywords/Groups/Reviews navigation.

## Added

### Navigation modes

`NavigationMode` now contains seven modes:

- `AUTHORS`
- `SERIES`
- `GENRES`
- `YEARS`
- `LANGUAGES`
- `ARCHIVES`
- `ALL_BOOKS`

The existing JavaFX mode ComboBox exposes the new modes automatically.

### Database-side navigation facets

Added application output port `NavigationFacetRepository` and SQLite adapter
`SqliteNavigationFacetRepository`.

Year, language and archive lists are built with SQL `GROUP BY` queries. The
navigation service never scans/materializes the entire book catalogue to build
these facets.

Every Stage 3 node includes its exact active-book count.

### Year navigation

- excludes deleted books;
- excludes missing/non-positive years;
- orders years newest-first;
- selecting a year opens the standard paginated book table;
- `BookQuery.year` is a first-class filter and survives page changes.

### Language navigation

- groups persisted language codes case-insensitively;
- normalizes codes through the existing `LanguageCode` value object;
- ignores invalid legacy codes that the domain cannot safely query;
- renders language names using the current UI locale plus the stable code;
- selecting a language uses the existing paginated `BookQuery.language` path;
- language filtering is now case-insensitive in SQLite.

### Archive navigation

A navigation archive means a physical container with non-empty
`archive_entry` rows.

Added `ArchiveNavigationKey`, whose stable identity is:

`collectionRoot + archivePath`

The pair is Base64-url encoded for workspace history so archives with the same
file name in different locations are not mixed.

Duplicate archive file names are disambiguated with the parent directory in the
visible navigation label. Archive selection uses first-class
`BookQuery.archiveCollectionRoot/archivePath` filters and remains paginated.

### History / workspace integration

`WorkspaceManager` now restores:

- `year`
- `language`
- `archive`

entries via Back/Forward using the same book-table workspace.

### Navigation filter UX

Changing navigation mode resets the alphabet selection to `*`. This prevents a
letter selected in Authors/Series/Genres from accidentally hiding numeric years
or unrelated archive/language nodes.

### Tests and guards

Added:

- `ArchiveNavigationKeyTest`
- Stage 3 cases in `DefaultNavigationQueryServiceTest`
- `BookQueryBuilderStage3Test`
- `tools/stage3-navigation-check.py`
- Stage 3 invariants in `tools/architecture-check.py`

## Localization

Added built-in translations for:

- `Роки`
- `Мови`
- `Архіви`

All bundled and external default language catalogues now contain **147 keys**.

## Architecture impact

No new UI architecture debt was introduced.

Ratchet remains:

- direct UI output-port users: **18 / 18**
- UI non-value domain-model users: **28 / 28**

## Documentation

Updated:

- `ARCHITECTURE.md`
- `docs/architecture/NAVIGATION_CORE.md`
- `docs/architecture/ARCHITECTURE_DEBT.md`

Added:

- `docs/architecture/NAVIGATION_FACETS_STAGE3.md`
- `STAGE-3-CHANGELOG.md`
- `STAGE-3-VALIDATION.md`
