# Stage 2 — Navigation Core

Date: 24.08.2026
Base: `myhomelib-enterprise-stage-1-architecture-baseline.zip`

## Scope

This stage implements only the roadmap item **Navigation Core**. It does not
start Stage 3 year/language/archive navigation.

## Added

### Application navigation API

- `NavigationMode`
  - `AUTHORS`
  - `SERIES`
  - `GENRES`
  - `ALL_BOOKS`
- `NavigationNodeDto`
- `NavigationQueryService`
- `DefaultNavigationQueryService`

The application layer now owns catalogue access, stable identifiers and node
ordering. JavaFX receives UI-neutral navigation nodes.

### All Books mode

The navigation panel now exposes `ALL_BOOKS` as a first-class mode. Its node
contains the exact active-book count from `BookQueryRepository.count(...)`.
Selecting it opens the paginated standard book table.

### Navigation tests

Added tests for:

- stable/sorted author nodes;
- persisted series IDs;
- stable genre codes/order;
- all-books count;
- post-import series synchronization delegation.

### Architecture guard

Stage 2 navigation invariants were added to `tools/architecture-check.py` and
ArchUnit rules. The UI architecture ratchet was tightened from:

- output-port users: **19 -> 18**;
- non-value domain-model users: **29 -> 28**.

`NavigationPanelController` may no longer regress into either debt category.

## Changed

### `NavigationPanelController`

The controller no longer:

- injects `DictionaryCachePort`;
- injects `LoadNavigationDataUseCase`;
- renders `Author`, `Series` or `Genre` entities;
- declares its own `NavigationMode`;
- generates `SeriesId` values in the UI.

It now:

- injects `NavigationQueryService`;
- renders `NavigationNodeDto`;
- uses one scalable navigation-mode `ComboBox`;
- keeps alphabet/text filtering as presentation logic;
- rejects stale asynchronous responses with a monotonic load generation.

### Stable series identity

The old series navigation created random `SeriesId.generate()` identifiers from
distinct series names. Stage 2 reads persisted `SeriesId` values from
`SeriesRepository.findAll()`.

### Series synchronization fix

`SyncSeriesUseCase.execute()` previously logged success without calling the
repository. It now calls `seriesRepository.syncSeriesFromBooks()`. This is
required so newly imported series receive persisted IDs before navigation
refreshes.

### Book-list navigation

Series and genre navigation now use the existing paginated `book-table.fxml`
workspace and normal `BookQuery` filters instead of materializing large
in-memory search-result lists.

`WorkspaceManager` now supports an `all-books` history entry as well.

### Legacy navigation API removed

Removed unused parallel navigation state/API:

- `LoadNavigationDataUseCase`
- `NavigationDataDto`
- `NavigationViewModel`

### Localization

Added three navigation strings to every built-in catalogue and first-run
bundled copy:

- `Навігація`
- `Режим навігації`
- `Усі книги`

Built-in catalogues now contain **144 keys each**.

## Documentation

Updated:

- `ARCHITECTURE.md`
- `docs/architecture/ARCHITECTURE_DEBT.md`

Added:

- `docs/architecture/NAVIGATION_CORE.md`
- `STAGE-2-CHANGELOG.md`
- `STAGE-2-VALIDATION.md`
