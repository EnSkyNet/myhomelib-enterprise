# Navigation Metadata/User Data — Stage 4

Date: 24.08.2026

## Scope

Stage 4 adds **Keywords**, **Groups** and **Reviews** to the existing application-level navigation core. It deliberately does not implement Stage 5 Recent/AlreadyRead/History.

## Keywords

The catalogue stores keywords as a flat string. `SqliteNavigationFacetRepository` uses a recursive SQLite CTE to split the common delimiters comma, semicolon and pipe, trims tokens, groups IDs case-insensitively and returns exact active-book counts. The selected token is carried by `BookQuery.keyword`; `BookQueryBuilder` uses the same delimiter semantics and exact case-insensitive token matching, so `space` does not match `spacecraft`.

## Groups / Favorites

`findGroups()` joins `groups`, `book_groups` and active `books`, returning persistent group IDs and counts. Empty groups remain visible. Built-in `Favorites` and `To Read` names are localized at presentation time without changing their persisted names. Selecting a group opens the standard paginated table with `BookQuery.groupId`.

`GroupRepository.findByBookId()` plus `LoadBookGroupsUseCase` provides membership data to the details pane without adding a UI -> output-port dependency.

## Reviews

`ReviewNavigationFilter` defines three stable subsets:

- `rated`: user rating greater than zero;
- `reviewed`: non-blank user review;
- `rated-reviewed`: both conditions.

The corresponding `BookQuery.onlyRated` / `onlyReviewed` flags compose naturally, including the intersection.

## Details deep links

`details.fxml` now shows lightweight link rows for keywords, group memberships and the applicable review subsets. A deep link switches the sidebar to the matching mode, clears an incompatible alphabet/text sidebar filter, selects the stable node without invoking navigation a second time, and opens the normal paginated book-table workspace.

## History

Stage 4 workspaces are restorable by the existing workspace Back/Forward mechanism:

- `keyword` -> keyword text;
- `group-nav` -> persistent numeric group ID;
- `reviews` -> `ReviewNavigationFilter.id()`.

## Non-goals

- no Recent/AlreadyRead/History navigation (Stage 5);
- no unified cross-screen filter engine (Stage 8);
- no rich annotation redesign (Stage 10);
- no schema migration is required because keywords, ratings, reviews, groups and memberships already exist.
