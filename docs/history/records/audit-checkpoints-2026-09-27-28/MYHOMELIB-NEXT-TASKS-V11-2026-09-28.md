# MyHomeLib — залишкові задачі після V11 checkpoint

Дата: 2026-09-28  
Baseline для наступної сесії: `myhomelib-enterprise-optimized-v11-checkpoint-2026-09-28.zip`

## 1. Незмінні правила

1. correctness і поведінкові контракти — перший пріоритет;
2. defect: red regression → мінімальний fix → targeted/downstream tests;
3. не переписувати стабільний код без тестової або вимірюваної причини;
4. refactoring: один independently-testable responsibility boundary за раз;
5. не вводити generic async/framework abstraction, доки щонайменше два consumers не мають однакового lifecycle contract;
6. performance/heap/GC змінювати тільки після контрольованого A/B або workload matrix;
7. не оголошувати release gate PASS, якщо він не виконувався в реальному потрібному середовищі.

## 2. P0 — consolidated Maven 3.9.16 verification

У мережево-доступному Linux environment:

1. перевірити `./mvnw -v` → Maven **3.9.16**;
2. `./mvnw clean verify`;
3. звірити Surefire totals і modified module suites;
4. `-Pproduction` package;
5. Linux `jpackage` app-image;
6. stage23 release checker;
7. packaged launcher `--release-smoke`;
8. перевірити, що wrapper/bootstrap працює як з ASCII, так і з non-ASCII checkout path.

Не відкотити external/formal-source compatibility minimum `Maven 3.9.6+` без окремої причини. Wrapper pin і minimum supported external Maven — різні contracts.

## 3. P0 — Windows x64 release acceptance

Все ще потрібне реальне Windows x64 середовище:

- production build/package;
- launcher з ASCII/non-ASCII paths;
- packaged `--release-smoke`;
- runtime tools availability;
- plugin install/enable/disable/restart lifecycle;
- EPUB/FB2/PDF reader smoke;
- strict/full release fail-closed для incomplete licensed/bundled libmobi runtime/source bundle.

Linux не замінює Windows validation.

## 4. P1 — correctness continuation

Regression-first audit, без масових replace:

1. Unicode/offset:
   - canonical-equivalence cases у Reader/Search annotation anchors;
   - інші reachable user-visible raw truncations лише після red test;
   - окремо перевірити `AiBookAssistantUiService` head/tail sampling на surrogate/combining boundary — це candidate, не підтверджений дефект;
   - `FfmpegAudioPlaybackBackend` diagnostic і `LineLayout.toString()` лишаються unproven candidates.
2. Binary/archive:
   - malformed/truncated ZIP/EPUB/FB2/MOBI/INPX з unknown sizes і cumulative limits;
   - інші `offset + length` / `position + size` paths тільки якщо можна провести external input до overflow/bounds failure;
   - не дублювати existing long-safe guards лише заради стилю.
3. HTTP:
   - redirect + validator change;
   - changed validator між retry/resume;
   - truncated `206` із inconsistent `Content-Range`/body length;
   - concurrent target replacement під час temp-file publication/rollback.
4. Persistence/concurrency:
   - SQLite busy/retry transaction boundaries при concurrent import/edit/index update;
   - plugin package/JAR symlink/replacement/restart races.

## 5. P2 — async ownership / resource lifecycle

V11 закрив stale queued annotation focus. Далі тільки через red regression:

1. `SearchWorkspaceController` content-search replacement/cancellation;
2. `ReaderTextProviderCoordinator` completion/error callbacks після book/workspace switch або dispose;
3. решта annotation refresh/callback paths у `NewReaderWorkspaceController` / `ReaderAnnotationCoordinator`;
4. shutdown paths: cancelled Future/executor + уже queued JavaFX callbacks;
5. plugin restart/upgrade/uninstall callbacks після package replacement.

## 6. P3 — Organizer / metadata coverage

Продовжити P1 із V10 task file:

- recursive one-root scan;
- format detection + verified metadata extraction;
- deterministic dry-run plan/apply/rollback;
- author/series naming contract;
- collision/duplicate/idempotent rerun;
- fixture-tree acceptance.

Metadata coverage перевіряти format-by-format; пріоритет `MOBI/AZW/AZW3`, `PDF`, `ODT`, далі фактично підтримувані формати. Не будувати universal metadata framework наперед.

## 7. P3.1 — Converter public filename

`ConvertBookUseCase`:

- internal `artifactId` не повинен автоматично потрапляти у visible filename;
- normal target: `{safeTitle}.{ext}`;
- real collision: deterministic non-destructive naming;
- regression-first: normal conversion, repeated target, no artifactId leak, unsafe/blank title, target extensions, rollback/history contracts.

Не змінювати naming інших підсистем без окремого доказу спільного contract.

## 8. P3.2 — UI action coverage + user functions

З V10 залишаються відкритими:

- machine-checkable inventory усіх user-triggerable actions;
- 100% mapping до automated test або justified exclusion;
- UI interaction suite під Xvfb;
- `docs/MYHOMELIB-UI-ACTION-COVERAGE.md`;
- `MYHOMELIB-USER-FUNCTIONS.md` із 100% coverage inventory mapping;
- formats/operations matrix та optional/external dependencies matrix.

## 9. P4 — structural decomposition

Лише після correctness/release gates і по одному boundary:

1. `NewReaderWorkspaceController` — annotation/sidebar/search presentation ownership;
2. `SearchWorkspaceController` — result presentation/query orchestration;
3. `DesktopIntegrationBackend` — sync/export/plugin/settings boundaries;
4. `VersionedUserDataTransferAdapter` — traversal/restore/compensation;
5. `InpxImportPipeline` — parsing/batching/progress/index-sync.

Перед extraction потрібен independently-testable contract; не робити «cleanup refactor» без нього.

## 10. Existing baseline debt to triage separately

`tools/architecture-check.py` має 6 violations, і V11 підтвердив, що ті самі 6 вже є в untouched V10 baseline:

- 5 Stage 6 remote UPSERT preservation guard expectations;
- 1 UI non-value domain-model debt group (`DeferredMainFeatureActions`, `ReaderBookMapCoordinator`, `ReaderBookmarkCoordinator`).

Спочатку визначити, чи checker stale відносно поточної реалізації, чи production contract реально порушений. Не виправляти механічно під checker без behavioral evidence.

## 11. Next checkpoint gates

Перед наступною baseline:

- кожний новий defect має red-before/green-after evidence;
- modified full module suites green;
- full UI Xvfb green;
- completeness/static/XML/archive/supply-chain green;
- **реальний Maven 3.9.16 consolidated `clean verify` green**;
- production package green;
- Linux stage23 + packaged launcher smoke green, якщо packaging/runtime code змінювався;
- Windows smoke documented, якщо доступне Windows environment;
- clean ZIP не містить `target/`, `dist/`, `.git/`, `.mvn/repository/`, logs/temp/cache;
- ZIP CRC PASS і SHA-256 записаний.
