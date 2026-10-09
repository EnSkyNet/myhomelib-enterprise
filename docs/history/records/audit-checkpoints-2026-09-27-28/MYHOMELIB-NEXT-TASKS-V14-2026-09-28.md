# MyHomeLib — залишкові задачі після V14 audit checkpoint

Дата: 2026-09-28  
Baseline для наступної сесії: `myhomelib-enterprise-optimized-v14-checkpoint-2026-09-28.zip`

## 1. Незмінні правила

1. correctness/contracts first;
2. defect: RED regression → minimal fix → targeted tests → downstream contracts;
3. не переписувати стабільний код без підтвердженої причини;
4. refactor — один independently-testable responsibility boundary за раз;
5. release gate не називати PASS, якщо потрібний runtime/toolchain реально не запускався.

## 2. P0 — unblock real Maven 3.9.16 gate

У середовищі з Maven 3.9.16 distribution або working DNS:

1. `./mvnw -v` → Maven 3.9.16;
2. `./mvnw clean verify`;
3. зафіксувати aggregate Surefire totals;
4. окремо підтвердити `application`, `infrastructure`, `reader`, `ui`, `bootstrap`;
5. `./mvnw -Pproduction -DskipTests package` або чинна production packaging command;
6. Linux `jpackage` app-image;
7. `tools/stage23-cross-platform-release-check.py` на реальному `dist/`;
8. packaged launcher `--release-smoke`;
9. ASCII та non-ASCII checkout paths.

Поточний environment: `./mvnw -v` → `UnknownHostException: repo.maven.apache.org`; dependency shards не містять Maven 3.9.16 distribution.

## 3. P0 — Windows x64 acceptance

Залишається реальне Windows x64 виконання production build/package, launcher smoke, plugin lifecycle, EPUB/FB2/PDF Reader smoke та strict licensed/bundled runtime checks.

## 4. P1 — next regression-first audits

- HTTP: redirect + validator changes, Range ignored with `200` after partial, deterministic final-target replacement race variants.
- Binary/archive: тільки нові malformed/truncated fixtures, які доводять порушення actual/cumulative bound.
- Persistence: deterministic concurrent `SQLITE_BUSY` regression для multi-statement atomicity/cancellation; не змінювати retry framework без такого тесту.
- Unicode: продовжувати лише user-visible reachable raw truncations; не mass-replace `substring()`.

## 5. P2 — async lifecycle

Поточний audit не відтворив defect у `SearchWorkspaceController` та `ReaderTextProviderCoordinator`.

Далі тільки через deterministic stale-completion reproduction:

- queued callbacks після dispose/shutdown;
- `NewReaderWorkspaceController` / `ReaderAnnotationCoordinator` refresh paths;
- plugin restart/upgrade/uninstall callbacks після package replacement.

## 6. P3 — user scenarios

Залишаються:

- Organizer recursive mixed-library end-to-end fixture scenario;
- machine-checkable UI action coverage + documentation consistency;
- user-facing functions/formats/optional dependency matrix.

Converter visible filename task у V13/V14 уже має regression coverage; не відкривати повторно без нового defect.

## 7. Architecture debt

`tools/architecture-check.py` тепер має один підтверджений debt group:

- `DeferredMainFeatureActions`;
- `ReaderBookMapCoordinator`;
- `ReaderBookmarkCoordinator`.

Stage-6 UPSERT preservation false positives закриті checker regression-тестом; не повертати перевірку до `JdbcBatchWriter`.

## 8. P4 — staged decomposition, тільки після P0

Порядок не змінювати без доказу:

1. `NewReaderWorkspaceController` (1 932 lines) — annotation/sidebar/search presentation ownership;
2. `SearchWorkspaceController` (1 254) — result presentation/query orchestration;
3. `DesktopIntegrationBackend` (1 367) — sync/export/plugin/settings boundaries;
4. `VersionedUserDataTransferAdapter` (1 225) — traversal/restore/compensation;
5. `InpxImportPipeline` (1 124) — parsing/batching/progress/index-sync.

Для кожного кроку:

- перед extraction — characterization/contract tests;
- extract один responsibility boundary без behavior change;
- targeted tests;
- повний Maven module suite;
- UI/Reader Xvfb suite, якщо boundary її торкається;
- architecture/static gates;
- тільки після green переходити до наступного boundary.

## 9. Checkpoint acceptance

Перед наступною baseline:

- кожен defect має RED-before/GREEN-after evidence;
- real Maven 3.9.16 `clean verify` green;
- modified module suites green у Maven lifecycle;
- production package + Linux stage23 + launcher smoke green;
- completeness/static/XML/archive/supply-chain green;
- architecture debt або усунений через окремі contract-backed зміни, або явно зафіксований без нових regressions;
- clean source ZIP без `.git`, `.mvn/repository/`, `target`, `dist`, temp/cache/logs; Maven wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper`) зберегти;
- ZIP CRC PASS і SHA-256 записаний.
