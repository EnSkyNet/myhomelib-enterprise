# MyHomeLib — залишкові задачі після V12 checkpoint

Дата: 2026-09-28  
Baseline для наступної сесії: `myhomelib-enterprise-optimized-v12-checkpoint-2026-09-28.zip`

## 1. Незмінні правила

1. correctness і поведінкові контракти — перший пріоритет;
2. defect: red regression → мінімальний fix → targeted/downstream tests;
3. не переписувати стабільний код без тестової або вимірюваної причини;
4. refactoring: один independently-testable responsibility boundary за раз;
5. generic async/framework abstraction — лише коли щонайменше два consumers мають доведений однаковий lifecycle contract;
6. performance/heap/GC — тільки контрольовані A/B або workload matrix;
7. release gate не вважати PASS, якщо він не виконувався у потрібному runtime/environment;
8. wrapper pin Maven 3.9.16 і formal-source external minimum Maven 3.9.6+ — різні contracts; не змішувати їх без compatibility evidence.

## 2. P0 — real Maven 3.9.16 consolidated verification

У network-enabled або pre-cached Linux environment:

1. `./mvnw -v` → Maven **3.9.16**;
2. `./mvnw clean verify`;
3. зафіксувати aggregate Surefire totals;
4. підтвердити modified modules (`domain`, `application`, `bootstrap`, `reader`, `ui`, `infrastructure`) у штатному Maven lifecycle;
5. `./mvnw -Pproduction -DskipTests package` або чинна production packaging команда;
6. Linux `jpackage` app-image;
7. `tools/stage23-cross-platform-release-check.py` на реальному `dist/`;
8. packaged launcher `--release-smoke`;
9. wrapper/build з ASCII та non-ASCII checkout path.

Поточний V12 environment не має DNS до `repo.maven.apache.org`; manual compile/test evidence не замінює цей gate.

## 3. P0 — Windows x64 release acceptance

Потрібне реальне Windows x64 середовище:

- production build/package;
- launcher з ASCII/non-ASCII paths;
- packaged `--release-smoke`;
- runtime tools availability;
- plugin install/enable/disable/restart lifecycle;
- EPUB/FB2/PDF Reader smoke;
- strict/full release fail-closed для incomplete licensed/bundled libmobi runtime/source bundle.

Linux evidence не є заміною Windows runtime validation.

## 4. P1 — Unicode / offset continuation

V12 закрив UI combining cuts, Reader keyboard boundaries, AI sampling, annotation NFC/NFD anchors, portable conflict preview та bootstrap diagnostics.

Далі — тільки regression-first для реально reachable path:

1. audit інших user-visible raw truncations; не mass-replace `substring()`;
2. `FfmpegAudioPlaybackBackend` diagnostic та `LineLayout.toString()` — усе ще **unproven candidates**;
3. перевірити bounded MCP/content/API previews лише якщо можна довести user-visible malformed UTF-16 output;
4. canonical-equivalence cases із повторюваними quote/context та складнішими combining sequences — лише якщо поточний matcher дає неправильний raw offset/rebind decision;
5. не перетворювати поточний matcher у general Unicode/grapheme framework без окремих failing contracts.

## 5. P1 — Binary/archive bounds

Regression-first:

- malformed/truncated ZIP/EPUB/FB2/MOBI/INPX з unknown sizes та cumulative limits;
- reachable `offset + length`, `position + size`, `count * elementSize` overflow paths;
- archive-entry declared size vs actual streamed bytes;
- nested/single-book archive limits там, де це вже product contract;
- не змінювати long-safe guards лише заради стилю.

MOBI metadata header-length overflow у `BinaryMetadataInspector` уже закритий V12.

## 6. P1 — HTTP resume / publication integrity

V12 уже закрив retry partial без validator. Далі:

1. redirect + validator change;
2. validator change між retry/resume;
3. truncated `206` / inconsistent `Content-Range` / short body;
4. server, що ігнорує `Range` і повертає `200` після partial;
5. concurrent final-target replacement під час temp-file publication/rollback;
6. symlink/replacement race тільки через deterministic local fixture.

Reuse current safe-publication/resume contracts; не будувати новий download framework без спільного доведеного defect pattern.

## 7. P1 — Persistence / concurrency

Потрібен deterministic concurrency regression перед fix:

- SQLite busy/retry boundaries при concurrent import/edit/index update;
- multi-statement write atomicity там, де `SQLITE_BUSY` може залишити partial state;
- cancellation/rollback при import/index sync;
- plugin package/JAR symlink/replacement/restart races.

## 8. P2 — async ownership / resource lifecycle

V12 закрив stale queued annotation focus. Далі тільки через stale-completion reproduction:

1. `SearchWorkspaceController` content-search replacement/cancellation;
2. `ReaderTextProviderCoordinator` callback після book/workspace switch або dispose;
3. annotation async refresh/callback paths у `NewReaderWorkspaceController` / `ReaderAnnotationCoordinator`;
4. shutdown: cancelled `Future`/executor + вже queued JavaFX callbacks;
5. plugin restart/upgrade/uninstall callbacks після package replacement.

Не вводити generic cancellable-task abstraction без двох однакових lifecycle contracts.

## 9. P3 — Organizer recursive mixed-library scenario

З V10 task file залишається відкритим повний user scenario:

- one-root recursive scan;
- supported-format detection;
- verified metadata extraction (`Author`, `Title`, `Series`, `SeriesNo`) format-by-format;
- normalization/filesystem-safe components;
- deterministic dry-run plan;
- `{Author}/{Series}/{SeriesNo:00} - {Title}.{ext}` з documented fallbacks;
- collision/duplicate/idempotent rerun;
- non-destructive apply/rollback;
- fixture-tree acceptance із mixed formats/subfolders.

Metadata priority: `MOBI/AZW/AZW3`, `PDF`, `ODT`, далі фактично підтримувані формати. Не будувати universal metadata framework наперед.

## 10. P3.1 — Converter public filename

`ConvertBookUseCase`:

- internal `artifactId` не повинен витікати у normal visible filename;
- normal case: `{safeTitle}.{ext}`;
- collision: deterministic non-destructive naming;
- no silent overwrite;
- regression-first: normal conversion, existing target, artifactId leak, blank/unsafe title, different extensions, rollback/history.

Не змінювати naming інших підсистем без окремого contract.

## 11. P3.2 — UI action coverage + user-facing functions

З V10 залишаються відкритими:

- machine-checkable inventory user-triggerable actions;
- 100% mapping до test або justified exclusion;
- interaction suite під Xvfb без screen-coordinate automation;
- `docs/MYHOMELIB-UI-ACTION-COVERAGE.md`;
- `MYHOMELIB-USER-FUNCTIONS.md`;
- user-visible formats/operations matrix;
- optional/external dependency matrix;
- deterministic consistency check між action inventory та документацією.

## 12. P4 — structural decomposition

Починати лише після real Maven/release gates і лише по одному independently-testable boundary:

1. `NewReaderWorkspaceController` — remaining annotation/sidebar/search presentation ownership;
2. `SearchWorkspaceController` — result presentation/query orchestration;
3. `DesktopIntegrationBackend` — sync/export/plugin/settings boundaries;
4. `VersionedUserDataTransferAdapter` — traversal/restore/compensation;
5. `InpxImportPipeline` — parsing/batching/progress/index-sync.

Не робити cleanup-refactor без окремого тестованого responsibility contract.

## 13. Existing baseline debt to triage separately

`tools/architecture-check.py` має ті самі 6 violations у V12 і untouched V10:

- 5 Stage-6 remote UPSERT preservation expectations;
- 1 UI non-value domain-model debt group (`DeferredMainFeatureActions`, `ReaderBookMapCoordinator`, `ReaderBookmarkCoordinator`).

Спершу визначити checker-vs-behavior truth; не «виправляти під checker» механічно.

## 14. Next checkpoint gates

Перед наступною baseline:

- кожен новий defect має red-before/green-after evidence;
- modified full module suites green у штатному Maven lifecycle;
- full UI/Reader render suites green під Xvfb;
- completeness/static/XML/archive/supply-chain green;
- **реальний Maven 3.9.16 `clean verify` green**;
- production package green;
- Linux stage23 + packaged launcher smoke green;
- Windows smoke documented, якщо Windows environment доступне;
- clean ZIP виключає `target/`, `dist/`, `.git/`, `.mvn/repository/`, logs/temp/cache, `__pycache__`;
- ZIP CRC PASS;
- SHA-256 записаний.
