# MyHomeLib — завдання для продовження після V8 checkpoint

Дата фіксації: 2026-09-27

## 1. Baseline

Єдиний baseline для наступної сесії:

`myhomelib-enterprise-optimized-v8-checkpoint-2026-09-27.zip`

Не повертатися до V7 або проміжних worktree/archive як до джерела коду.

## 2. Незмінні правила роботи

1. correctness і behavioral contracts — перший пріоритет;
2. defect: **regression test → minimal fix → targeted/downstream tests**;
3. refactoring: **один responsibility boundary за раз**;
4. не переписувати стабільний код без вимірюваної або test-confirmed причини;
5. structural deduplication робити за shared contract/divergence risk, а не за механічною схожістю;
6. performance/memory рішення приймати тільки після fair A/B;
7. не змінювати default GC або `-Xmx1024m` без production-scale benchmark;
8. після cross-module extraction — targeted tests + `-am` compile/package + completeness/static checks;
9. не повертати видалені eager/duplicated mechanisms.

## 3. Поточний перевірений V8 state

### Build/tests

- full UI under Xvfb: **197 tests, 0 failures/errors/skips**;
- consolidated `./mvnw -o clean verify` under Xvfb: **BUILD SUCCESS, 20/20 modules**;
- aggregate Surefire: **1726 tests, 0 failures, 0 errors, 17 skipped, 526 test classes**;
- infrastructure: **632 tests, 0 failures/errors, 9 skipped**;
- application: **453 tests, 0 failures/errors, 1 skipped**;
- reader: **131 tests, 0 failures/errors, 1 skipped**;
- bootstrap: **44 tests, 0 failures/errors, 2 skipped**;
- architecture: **14/14 PASS**;
- E2E: **15/15 PASS**;
- production reactor package: **20/20 BUILD SUCCESS**.

### Static/security/release

- implementation-completeness: **PASS**, production Java files 1199;
- exact cross-file method clones >=180 chars: **0**;
- static release: **PASS**;
- 49 XML/POM/FXML, 29 FXML workspaces, 261 handler refs, 0 missing;
- SQLite migrations: 63, integrity OK;
- XML/archive security: **PASS**;
- supply-chain policy: **PASS**;
- Linux jpackage app-image: **PASS**;
- stage23 artifact validation: **PASS**;
- packaged `--release-smoke`: **PASS**.

## 4. V8 changes that must not be reverted

### Correctness

- NFC canonical-equivalence handling in `ArchiveEntryNameSupport.sameLogicalPath`;
- NFC canonical-equivalence handling in `containsDelimitedToken`;
- surrogate-safe truncation in `SearchAnnotationPresentation.summary`;
- surrogate-safe 10,000-char truncation in `VersionedUserDataTransferAdapter`.

### Responsibility boundaries

- `ReaderBookMapCoordinator`;
- `SearchAnnotationPresentation`;
- `PortableUserDataManifestValidator`;
- `InpxSearchStateSupport`;
- `WebDavSyncStateStore`.

Не зливати ці responsibilities назад у великі controllers/adapters/backend.

## 5. Packaged memory baseline V8

Primary packaged run, без NMT, main window ready + 5 s idle + full GC:

- RSS: **414,264 kB ≈ 404.55 MiB**;
- PSS: **399,052 kB ≈ 389.70 MiB**;
- private dirty: **364,992 kB ≈ 356.44 MiB**;
- live heap: **62,018 kB ≈ 60.56 MiB**;
- committed heap: **79,872 kB ≈ 78.00 MiB**;
- metaspace used: **79,139 kB ≈ 77.28 MiB**;
- loaded classes: **14,372**;
- OS threads: **57**.

Diagnostic NMT run:

- total committed: **263,771 kB ≈ 257.59 MiB**;
- Java Heap: **78,848 kB committed**;
- Metaspace: **69,834 kB committed**;
- Class: **12,306 kB committed**;
- Thread: **3,284 kB committed**, 40 NMT JVM threads;
- Code: **23,781 kB committed**;
- GC: **44,006 kB committed**;
- NMT overhead: **4,290 kB**.

Не трактувати різницю між runs або між V7/V8 як causal improvement/regression без paired A/B.

## 6. P0 — platform release evidence

1. Виконати **реальний Windows x64** build/package/runtime smoke у Windows environment:
   - jpackage app-image;
   - launcher startup;
   - `--release-smoke`;
   - production profile;
   - UTF-8/non-ASCII paths;
   - Windows DPAPI integration probe;
   - optional libmobi behavior/fail-closed full-release contract.
2. Якщо будь-який source/package script буде змінено — повторити Linux static gates, package, stage23 і smoke.

## 7. P1 — fair memory/performance A/B

### A. G1 vs Serial GC

