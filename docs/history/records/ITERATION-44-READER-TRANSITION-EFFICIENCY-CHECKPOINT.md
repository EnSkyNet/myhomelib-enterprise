# Iteration 44 — Reader transition/runtime efficiency checkpoint

**Date:** 2026-09-11  
**Base:** Iteration 43  
**Status before final cycle:** SOURCE/DOCS/TESTS COMPLETE — tree frozen before tests.

## Why this iteration exists

The Windows runtime log supplied after Iteration 43 showed a healthy Reader open followed by an avoidable second FB2 parse on a UI background thread. It also showed repeated built-in Reader format registration messages whenever a new Reader workspace was created. Neither was a correctness failure, but both are avoidable runtime work/noise.

## Implemented before tests

### Same logical book transition guard

`BookDetailsViewModel` now has a deliberately narrow `setCurrentBookIfDifferentId` operation. It is used only by the Reader workspace transition. Same-id transitions keep the existing details selection/session and therefore avoid re-triggering `BookDetailsAnalysisService`; a normal `setCurrentBook` still emits same-id replacements for real metadata refreshes.

### Canonical standard format registry

`DefaultBookFormatRegistry.standard()` preloads FB2/EPUB/TXT/ZIP without replaying normal registration INFO logs. The returned instance stays mutable and isolated, so Reader custom-format extension semantics are preserved. ReaderView and BookInspectionService now share this construction path.

## Manual pre-test review

- no persistence/schema change;
- no change to Reader archive materialization ownership;
- no new Reader -> application/infrastructure dependency;
- same-id suppression is intentionally **not** applied globally to `setCurrentBook`, preventing stale details after edit/download refresh;
- standard format instances are stateless factories; parser instances are still created per `createParser()` call;
- returned registries have separate maps, so custom registration remains per-instance;
- no Maven/dependency binaries are added to source.

## Final results

Final acceptance was run only after the source/docs/tests freeze.

- Targeted Iteration 44 acceptance: **7/7 PASS** — standard registry 3 tests; same-book transition/view-model contract 4 tests.
- Headless affected reactor: **BUILD SUCCESS**. Shared **12/12**, Domain **20/20**, Application **206 tests** (0 failures/errors, 1 intentional skip), Reader **62 tests** (0 failures/errors, 1 environment-dependent real-file skip), UI **72/72**.
- The first affected regression inherited `DISPLAY=:0` and the PDF raster test correctly failed to connect to a non-existent X11 server. No source was changed; rerun with `DISPLAY` unset and `-Djava.awt.headless=true` passed.
- Architecture: PASS.
- Critical UI localization: PASS (386 stable keys / 14 source files).
- Implementation completeness: PASS; 903 production Java files; 0 TODO/FIXME; 0 unused imports; 0 exact cross-file method clones >=180 chars.
- Static release: PASS — 42 XML/POM/FXML, 29 workspaces / 236 handlers, 57 SQLite migrations / integrity ok, shell/release static issues 0.
- Supply-chain policy: PASS.
- XML/archive security: PASS.
- Full offline reactor `test-compile`: **13/13 modules BUILD SUCCESS**.
- Formal source release remains Maven-free and dependency-binary-free; release artifact integrity is recorded in `MyHomeLib-Iteration44-VALIDATION.txt`.
