# Iteration 38 checkpoint — MHL-205 / MHL-206

Date: 2026-09-11  
Base: MyHomeLib 7.3 WIP / Iteration 37

## Scope implemented before test freeze

### MHL-205 — Export annotations
- Application-layer streaming exporter for Markdown, JSON and HTML.
- Scope: one book, multiple selected books or all books.
- Stable identity plus book metadata, chapter, quote, note, tags, color/type, position and timestamps.
- User-editable Markdown/HTML document and item templates; missing `${items}` is normalized to a safe insertion point.
- Template placeholders are expanded in one pass, so annotation text containing `${...}` is never reinterpreted as template syntax.
- JSON schema marker: `myhomelib.annotations.export/v1`.
- UTF-8 output, explicit deterministic default line endings and deterministic storage ordering.
- Bounded storage paging (`250` rows requested per application export page; infrastructure hard-cap `500`).
- Same-directory temporary output and publish-on-success semantics; cancellation/error removes the temporary file and does not pre-truncate the destination.
- Annotation Manager invokes the application service asynchronously; no rich-export UI → SQLite/JDBC path was added.

### MHL-206 — PDF Reader v1
- PDF marked Reader-supported while full-text support remains disabled.
- Dedicated PDF renderer package in `myhomelib-reader` using Apache PDFBox 3.0.8.
- Existing Reader async prepare/open workflow routes `.pdf` to `PdfDocumentSession`.
- Previous/next navigation, zoom in/out/reset, Fit Width, Fit Page, continuous mode and thumbnail sidebar.
- Existing Reader position persistence stores/restores the PDF page.
- Lazy page rasterization, one background render executor and bounded raster LRU cache (64 MiB target, 12M-pixel raster ceiling).
- Render generation + current-session checks reject stale completion after rerender/switch/close; queued render futures are tracked and cancelled on rerender/mode switch/close.
- Page geometry is read during background prepare, so Fit Width/Fit Page does not call PDFBox from the JavaFX thread.
- Raster requests are bounded to 12M pixels and pathological pages that cannot fit even the minimum DPI are rejected before raster allocation.
- PDF session is closed on abandoned/failed prepare and on Reader close.
- Malformed/encrypted/render failures propagate as controlled Reader errors rather than running parsing/rendering on the JavaFX thread.
- PDF source bytes are copied in bounded chunks to a session-owned temporary PDF before parsing; PDFBox uses a temp-file stream cache and the session removes its copy after close.

## Dependency note before final tests

The supplied offline Maven repository does not contain PDFBox 3.0.8. The locally installed PDFBox 1.8.16 was rejected during manual review because Apache documents a crafted-PDF OOM vulnerability affecting versions through 2.0.23. The source therefore targets the current 3.0.8 release rather than silently downgrading to the insecure local jar. Binary download is unavailable in the isolated container and no PDFBox 3.x artifact exists elsewhere in the supplied files. If 3.0.8 artifacts cannot be added to the isolated repository, MHL-206 must be reported as an offline dependency blocker rather than falsely marked DONE.

## MHL-207 gate

Not started before the final test cycle. The task explicitly requires MHL-205 and MHL-206 to be fully accepted first. Because this iteration also requires all source/docs/tests changes to be completed before the first test run, starting MHL-207 before those acceptance results would violate the gate. PDF TOC/search/bookmarks/annotations and OCR are therefore outside the pre-test freeze.

## Added acceptance/regression coverage (source added, not yet executed at this checkpoint)
- `AnnotationExportServiceTest` — schema/Unicode/emoji/determinism/templates/escaping/placeholder-in-content isolation/bounded paging/cancel safety.
- `PdfDocumentSessionTest` — open/page metadata/render/cache/malformed/encrypted/interruption/pathological-raster guard.
- `PdfReaderSourceContractTest` — background executor, stale-result guard and bounded-cache source contract.
- `AnnotationManagerUiContractTest` extended for rich-export application boundary.
- `PdfReaderWorkflowContractTest` — existing Reader routing/persistence boundary and no UI PDFBox call.
- `SupportedFormatRegistryTest` updated for PDF Reader support.

## Manual review before test freeze