Тільки paired benchmark:

- два окремі, але byte-equivalent prepared `user.home`;
- однакові SQLite/Lucene state;
- однакові JVM options, крім GC;
- однаковий main-window readiness signal та idle interval;
- мінімум кілька повторів, не один run.

Workloads:

1. packaged cold/warm startup;
2. повний production-scale INPX import (цільовий dataset ~707k rows, якщо доступний);
3. Lucene rebuild + representative searches;
4. large-book reader search/navigation;
5. UI pause/responsiveness observation.

Збирати RSS/PSS, peak RSS, live/committed heap, GC count/pauses, wall-clock, CPU, threads.

Serial GC можна робити default лише за стабільного memory win без неприйнятних latency/throughput regressions.

### B. `-Xmx` 512/768/1024 MiB

Не змінювати default до завершення однакового benchmark matrix:

- wall-clock;
- peak heap/RSS/PSS;
- GC pauses/collections;
- import rows imported/skipped/errors;
- DB/index reconciliation;
- reader/search completion and responsiveness.

## 8. P2 — structural clone audit continuation

Поточні top token candidates уже переглянуті. Не створювати abstractions для:

- DeepL/Google provider-specific HTTP semantics поверх уже shared HTTP boundary;
- PDF/Comic UI rendering, де paged lifecycle already shared;
- exception/request-context pairs лише заради однакової форми;
- LibMobi/Pandoc CLI flows без спільного failure/ownership contract;
- trivial mapper/load-use-case boilerplate.

Наступний аудит концентрувати на **складних mutable contracts**:

1. filesystem plan/apply/rollback;
2. cancellation/current-context guards;
3. restore traversal/DB write transaction boundaries;
4. import batch resource ownership;
5. plugin package validation/install lifecycle;
6. SQLite/Lucene coordinated update lifecycle.

Extraction робити лише якщо є shared invariant або реальний divergence risk.

## 9. P3 — large-class decomposition, один boundary за раз

Поточні кандидати за розміром:

1. `NewReaderWorkspaceController` — **1937 lines**. Наступний boundary шукати серед sidebar/search/annotation UI orchestration, але не чіпати вже винесені bookmarks/text-provider/TTS/autosave/Book Map responsibilities.
2. `DesktopIntegrationBackend` — **1390 lines**. Після WebDAV state extraction окремо оцінити plugin package/install lifecycle; не змішувати його з sync/AI support.
3. `SearchWorkspaceController` — **1251 lines**. Оцінити query state/current-query cancellation або result-section mapping як окремі boundaries; annotation presentation уже винесено.
4. `VersionedUserDataTransferAdapter` — **1225 lines**. Оцінити restore row/application boundary або traversal/transaction rollback, не будувати generic restore framework.
5. `ApplyLibraryOrganizationUseCase` — **1134 lines**. Оцінити filesystem plan/apply/rollback transaction boundary з fail-closed publication tests.
6. `InpxImportPipeline` — **1124 lines**. Search-state policy вже винесено; наступний extraction — лише якщо parsing/batching/resource ownership має самодостатній contract.

Після кожного extraction:

- focused unit/contract test;
- existing consumer/downstream tests;
- `-pl <module> -am` compile/package;
- completeness check;
- full module suite, якщо змінено lifecycle/concurrency/persistence behavior.

## 10. P4 — hidden correctness defects

Продовжити test-first пошук у:

- Unicode code-point/grapheme boundaries, canonical equivalence, surrogate pairs, combining marks;
- integer overflow: `offset + length`, `position + size`, `start + count`;
- malformed/truncated ZIP/EPUB/FB2/MOBI/INPX;
- HTTP resume + redirect + ETag/Last-Modified validator changes + truncated body;
- temp publication / atomic move / rollback races;
- cancellation/shutdown/executor ownership;
- SQLite busy/retry transaction boundaries під concurrent import/edit/index update;
- plugin JAR ZIP/path/signature/manifest/security boundaries.

Для кожного реального дефекту: **red regression → minimal fix → downstream**.

## 11. Criteria for V9 checkpoint

Не формувати V9 baseline, доки:

- усі нові defects мають regression tests;
- modified module suites — 0 failures/errors;
- full UI under Xvfb — 0 failures/errors;
- completeness/static/XML/archive/supply-chain checks — PASS;
- production reactor package — BUILD SUCCESS;
- consolidated clean verify — PASS, якщо environment дозволяє;
- Linux packaged app smoke — PASS після packaging/source changes;
- clean ZIP не містить `target/`, `dist/`, `.git/`, `.mvn/repository/`, temp/log/cache runtime files;
- ZIP CRC — PASS;
- SHA-256 зафіксований;
- performance/memory claims мають paired A/B evidence, а не одиничні runs.
