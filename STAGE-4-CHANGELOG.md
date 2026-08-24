# Stage 4 — Keywords / Groups / Reviews Navigation

Date: 24.08.2026  
Base: `myhomelib-enterprise-stage-3-year-language-archive-navigation.zip`

## Scope

This stage implements only roadmap item **4 — Navigation: Keywords / Groups / Reviews**.
It does **not** start Stage 5 Recent / AlreadyRead / History.

## Added

### Navigation modes

`NavigationMode` now contains ten modes:

- `AUTHORS`
- `SERIES`
- `GENRES`
- `YEARS`
- `LANGUAGES`
- `ARCHIVES`
- `KEYWORDS`
- `GROUPS`
- `REVIEWS`
- `ALL_BOOKS`

The existing ComboBox-based JavaFX navigation selector exposes the new modes through the same application-level contract.

### Keyword navigation

`NavigationFacetRepository` and `SqliteNavigationFacetRepository` now expose keyword facets.

The existing flat `books.keywords` metadata is split using the common delimiters:

- comma `,`
- semicolon `;`
- pipe `|`

SQLite performs the split with a recursive CTE, trims empty tokens and returns counts for active books only. The catalogue is not materialized in JavaFX to construct this navigation list.

`BookQuery` now has a first-class `keyword` filter. `BookQueryBuilder` applies exact token matching using the same delimiter semantics, so selecting `space` does not accidentally select `spacecraft`.

### Groups / Favorites navigation

Groups are exposed through persistent numeric group IDs and active-book counts.

- all groups remain visible, including empty groups;
- built-in `Favorites` and `To Read` remain persisted under their existing names;
- presentation localizes those built-ins without changing database identity;
- selecting a group uses the existing `BookQuery.groupId` path and standard pagination.

`GroupRepository` now includes `findByBookId`, with SQLite implementation, and new `LoadBookGroupsUseCase` exposes group membership to the UI without adding a direct UI -> output-port dependency.

### Reviews navigation

Added stable application-level `ReviewNavigationFilter` values:

- `rated` — user rating > 0;
- `reviewed` — non-blank user review;
- `rated-reviewed` — both conditions.

`BookQuery` now includes composable `onlyRated` and `onlyReviewed` flags. The review navigation mode presents all three subsets with exact counts.

### Details-pane deep links

`details.fxml` now contains lightweight link rows for:

- keywords;
- group memberships;
- applicable rated/reviewed subsets.

Selecting a link:

1. switches the sidebar to the corresponding navigation mode;
2. clears incompatible sidebar text/alphabet filters;
3. reveals/selects the stable navigation node without firing the navigation callback twice;
4. opens the normal paginated `book-table.fxml` workspace.

This is intentionally a minimal Stage 4 enhancement; the full rich annotation redesign remains Stage 10.

### Workspace history

Back/Forward restoration now understands:

- `keyword` with keyword text;
- `group-nav` with persistent group ID;
- `reviews` with stable `ReviewNavigationFilter.id()`.

### Pagination preservation

`BookLoaderService.withPagination(...)` now preserves:

- `keyword`;
- `onlyRated`;
- `onlyReviewed`;

alongside all Stage 3 filters, so changing pages does not lose the selected Stage 4 subset.

## Tests and guards

Added/updated:

- Stage 4 cases in `DefaultNavigationQueryServiceTest`;
- `BookQueryBuilderStage4Test`;
- `tools/stage4-navigation-check.py`;
- Stage 4 invariants in `tools/architecture-check.py`.

The Stage 4 offline SQLite test validates:

- keyword tokenization/counts;
- exact keyword selection (`space` vs `spacecraft`);
- group/Favorites counts while excluding deleted books;
- empty group visibility;
- Rated / Reviewed / Rated & Reviewed counts.

## Localization

Added built-in translations for:

- `Групи`
- `Відгуки`
- `Обране`
- `До читання`
- `Оцінені`
- `З відгуками`
- `Оцінені з відгуками`

All bundled and external default language catalogues now contain **154 keys**.

## Architecture impact

No new architecture debt was introduced.

Ratchet remains:

- direct UI output-port users: **18 / 18**
- UI non-value domain-model users: **28 / 28**

No schema migration was required because `keywords`, `rate`, `review`, `groups` and `book_groups` already exist in the current database schema.

## Documentation

Updated:

- `ARCHITECTURE.md`
- `docs/architecture/NAVIGATION_CORE.md`

Added:

- `docs/architecture/NAVIGATION_USER_DATA_STAGE4.md`
- `STAGE-4-CHANGELOG.md`
- `STAGE-4-VALIDATION.md`
