# MyHomeLib — завдання для продовження після V9 checkpoint

Дата фіксації: 2026-09-27

Базовий архів для наступної сесії:
`myhomelib-enterprise-optimized-v9-checkpoint-2026-09-27.zip`

## 1. Незмінні правила

1. correctness і поведінкові контракти — перший пріоритет;
2. defect: red regression → мінімальний fix → targeted/downstream tests;
3. refactoring: один responsibility boundary за раз;
4. performance/memory: лише paired A/B або контрольована matrix;
5. не переписувати стабільний код заради стилю;
6. не створювати generic framework для механічно схожих, але семантично різних orchestration blocks;
7. не повертати eager/lifecycle/runtime дублювання, уже прибране у V7–V9.

## 2. Перевірений V9 state

- full `clean verify`: **BUILD SUCCESS**, 20/20 modules, 03:03;
- aggregate Surefire: **1736 tests, 0 failures, 0 errors, 17 skipped**;
- UI під Xvfb: **199/199 PASS**;
- infrastructure у consolidated verify: **634 tests, 0 failures/errors, 9 skipped**;
- application: **453 tests, 0 failures/errors, 1 skipped**;
- bootstrap: **47 tests, 0 failures/errors, 2 skipped**;
- completeness/static/XML/archive/supply-chain: **PASS**;
- production reactor package: **BUILD SUCCESS**;
- Linux jpackage/stage23/packaged smoke: **PASS**.

## 3. V9 changes, які не можна втрачати

- refresh plugin package inventory before replacement decision;
- `PluginRuntimePackageStager` TOCTOU-safe verified runtime staging;
- `OrganizerTargetPublisher` fresh-target publication/rollback boundary;
- cumulative 1 GiB declared-size enforcement for INPX;
- cumulative EPUB importer archive-size enforcement;
- cumulative Reader EPUB archive-size enforcement;
- surrogate-safe annotation summary truncation;
- surrogate-safe support-bundle value truncation.

## 4. P0 — release coverage

### Windows x64

Виконати на реальному Windows x64 environment:

- production build/package;
- launcher startup from ASCII і non-ASCII paths;
- packaged `--release-smoke`;
- runtime tools availability;
- plugin install/enable/disable/restart lifecycle;
- EPUB/FB2/PDF basic reader smoke;
- strict/full release fail-closed behavior when licensed/bundled libmobi runtime/source bundle is incomplete.

Linux environment не є доказом Windows runtime correctness.

## 5. P1 — performance continuation

V9 already established:

- Serial GC lowers packaged PSS by ~5.35% but slows main-window readiness ~19%;
- Serial INPX pauses are materially worse despite ~9% lower peak RSS;
- Serial Lucene is faster/lower-memory in current batch benchmark but has much worse pause profile;
- G1 `-Xmx512m/768m` works on full INPX but gives only ~5–6% RSS reduction with slower import and higher pause burden;
- defaults remain **G1 + `-Xmx1024m`**.

Next performance work only if there is a concrete product reason to reconsider defaults:

1. scripted packaged UI interaction/JFR pause profile, not startup-only memory;
2. Lucene search during/after rebuild with UI responsiveness signal;
3. `-Xmx512/768/1024` under Lucene rebuild + large Reader + packaged UI, with PSS sampling;
4. keep datasets/homes byte-equivalent and record all failed/aborted runs separately.

Do not change GC/Xmx from V9 evidence alone.

## 6. P2 — structural clone / mutable-contract audit

Continue semantic/AST-style review; literal checker is already 0 exact method clones >=180 chars.

Priority candidates:

1. async cancellation/current-generation ownership in `SearchWorkspaceController`, `SearchDialogController`, `NewReaderWorkspaceController`, `BookWorkspaceController`;
2. filesystem transaction/apply/rollback patterns not already covered by `OrganizerTargetPublisher` / `SafeFilePublication`;
3. import/persistence batch resource ownership and cancellation boundaries;
4. SQLite/Lucene coordination around edit/import/index-update concurrency;
5. portable restore external-state compensation and manifest traversal, only where a failing scenario can be demonstrated;
6. plugin lifecycle restart/uninstall/upgrade edge cases after the V9 inventory refresh fix.

Do not create a generic cancellable-task framework unless at least two consumers share the same lifecycle contract and tests demonstrate that the abstraction reduces divergence risk.

## 7. P3 — large-class decomposition

One responsibility at a time, with behavioral tests:

1. `NewReaderWorkspaceController`: remaining annotation/sidebar/search presentation responsibilities;
2. `SearchWorkspaceController`: query state vs result presentation/paging;
3. `DesktopIntegrationBackend`: remaining sync/export/plugin/settings boundaries, without a replacement god-service;
4. `VersionedUserDataTransferAdapter`: traversal/restore/compensation only if boundaries remain independently testable;
5. `InpxImportPipeline`: parsing/batching/progress/index sync only where contract ownership is clear;
6. `ApplyLibraryOrganizationUseCase`: continue only beyond the already-extracted target publication boundary.

## 8. P4 — correctness / hidden defects

Continue targeted audit of:

- remaining raw UTF-16 truncations in Reader/Search UI (`SearchDialogController`, `NewReaderWorkspaceController`, saved-search default title); add one regression per real boundary defect;
- canonical equivalence/combining characters where offsets cross normalization boundaries;
- malformed/truncated ZIP/EPUB/FB2/MOBI/INPX, including cumulative limits and unknown sizes;
- integer overflow in parser slice arithmetic;
- HTTP resume after redirect/validator change/truncated 206 body;
- temp-file publication and rollback after target changes concurrently;
- cancellation/shutdown races and executor ownership;
- SQLite busy/retry transaction boundaries under concurrent import/edit/index update;
- plugin package/JAR validation, symlink/replacement/restart races.

Do not mass-replace every `substring()` call. Each production change needs a test proving the boundary is reachable and behaviorally incorrect.

## 9. Next checkpoint gates

Before V10 baseline:

- every new defect has a red-before/green-after regression;
- modified full module suites green;
- full UI under Xvfb green;
- completeness/static/XML/archive/supply-chain green;
- production reactor package succeeds;
- consolidated `clean verify` succeeds if environment permits;
- Linux packaged stage23 + launcher smoke green;
- Windows smoke result documented if a Windows environment becomes available;
- clean source ZIP excludes `target/`, `dist/`, `.git/`, `.mvn/repository/`, logs/temp/cache;
- ZIP CRC test passes;
- SHA-256 recorded;
- any performance claim includes run count, dataset, collector/heap settings and failed-run handling.
