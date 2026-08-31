# Stage 20 Validation

Date: 2026-08-25

## PASS

- `tools/stage19-20-reader-check.py`:
  - real uk/en/bg/ru dictionary-aware hyphenation wiring;
  - document-language propagation into layout;
  - 3-second background autosave + final flush wiring;
  - EPUB nav/NCX fragment anchors;
  - Shift-drag selection + Ctrl+C;
  - large FB2/EPUB performance fixtures.
- Portable Reader settings/hyphenation Java 21 compile/run smoke: PASS.
- Transformed dependency-free Java 21 compile of the real `TextLayoutEngine`: PASS.
- Transformed dependency-free Java 21 compile of the real `EpubParser`: PASS.
- Stage 3–19 regression guards: PASS.
- Large-library guard: PASS.
- Architecture/static/language validations: PASS.

## Environment limitation

The Maven/JUnit suite itself cannot be launched because this environment cannot download Maven Wrapper/Maven Central artifacts. Performance/JUnit fixtures are included for the connected build; offline Java compile-oriented checks were run here for the changed portable reader code.