Completed without running Maven/tests:
- UI → application boundary checked for MHL-205; changed UI controllers contain no JDBC/SQLite dependency.
- PDF renderer package checked for persistence coupling; none was added.
- Application export package depends on the annotation export output port, not infrastructure.
- PDF lifecycle reviewed for switch/close: stale generation guards plus tracked render-future cancellation and deferred session close on the render executor.
- PDF page sizes are precomputed during background open; no PDFBox page access is required for JavaFX fit calculations.
- Export paging/cache/raster bounds reviewed; annotation export requests 250 rows per page and SQLite caps requests at 500.
- Markdown/HTML/JSON escaping and UTF-8 handling reviewed; template content cannot trigger recursive placeholder expansion.
- New localization keys are present consistently in UK/EN/BG.
- `ITERATION-38-CHANGED-FILES.txt` records the frozen source/doc/test inventory.

## Pre-test rule

No Maven compile/test/acceptance command is run until all source/docs/test changes, offline dependency preparation and manual static review are complete. Final test evidence and release status are appended only after that single final test cycle.

## Final test cycle — 2026-09-11

The implementation tree was frozen before the final compile/acceptance/regression cycle. Two defects found by final static gates were corrected (root `Lang/` synchronization and one unused import/new helper clone), after which the affected static gates and MHL-205 integration test were rerun. No MHL-207 work was started.

### 1. Offline reactor `test-compile`

Command: `./mvnw -o -Dmaven.repo.local=<supplied-offline-repo> -DskipTests test-compile`

Result:
- parent: PASS;
- `myhomelib-shared`: PASS;
- `myhomelib-domain`: PASS;
- `myhomelib-application`: PASS;
- `myhomelib-infrastructure`: PASS;
- `myhomelib-reader`: BLOCKED before compilation because `org.apache.pdfbox:pdfbox:3.0.8` is absent from the supplied offline Maven repository;
- Maven therefore skipped downstream UI/bootstrap/ArchUnit/E2E modules in this reactor run.

This is a dependency-resolution blocker, not a Reader test assertion or Java compilation failure. The source remains pinned to PDFBox 3.0.8; it is not downgraded to the old system PDFBox.

### 2. MHL-205 targeted acceptance

PASS:
- `AnnotationExportServiceTest`: 6 tests, 0 failures, 0 errors;
- `SqliteAnnotationExportQueryAdapterIntegrationTest`: 1 test, 0 failures, 0 errors.

Acceptance exercised deterministic UTF-8 Markdown/JSON/HTML generation, JSON schema marker, templates/escaping, `${...}` content isolation, bounded paging, cancellation safety, selected/all scope and real SQLite ordering/metadata/tag retrieval.

**MHL-205 status: DONE.**

### 3. MHL-206 targeted acceptance

- `SupportedFormatRegistryTest`: PASS — 3 tests, 0 failures, 0 errors; PDF is Reader-supported and remains outside full-text support.
- Reader-specific JUnit (`PdfDocumentSessionTest`, `PdfPagePositionTest`, `PdfReaderSourceContractTest`) could not start because Maven cannot resolve PDFBox 3.0.8 in offline mode.
- UI PDF workflow contract cannot be compiled through the normal reactor for the same dependency-chain reason (`myhomelib-ui` depends on `myhomelib-reader`).

**MHL-206 status: IMPLEMENTED, ACCEPTANCE BLOCKED by missing offline PDFBox 3.0.8 artifact. Not marked DONE.**

### 4. Architecture / localization / static gates

PASS after the two final-gate corrections:
- `architecture-check.py`;
- `managed-executor-check.py`;
- `check-critical-ui-localization.py` — 351 stable keys / 14 source files;
- `validate-language-catalogs.py` — UK/EN/BG aligned, 741 UI keys and 335 genre keys each;
- `stage35-annotations-check.py`;
- `stage36-reader-annotations-check.py`;
- `stage37-annotation-manager-check.py`;
- `ui-function-reachability-check.py` — 236 FXML handlers / 58 application use cases reachable;
- `static_release_check.py` — XML/FXML, handlers, 57 SQLite migrations, packaging/CI and Java source scans pass.

