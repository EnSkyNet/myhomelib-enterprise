# Iteration 48 — TTS, Accessibility, Reader regression

**Дата:** 2026-09-12  
**Статус:** DONE (local acceptance)  
**Scope:** MHL-209, MHL-212, MHL-213

## MHL-209 — System TTS / Read Aloud

- Application-owned `TtsProvider` boundary and asynchronous `TtsPlaybackService`.
- Native system adapters for Windows PowerShell/System.Speech, macOS `say`, Linux `espeak-ng`/`espeak`.
- Reader controls: start, pause/resume, stop; system voice selection and speed 0.5–2.0x.
- Sentence-level Reader highlight callback is marshalled to JavaFX.
- Pause/resume race is guarded so an interrupted sentence is replayed after resume rather than skipped.
- TTS remains local/system-owned; no cloud text transport was added.

## MHL-212 — Accessibility audit and hardening

- Runtime accessibility enhancement/audit for programmatic and FXML controls.
- All 29 FXML views checked for keyboard-focusable buttons and accessible naming.
- Icon-only controls receive semantic accessible text; glyphs alone are not accepted as meaningful names.
- Warning foreground/background contrast was raised from the previous insufficient value to WCAG-friendly >5:1 for the guarded base theme combination.
- Added `ui.accessibility.reducedMotion`; Reader auto-scroll is disabled when Reduced Motion is active.
- Ukrainian, English and Bulgarian catalogs contain the accessibility and TTS strings in both root and bundled copies.

## MHL-213 — Reader regression corpus

- E2E corpus covers FB2, EPUB, PDF and CBZ.
- Includes malformed inputs, Unicode paths/content and a multi-megabyte FB2 scenario.
- Reopen validation covers reading progress, bookmarks and annotations through fresh repository instances.

## Local acceptance

- `tools/iteration48-tts-accessibility-reader-regression-check.py`: 12/12 PASS.
- Full offline Maven reactor: 13/13 modules BUILD SUCCESS.
- Surefire aggregate: 834 tests, 0 failures, 0 errors, 10 skipped.
- E2E module: 14/14 PASS.
- Static architecture/security/release gates: required to be green before source packaging.

## External boundary

This iteration does not close the existing external Windows/GitHub acceptance tasks MHL-010/MHL-011/MHL-012/MHL-017/MHL-018/MHL-019. Those remain OPEN until real external evidence is attached.
