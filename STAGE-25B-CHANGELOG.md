# Stage 25B Changelog — Reader Internals Targeted Refactor

## Scope

Stage 25B is a behaviour-preserving refactor of the three reader hot spots identified by the roadmap: `ReaderCanvas`, `TextLayoutEngine` and `Fb2StreamingParser`. It is performed after Stage 24 so all changes are protected by the established reader/performance baselines.

## ReaderCanvas

- Extracted bounded previous-page state to `ReaderPageHistory`.
- Extracted selection offsets, hit-testing, selection overlay and clipboard copy to `ReaderSelectionController`.
- `ReaderCanvas` remains responsible for JavaFX viewport lifecycle, navigation commands and input routing, but no longer owns selection offset algorithms/history collection internals.
- Source text offsets and Shift-drag/Ctrl+C behaviour are unchanged.
- Size reduced from 772 to 701 lines.

## TextLayoutEngine

- Extracted line breaking, language-aware hyphenation decisions, inline-style lookup, visual run composition, justification/alignment helpers and paragraph/font metrics policy to `TextLineLayoutSupport`.
- `TextLayoutEngine` now orchestrates page/paragraph flow and delegates line-level mechanics.
- `updateSettings()` updates both font metrics and the extracted line support atomically.
- Visual hyphenation still never mutates source text offsets.
- Size reduced from 638 to 366 lines.

## Fb2StreamingParser

- Extracted low-level token/text utilities to `Fb2ParseSupport`:
  - safe element text;
  - element-name normalization;
  - paragraph/inline style resolution;
  - nested bold+italic composition;
  - whitespace normalization;
  - author/title normalization;
  - temporary writer/file cleanup.
- Streaming/resource/chapter state remains in `Fb2StreamingParser`; no DOM/full-file buffering was introduced.
- Size reduced from 738 to 600 lines.

## Regression protection

Added:

- `ReaderPageHistoryTest`;
- `TextLineLayoutSupportTest`;
- `Fb2ParseSupportTest`;
- `tools/stage25b-reader-refactor-check.py`, including portable `javac`/runtime smoke checks for extracted pure-JDK helpers.

Updated the Stage 19/20 guard so it checks the refactored implementation by architectural responsibility rather than requiring hyphenation/clipboard code to remain physically inside the former large classes.

No reader feature or format contract was intentionally changed by Stage 25B.
