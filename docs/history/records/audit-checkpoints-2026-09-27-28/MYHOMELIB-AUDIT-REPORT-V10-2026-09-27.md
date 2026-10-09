# MyHomeLib — deep technical audit / V10 checkpoint

Дата: 2026-09-27  
Baseline: `myhomelib-enterprise-optimized-v9-checkpoint-2026-09-27.zip`

## 1. Executive summary

V10 focused on asynchronous UI ownership/cancellation, reachable UTF-16 boundary defects, and two narrow state/presentation extractions. No GC, heap, persistence schema, packaging policy, or broad architectural rewrite was introduced.

Final verification:

- consolidated `clean verify`: **BUILD SUCCESS**, 20/20 modules, 02:55;
- aggregate Surefire: **1755 tests, 0 failures, 0 errors, 17 skipped**;
- UI under Xvfb: **217/217 PASS**;
- infrastructure: **634**, application: **453**, reader: **132**, bootstrap: **47** tests, all without failures/errors;
- completeness/static/XML/archive/supply-chain gates: **PASS**;
- production package, Linux jpackage app-image, stage23 and packaged `--release-smoke`: **PASS**.

## 2. Correctness defects closed

### 2.1 Blank Reader search could publish stale results

Search cancellation/generation was not invalidated on blank-query transitions in Reader search paths. Red regressions demonstrated that an older in-flight result could repopulate a cleared UI. The fixes invalidate generation/cancel the task before clearing state.

### 2.2 Search UI truncation could split surrogate pairs

Behavioral regressions covered saved-search default titles and Reader search/sidebar previews. The production changes preserve the existing character-limit policy while moving a cut point back only when it falls between a UTF-16 surrogate pair.

### 2.3 Book download completion could restore stale workspace state

A completed download for book A could call `setBookId(A)` after the user had already navigated to book B. The completion callback now validates an async request token/generation and current book ownership before refresh.

### 2.4 Reader open progress could outlive controller ownership

Two separate regressions were closed:

- queued progress after `dispose()` no longer updates UI/global status;
- progress from open request A no longer publishes after request B becomes current.

Progress publication now checks both lifecycle and the current open-generation token.

### 2.5 Annotation and bookmark previews could split surrogate pairs

Independent regressions covered unresolved annotation issue preview, annotation sidebar preview, and domain bookmark title generation. Each fix is boundary-local and preserves the existing maximum-length behavior.

## 3. Structural decomposition

### 3.1 `Utf16BoundarySupport`

Surrogate-safe UI cut logic that had become genuinely repeated was centralized after multiple red regressions proved the same boundary contract. Callers retain their own visible-length/ellipsis policy.

### 3.2 `SearchBookPagingState`

Extracted only paging mutable state and transition rules (`request`, total, loading-more / begin / finish / reset) from `SearchWorkspaceController`. Query orchestration and presentation remain in the controller.

### 3.3 `ReaderAnnotationSidebarFilter`

Extracted pure annotation-sidebar tag collection, filtering and deterministic ordering from `NewReaderWorkspaceController`. JavaFX controls, selection restoration and presentation remain in the controller.

No generic async framework was added; current ownership contracts remain sufficiently different.

## 4. Verification detail

Final Surefire totals from the clean reactor run:

- application 453 (1 skipped);
- infrastructure 634 (9 skipped);
- reader 132 (1 skipped);
- UI 217;
- bootstrap 47 (2 skipped);
- domain 30;
- shared 40;
- organizer 29;
- EPUB optimizer 21;
- converter toolchain 39 (1 skipped);
- plugin API 30;
- OPDS 24;
- MCP 10;
- architecture 14;
- E2E 15;
- benchmark 3 (3 skipped);
- remaining modules green.

Static/completeness result includes **0 exact cross-file method clone warnings >=180 chars**.

## 5. Release verification

Current Linux app-image preserves:

- 27 runtime modules with development tools excluded;
- external runtime tools outside the application classpath;
- EPUBCheck packaged as an external tool;
- optional bundled libmobi remains absent/supported rather than silently required;
- packaged launcher returns `MYHOMELIB_RELEASE_SMOKE_OK`.

Real Windows x64 runtime/package verification remains open because this environment is Linux.

## 6. Performance decision

No V10 performance default was changed. V9 production-scale evidence remains the basis for retaining **G1 + `-Xmx1024m`**. V10 changes are correctness/refactoring changes and do not claim a memory/performance improvement.

## 7. Checkpoint decision

V10 is suitable as the next clean source baseline: all V10 defects are regression-backed, refactors stayed within one responsibility boundary, final reactor/static/release gates are green, and remaining work is explicitly documented in `MYHOMELIB-NEXT-TASKS-V10-2026-09-27.md`.
