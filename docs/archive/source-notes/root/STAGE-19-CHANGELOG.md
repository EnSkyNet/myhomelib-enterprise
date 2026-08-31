# Stage 19 Changelog — Reader settings UX (AlReader-like)

Date: 2026-08-25

## Implemented

- Replaced the single long Reader settings grid with categorized tabs: Typography, Colors, Layout, Navigation and Status.
- Added built-in presets: Standard, Comfortable, Compact and Night.
- Added live preview while the dialog is open. Cancel restores the exact settings that were active when the dialog opened.
- Added reset controls per settings category instead of a single destructive all-settings reset.
- Added explicit global-default vs per-book override scope. Existing per-book overrides win until the user explicitly switches the dialog back to global scope.
- Added atomic JSON persistence for per-book Reader settings in `reader-book-preferences.json` through an application service; JavaFX does not write the file directly.
- Added backward-compatible migration of pre-Stage-19 `reader-preferences.json` files so missing status/tap fields get safe defaults instead of Java primitive zero-values.
- Added configurable left/center/right tap-zone actions: previous/next page, previous/next chapter, toggle toolbar, search or none.
- Added a dedicated Reader bottom status bar with independent visibility controls for progress, chapter and page information.
- Existing quick toolbar changes persist in the same global/per-book scope as the active Reader settings.

## Compatibility

- Existing Reader JSON preferences remain readable.
- Default tap behavior remains left=previous page, center=toggle toolbar, right=next page.
- Reader engine stays persistence-agnostic; global/per-book resolution is handled in the application layer.