`implementation-completeness-check.py` still reports one exact `inTransaction` method clone between `SqliteAnnotationManagerQueryAdapter` and `SqliteAnnotationRepository`. The same finding reproduces on the untouched Iteration 37 baseline; it is pre-existing baseline debt, not introduced by Iteration 38. The Iteration 38-specific unused import and duplicate helper finding were removed and the gate was rerun.

The Maven ArchUnit module is transitively blocked by the same PDF dependency (`myhomelib-architecture-tests` -> `myhomelib-bootstrap` -> `myhomelib-ui` -> `myhomelib-reader`). The independent architecture/static gates above are green.

### 5. Regression results available without PDFBox

PASS:
- full application regression: 187 application tests, 0 failures/errors, 1 skipped; upstream shared 12/12 and domain 20/20 also green;
- annotation regression group across domain/application/infrastructure: green;
- infrastructure regression was split into disjoint package groups after the monolithic run exceeded the execution window; all groups completed with `BUILD SUCCESS` and no failures/errors:
  - persistence/catalog/collection/config/custom-field group: 94 tests, 2 skipped;
  - download/import-engine/importer/parser/resource group: 140 tests, 2 skipped;
  - search/sync/metadata/cover/image/reader-infrastructure group: 75 tests, 2 skipped;
  - adapter/backup/event/executor/history/integrity/maintenance/monitoring/profiling/security/settings group: 31 tests, 1 skipped;
- OPDS tail: 14 tests, 0 failures/errors;
- MCP tail: 6 tests, 0 failures/errors.

Reader/UI/bootstrap/E2E Maven regressions cannot be executed in this isolated repository because their dependency chain reaches `myhomelib-reader` and the missing PDFBox 3.0.8 artifact. They are recorded as blocked, not passed.

### 6. MHL-207 gate

MHL-207 was not started. Its prerequisite requires MHL-205 and MHL-206 to be fully accepted without debt; MHL-206 cannot satisfy that prerequisite until PDFBox 3.0.8 is available to the offline build.

### Iteration 38 status before release-artifact integrity gate

- **MHL-205: DONE / acceptance green.**
- **MHL-206: implemented; offline acceptance blocked by missing `org.apache.pdfbox:pdfbox:3.0.8`.**
- **MHL-207: NOT STARTED by design.**
- Remaining work in this cycle: release-tree cleanliness plus source ZIP integrity/SHA-256 only; no further feature/source changes are planned.


## Release packaging correction — 2026-09-11

Per release handoff requirement, the formal source ZIP must contain no Maven payload. The policy was tightened after the first Iteration 38 archive:

- the complete `.mvn/` directory, `mvnw` and `mvnw.cmd` are excluded from source-release staging and ZIP creation;
- `pom.xml` remains part of the source contract;
- root build/run/package/release scripts resolve Maven through `tools/invoke-maven.*`: a repository checkout wrapper is used when present, otherwise the scripts use an installed `mvn` from `PATH`;
- `target/`, `verification/`, IDE/cache/generated directories remain excluded;
- the source packager verifies the extracted archive and rejects any Maven runtime/wrapper payload;
- the supply-chain gate accepts both repository checkouts with a complete integrity-checked wrapper and formal source-release staging with no wrapper;
- release documentation states that source-archive builds require Maven 3.9.6+ separately from the project dependency repository.

This packaging-policy correction does not change MHL-205/MHL-206 runtime behavior. The final source ZIP and SHA-256 are regenerated only after the updated packaging/static gates complete.

### Maven-free source archive correction — implementation freeze

The packaging correction is now implemented across the source packager, release policy, root launch scripts and GitHub workflows. The formal source ZIP contract is: no `mvnw`, no `mvnw.cmd`, no `.mvn/`; Maven is an external build prerequisite. `tools/invoke-maven.sh`, `.ps1` and `.cmd` keep repository checkouts and formal source archives on one command path by preferring a checkout wrapper when available and otherwise using `mvn` from `PATH`.

No additional MHL-205/MHL-206 feature changes were made in this correction. The source tree is frozen again here; all validation for this correction is performed after this point.
