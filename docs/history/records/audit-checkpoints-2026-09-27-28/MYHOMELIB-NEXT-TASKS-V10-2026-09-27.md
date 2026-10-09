# MyHomeLib — завдання для продовження після V10 checkpoint

Дата фіксації: 2026-09-27

Базовий архів для наступної сесії:
`myhomelib-enterprise-optimized-v10-checkpoint-2026-09-27.zip`

## 1. Незмінні правила

1. correctness і поведінкові контракти — перший пріоритет;
2. defect: red regression → мінімальний fix → targeted/downstream tests;
3. refactoring: один responsibility boundary за раз;
4. performance/memory: тільки контрольовані A/B або matrix-вимірювання;
5. не переписувати стабільний код без тестової або вимірюваної причини;
6. не створювати generic async/framework abstractions, поки щонайменше два consumers не мають однакового lifecycle contract;
7. не повертати вже прибрані eager/lifecycle/runtime дублювання.

## 2. Перевірений V10 state

- consolidated `clean verify`: **BUILD SUCCESS**, 20/20 modules, 02:55;
- aggregate Surefire: **1755 tests, 0 failures, 0 errors, 17 skipped**;
- `myhomelib-ui`: **217/217 PASS** під Xvfb;
- `myhomelib-infrastructure`: **634**, 0 failures/errors, 9 skipped;
- `myhomelib-application`: **453**, 0 failures/errors, 1 skipped;
- `myhomelib-reader`: **132**, 0 failures/errors, 1 skipped;
- `myhomelib-bootstrap`: **47**, 0 failures/errors, 2 skipped;
- implementation completeness / static release / XML-archive security / supply-chain: **PASS**;
- production bootstrap reactor package (`-Pproduction -DskipTests`): **BUILD SUCCESS**;
- Linux `jpackage` app-image: **PASS**;
- stage23 release checker: **PASS**;
- packaged launcher `--release-smoke`: **PASS**.

## 3. V10 changes, які не можна втрачати

- blank Reader/Search queries invalidate generation and cancel in-flight work, preventing stale-result repopulation;
- Reader sidebar search cancellation is generation-safe;
- `BookWorkspaceController` download completion can no longer reload a stale book after workspace navigation;
- Reader open progress is guarded both against dispose and against stale open generations;
- surrogate-safe truncation is centralized through `Utf16BoundarySupport` for relevant UI boundaries;
- saved-search default names, annotation issue previews, annotation sidebar previews and bookmark titles no longer split surrogate pairs;
- `SearchBookPagingState` owns search-result paging state;
- `ReaderAnnotationSidebarFilter` owns pure annotation sidebar tag/filter/ordering selection.

## 4. P0 — Windows release coverage

Still requires a real Windows x64 environment:

- production build/package;
- launcher from ASCII and non-ASCII paths;
- packaged `--release-smoke`;
- runtime tools availability;
- plugin install/enable/disable/restart lifecycle;
- EPUB/FB2/PDF reader smoke;
- strict/full release fail-closed behavior for incomplete licensed/bundled libmobi runtime/source bundle.

Linux evidence is not a substitute for Windows runtime validation.

## 5. P1 — async ownership / cancellation continuation

Continue only where a stale completion can be demonstrated by a red test.

Priority review:

1. remaining `SearchWorkspaceController` content-search cancellation and workspace replacement semantics;
2. `ReaderTextProviderCoordinator` callbacks when active reader/book changes or workspace is disposed;
3. annotation async refresh/callback paths in `NewReaderWorkspaceController` / `ReaderAnnotationCoordinator`;
4. shutdown paths that cancel `Future`/executor work but may still allow queued JavaFX callbacks;
5. plugin restart/upgrade/uninstall lifecycle callbacks after package replacement.

Do not introduce a generic cancellable-task framework unless tests prove the same lifecycle contract exists in multiple consumers.

## 6. P2 — structural decomposition

One boundary at a time:

1. `NewReaderWorkspaceController`: remaining annotation/sidebar/search presentation responsibilities after `ReaderAnnotationSidebarFilter` extraction;
2. `SearchWorkspaceController`: remaining result-presentation/query orchestration after `SearchBookPagingState` extraction;
3. `DesktopIntegrationBackend`: remaining sync/export/plugin/settings boundaries only when independently testable;
4. `VersionedUserDataTransferAdapter`: traversal/restore/compensation only with a concrete failing or independently testable contract;
5. `InpxImportPipeline`: parsing/batching/progress/index-sync only where responsibility ownership is clear.

Literal clone checker is already 0 exact cross-file method clones >=180 chars; continue semantic/AST-style review only.

## 7. P3 — correctness / hidden defects

Targeted audit only:

- remaining reachable raw UTF-16 truncations; do not mass-replace `substring()`;
- canonical equivalence / combining-character offset boundaries in Reader/Search annotations;
- malformed/truncated ZIP/EPUB/FB2/MOBI/INPX with unknown sizes and cumulative limits;
- overflow in parser slice arithmetic (`offset + length`, `position + size`);
- HTTP resume after redirect/validator change/truncated 206 body;
- temp-file publication/rollback when target changes concurrently;
- SQLite busy/retry transaction boundaries under concurrent import/edit/index update;
- plugin package/JAR symlink/replacement/restart races.

Potential truncation candidates found but **not yet proven defective**: `FfmpegAudioPlaybackBackend` diagnostic string and `LineLayout.toString()` preview. Add a regression first before any production change.

## 8. P4 — performance continuation

V9 evidence remains authoritative: keep **G1 + `-Xmx1024m`**.

Only revisit defaults for a concrete product need and with paired workloads, e.g.:

- scripted packaged UI/JFR pause profile;
- Lucene search while rebuild runs, with UI responsiveness signal;
- combined Lucene rebuild + large Reader + packaged UI with PSS sampling;
- byte-equivalent datasets/homes and explicit logging of aborted runs.

## 9. Next checkpoint gates

Before V11 baseline:

- every new defect has red-before/green-after regression evidence;
- modified full module suites green;
- full UI under Xvfb green;
- completeness/static/XML/archive/supply-chain green;
- production package succeeds;
- consolidated `clean verify` succeeds;
- Linux stage23 + packaged launcher smoke green if packaging/runtime-affecting code changed;
- Windows smoke documented if a Windows environment becomes available;
- clean ZIP excludes `target/`, `dist/`, `.git/`, `.mvn/repository/`, logs/temp/cache;
- ZIP CRC passes and SHA-256 is recorded.
