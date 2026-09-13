# MyHomeLib Enterprise v7.1 — AlReaderX reader gap audit

Status date: 2026-08-30  
Scope: desktop-relevant Reader UX/settings/navigation behavior. Android-only device controls are not emulated.

## Current public reference

The current public AlReaderX listing checked on 2026-08-30 is Google Play package `com.neverland.alreaderext`, last updated 2025-04-27:
`https://play.google.com/store/apps/details?id=com.neverland.alreaderext`

The listing currently advertises, among other items: day/night profiles, custom font/color settings, one/two-page mode with automatic landscape switching, autoscroll, navigation by percentage/pages/start/end/chapter, 9 short/long tap zones, gestures, pinch text resize, clock display, screen-backlight hold, reading-position synchronization, and hyphenation for 20 languages.

This audit does not assume that every historical AlReader feature is present in AlReaderX. The older `alreader.com`/legacy AlReader feature list is used only as an idea source for P2 items such as footnotes or hanging punctuation, not as proof of current AlReaderX parity requirements.

## Gap matrix

| Public AlReaderX behavior | MyHomeLib v7.1 state | Decision | Release impact |
|---|---|---|---|
| Day/night profiles | `day` and `night` built-in presets exist and can be persisted after applying, globally or per book. There are no two independently editable named persistent profile slots. | PARTIAL; named persistent profile slots are a later enhancement. | Audit gap documented; not a v7.1 correctness blocker. |
| Custom font settings | Font family, size, line spacing, paragraph spacing, first-line indent, alignment and hyphenation controls are exposed with live preview. | IMPLEMENTED. | None. |
| Custom color settings | Theme plus custom background/text colors are exposed and persisted through reader CSS variables. Theme model also has link/selection/quote/code colors, but those finer colors do not have direct UI controls. | PARTIAL; background/text customization is real, fine-grained colors remain P2. | None for v7.1. |
| One/two-page mode + automatic wide/landscape switch | Real two-page spread and `autoTwoPageLandscape` are implemented. | IMPLEMENTED. | Keep regression coverage for position preservation and odd last spread. |
| Autoscroll | Implemented as timed page advance. It intentionally does not imitate AlReaderX “wave” continuous scrolling, avoiding continuous JavaFX redraw. | PARTIAL BY DESIGN. | Not a correctness blocker; performance/UX trade-off is explicit. |
| Navigation by percentage/pages/start/end/next/previous chapter | `goToPercent`, approximate page navigation, start/end actions and chapter navigation are wired to Reader/toolbar/input actions. | IMPLEMENTED. | Page number remains approximate because a full page map is intentionally not retained in RAM. |
| 9 short + 9 long tap zones | `ReaderInputSettings` has 3×3 short and 3×3 long actions. | IMPLEMENTED. | Keep selection-vs-long-press regression coverage. |
| Gestures + pinch text resize | Four configurable swipes plus pinch zoom are implemented. | IMPLEMENTED. | Keep conflict tests with text selection. |
| Clock display | Optional status clock exists. | IMPLEMENTED. | None. |
| Hold screen backlight | Desktop JavaFX has no portable/safe equivalent. No fake OS brightness/backlight API is added. | NOT APPLICABLE. | None. |
| Reading-position synchronization via network/filesystem | Local position autosave/persistence exists; cross-device/network/filesystem sync does not. | DEFERRED P2. | Explicitly non-blocking for v7.1. |
| Hyphenation for 20 languages | Language-aware hyphenation is implemented with bundled dictionaries for `uk`, `en`, `bg`, `ru`. | PARTIAL. | Additional dictionaries are optional content expansion, not a data-integrity blocker. |
| OpenGL page animation / Android hardware-button assignment | Platform-specific behavior is not reproduced. Keyboard/mouse/touchpad actions use JavaFX-native routing. | INTENTIONALLY NOT PORTED. | None. |

## Regression findings from this pass

- `ReaderSettingsPresetsTest` was stale and still expected four presets; it now includes the `day` preset and matches the production list of five built-ins.
- `ReaderCanvas` keyboard/wheel routing was extracted to `ReaderKeyboardScrollController`; Stage 25B size/refactor guard is back under its configured limit.
- The current settings model has real custom background/text colors, so “custom colors missing” is not a valid gap. Only finer link/selection/semantic-style controls remain.
- Local position persistence is present, but it must not be described as AlReaderX-style synchronization.

## Remaining reader verification before formal release acceptance

The static/portable checks cover the wiring, but the connected Maven/JavaFX run is still required for: large FB2/EPUB behavior, one↔two-page position preservation, odd final spread, resize wide↔narrow, pinch repagination, left/right-page selection, search-result navigation, autosave after navigation, settings restart persistence, autoscroll/manual-navigation interaction, keyboard focus traversal and JavaFX-thread responsiveness.

P2 candidates after v7.1: independent editable day/night profile slots, fine-grained link/selection/semantic-style colors, optional position sync, footnote presentation if the document model supports it cleanly, and typography refinements such as hanging punctuation when measurable and non-regressive.
