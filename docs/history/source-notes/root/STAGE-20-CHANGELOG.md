# Stage 20 Changelog — Reader engine quality

Date: 2026-08-25

## Implemented

- `hyphenation=true` now affects actual text layout. Added bundled language-aware dictionaries for Ukrainian, English, Bulgarian and Russian plus a conservative syllable fallback for unknown words.
- Visual hyphenation does not modify source text offsets, preserving bookmarks, search results and persisted positions.
- Replaced JavaFX `AnimationTimer`-based position saving with a daemon background autosaver that flushes dirty positions every 3 seconds and performs a final synchronous flush on normal close/dispose.
- Refined EPUB3 nav and EPUB2 NCX handling so `chapter.xhtml#fragment` targets resolve to the exact element text offset instead of only the start of the spine document.
- Added Shift+drag current-page text selection with visual highlight and Ctrl+C clipboard copy. Existing swipe/tap navigation remains unchanged when Shift is not held.
- Added layout regression fixture for dictionary-driven hyphenation and source-offset stability.
- Added EPUB fragment-anchor regression fixture.
- Added large FB2 and EPUB performance guardrail tests using multi-megabyte synthetic documents; parsers must finish within a generous 15-second test threshold without DOM-style loading.
- Updated README and architecture documentation for the settings/autosave/hyphenation pipeline.

## Safety/performance decisions

- Hyphenation dictionaries are small bundled resources and are cached by language.
- The fallback hyphenator is deliberately conservative; it will prefer no split over an unsafe one.
- Selection is page-local and does not build a whole-book selection model in memory.
- Position persistence is transaction-backed by the existing reading-progress repository; an unexpected hard process kill can lose only the most recent short autosave interval.
