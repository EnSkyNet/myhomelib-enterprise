# Stage 21 Changelog — Context help + genre localization

Date: 2026-08-25

## Implemented

- Added central `HelpTopicRegistry` mapping workspaces and dialog contexts to help topics; controllers/workspaces no longer need to know bundled resource paths.
- `HelpService` now prefers localized Markdown help and keeps TXT/HTML plus Ukrainian fallback for compatibility.
- Added 63 bundled Markdown help pages across Ukrainian, English and Bulgarian, including navigation, updates, filters, details, maintenance, actions, OPDS and backup topics.
- Upgraded shipped `Lang/*.json` catalogues to schema version 2 while keeping schema-v1 packs readable with safe fallback.
- Added localized genre display names keyed by stable FB2 genre code. Shipped Ukrainian/English/Bulgarian catalogues contain the same 110 stable genre keys.
- Navigation genre facets and Rich Book Details now localize labels through the stable genre code; database/book-genre relations are not changed when UI language changes.
- Added language catalogue diagnostics for schema compatibility, missing UI keys and missing genre keys. Diagnostics are written to `config/language-diagnostics.txt`.
- Added a Settings action to view language diagnostics directly in the application.
- Future-schema language packs are rejected safely instead of crashing startup; missing keys fall back to the existing UI/DB labels.
- Language-catalog signing remains optional; there is no mandatory signature requirement.
- Updated README/architecture localization/help documentation and added `tools/stage21-help-genre-localization-check.py`.

## Compatibility and safety

- Stable FB2 genre IDs remain authoritative; only presentation labels are localized.
- Existing schema-v1 third-party language packs continue to load with diagnostics and fallback behavior.
- Bundled/root first-run language catalogues are byte-for-byte synchronized by validation.
