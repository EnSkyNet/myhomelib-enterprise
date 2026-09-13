# Iteration 40 — PDF closure checkpoint

**Date:** 2026-09-11  
**Status:** DONE — final offline acceptance/regression cycle completed; formal source artifact prepared without Maven/dependency binaries.

## Supplied dependency sources

The user supplied Apache PDFBox 3.0.8 source and Apache PDFBox JBIG2 ImageIO 3.0.5 source archives. They are used only to seed an **external** offline dependency repository. They are not copied into the MyHomeLib source release.

Prepared external artifacts:
- `org.apache.pdfbox:pdfbox-io:3.0.8`;
- `org.apache.pdfbox:fontbox:3.0.8`;
- `org.apache.pdfbox:pdfbox:3.0.8`;
- `org.apache.pdfbox:jbig2-imageio:3.0.5`.

The formal source ZIP remains free of Maven runtime/wrapper files and dependency JARs.

## MHL-206

The Iteration 38 source implementation is unchanged in architecture: `PdfDocumentSession` owns PDFBox document lifecycle/rasterization and `PdfReaderView` owns JavaFX presentation/background scheduling. The former missing-PDFBox blocker was removed by the external offline repo. MHL-206 is accepted by the final Reader/UI/regression cycle.

## MHL-207 implementation

- bounded/cancellable PDF outline extraction during background document preparation;
- TOC entries expose title, zero-based page index and hierarchy level without exposing PDFBox;
- bounded text-layer search returns page-local hit positions and snippets;
- explicit `textLayerDetected` state for image-only/scanned PDFs; no implicit OCR;
- Reader toolbar actions for PDF TOC, search, add bookmark and bookmark list;
- PDF bookmarks reuse `NewReaderPersistenceService` and serialized `ReaderPosition` page mapping;
- `PdfAnnotationSelectionData` application contract keeps page-local offsets explicit and does not create alternate annotation persistence;
- JBIG2 ImageIO is a runtime dependency for PDFs containing JBIG2-compressed images.

## Iteration 39 tail

MHL-210/MHL-211 remain part of the same final regression cycle. Their previously recorded targeted acceptance is not treated as a substitute for the final Iteration 40 reactor/regression run.

## Freeze rule

After source/docs/tests inventory and manual architecture review are complete, no new feature work is added. Any later edit must be attributable to a failing final gate and followed by rerunning the affected gate.

## Pre-test manual review / freeze

Manual review completed before the first Iteration 40 project compile/test run:
- no PDFBox imports exist in UI, Application, Domain or Infrastructure production sources;
- PDF outline/search limits and interruption checks are explicit;
- stale PDF search completion is guarded by session + generation tokens and document tasks are cancelled on close/switch;
- PDF bookmark save/load/jump reuses `NewReaderPersistenceService`;
- deferred MHL-207 localization keys were replaced in synchronized root/bundled UK/EN/BG catalogs;
- the formal source packager excludes `mvnw`, `mvnw.cmd`, `.mvn/`, generated targets and verification evidence.

The Iteration 40 source/docs/tests tree is now frozen. `ITERATION-40-CHANGED-FILES.txt` records 27 source/documentation/test paths (plus its header text). From this point, edits are allowed only to correct a defect discovered by a final gate, followed by rerunning the affected gate.


## Final acceptance / regression results

Final cycle completed after source/docs/tests freeze. No functional source changes were made during the cycle.

- PDFBox/JBIG2 dependency closure: PASS. `pdfbox-io`, `fontbox`, `pdfbox` 3.0.8 and `jbig2-imageio` 3.0.5 were compiled from the user-supplied source archives and placed only in the external offline repository.
- MHL-206/MHL-207 Reader targeted acceptance: **14/14 PASS** in headless mode. The first raster run without `java.awt.headless=true` hit the CI host's unavailable X11 display (`DISPLAY=:0`); the same test set passed fully in headless mode.
- MHL-210/MHL-211 Application targeted acceptance including PDF annotation contract: **14/14 PASS**.
- MHL-210/MHL-211 Infrastructure targeted acceptance: **10/10 PASS**.
- PDF/text-provider UI contracts: **5/5 PASS** and UI production/test compilation PASS.
- Full Application regression: **201 tests, 0 failures, 0 errors, 1 skip**.
- Full Reader regression: **60 tests, 0 failures, 0 errors, 1 skip**.
- Full UI regression: **66/66 PASS**.
- Infrastructure regression: group A **94 tests, 0 failures/errors, 2 skips**; group B was split after an external command time limit, and all B subgroups completed with `BUILD SUCCESS` (B1 21 tests/1 skip, `JdbcBatchWriterStage6Test` 4/4, remaining B2 115 tests/1 skip); group C **75 tests, 0 failures/errors, 2 skips**; group D **31 tests, 0 failures/errors, 1 skip**. New text-provider infrastructure acceptance is covered separately by the 10/10 targeted run.
- Tail reactor: OPDS **14/14**, Bootstrap **15/15**, MCP **6/6**, ArchUnit **12/12**, E2E **10/10** — `BUILD SUCCESS`.
- Iteration 39/40 source contracts, architecture, critical UI localization, language catalogues, supply-chain policy, static release, managed-executor, UI reachability and Stage 35/36/37 annotation gates: PASS.
- `implementation-completeness-check.py` still reports only the pre-existing `inTransaction` clone between `SqliteAnnotationManagerQueryAdapter` and `SqliteAnnotationRepository`; the identical finding is present in the Iteration 38 baseline log and is not introduced by Iteration 40.
- Offline `mvn install` is not used as an acceptance gate because the supplied offline repository lacks `maven-install-plugin:3.1.1`; reactor `test`/`test-compile` gates do not depend on that plugin and completed successfully.

## Release status

- **MHL-206: DONE.**
- **MHL-207: DONE for the Iteration 40 minimum scope** (TOC, text-layer search/jump, page bookmarks, no implicit OCR, page-local PDF annotation application contract).
- **MHL-210: DONE.**
- **MHL-211: DONE.**
- Formal source ZIP policy remains strict: no `.mvn/`, `mvnw`, `mvnw.cmd`, Maven distribution, PDFBox/JBIG2 JARs or other dependency binaries.
