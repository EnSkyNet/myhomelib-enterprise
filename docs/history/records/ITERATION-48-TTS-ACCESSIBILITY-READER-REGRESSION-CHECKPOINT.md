# Iteration 48 checkpoint — TTS / Accessibility / Reader regression

**Date:** 2026-09-12  
**Local status:** DONE  
**Tasks:** MHL-209, MHL-212, MHL-213

## Closure summary

Iteration 48 closes the remaining local Reader 7.3 items after the Comic Reader checkpoint. Read Aloud uses only operating-system speech facilities through an application SPI; the Reader never calls a cloud TTS endpoint. Playback is asynchronous, supports voice/rate selection and pause/resume/stop, and highlights the current sentence without blocking JavaFX.

Accessibility hardening now covers the complete FXML surface, keyboard reachability, semantic names for icon controls, base-theme contrast and Reduced Motion. Reduced Motion is a persisted setting consumed by Reader to prevent automatic scrolling.

The Reader E2E regression corpus now exercises FB2, EPUB, PDF and CBZ plus malformed, Unicode and large-file cases and verifies persistence after reopen.

## Defects found during closure

1. A pause/resume race could skip the interrupted TTS sentence when Resume followed Pause quickly. The playback state now explicitly replays the current sentence.
2. The warning color produced only ~2.53:1 contrast against white text. The base warning color is now `#b45309`, yielding >5:1 for the guarded combination.
3. Several icon-only/Library Health controls lacked explicit accessible naming; the full FXML audit exposed and corrected them.
4. Reduced Motion was implemented but initially lacked `ui.accessibility.reducedMotion` in all language catalogs. The critical-localization gate caught this before packaging; UK/EN/BG root and bundled catalogs now contain it.

## Verification

- Full offline reactor: **13/13 modules BUILD SUCCESS**.
- Aggregate Surefire: **834 tests; 0 failures; 0 errors; 10 skipped**.
- E2E: **14/14 PASS**.
- Iteration 48 source contract: **12/12 PASS**.
- External acceptance MHL-010/011/012/017/018/019 remains OPEN and is not represented as locally closed.
