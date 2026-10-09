# MyHomeLib — зміни V25 → V26

Дата: 2026-09-29. Baseline: clean V25 checkpoint.

## Production fixes

1. `BookDetailsController.onEdit` — red behavioral regression довів no-op; action тепер викликає canonical `ClassicLibraryActionsService.editBook`.
2. `MobiParser` — truncated unknown-size payload, коротший за declared `textLength`, тепер fail-closed з `IOException`.
3. Native chooser ownership — усі JavaFX `FileChooser`/`DirectoryChooser` construction/fallback централізовані у `FileChooserService`; callers не дублюють native lifecycle logic.

## Test/tooling hardening

- strict `ui-action-behavior-check.py`: 446 automated action-specific + 1 justified exclusion = 447/447;
- численні focused UI action contract tests для FXML/programmatic actions;
- malformed mutation corpus для EPUB/FB2/ZIP/INPX/MOBI;
- `functional-regression-check.py` ratcheted до нового chooser boundary;
- repeat of historical iteration/stage checks — PASS;
- `build-check-v7.py` fixed to be non-mutating on a pristine source tree: child Python gates run with `PYTHONDONTWRITEBYTECODE=1`; added `build-check-nonmutating-test.py` (RED before, PASS after).
- `static_release_check.py` source metrics now exclude generated `target/**`; added non-mutating `static-release-source-count-test.py`, wired into Linux CI and the formal source-packager gate. Exact-current count: 1989 Java / 595 test sources.
- `external-acceptance-readiness.py` exact-current structural contract updated from stale V25 `1842/17` to V26 `1960/18`; `CURRENT-VALIDATION.md` now spells external gate IDs explicitly. Existing readiness regression is RED-before/GREEN-after evidence.

## Verification

Full Maven 3.9.16 / JDK 21 / UTF-8 / Xvfb `clean verify`: **1960 tests, 0 failures, 0 errors, 18 skipped; 20/20 SUCCESS**.
