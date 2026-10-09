# MyHomeLib — V8 deep technical audit report

Дата: 2026-09-27  
Baseline: `myhomelib-enterprise-optimized-v7-checkpoint-2026-09-27.zip`  
Ціль: продовжити enterprise-аудит без overengineering, із пріоритетом correctness, перевірюваної дедуплікації та вимірюваних memory/performance рішень.

## 1. Підсумок checkpoint

V8 checkpoint сформовано лише після повторного закриття UI, модульних/реакторних тестів, статичних security/release gates, Linux packaged smoke і нового packaged-memory baseline.

Фінальний стан:

- `myhomelib-ui` під Xvfb: **197 tests, 0 failures, 0 errors, 0 skipped**;
- повний `./mvnw -o clean verify` під Xvfb: **BUILD SUCCESS, 20/20 modules**;
- сукупно за Surefire XML: **1726 tests, 0 failures, 0 errors, 17 skipped, 526 test classes**;
- `myhomelib-infrastructure`: **632 tests, 0 failures, 0 errors, 9 skipped**;
- `myhomelib-application`: **453 tests, 0 failures, 0 errors, 1 skipped**;
- `myhomelib-reader`: **131 tests, 0 failures, 0 errors, 1 skipped**;
- `myhomelib-bootstrap`: **44 tests, 0 failures, 0 errors, 2 skipped**;
- production reactor `package -DskipTests`: **20/20 modules BUILD SUCCESS**;
- implementation completeness: **PASS**;
- static release/XML/FXML/SQLite migration integrity: **PASS**;
- XML/archive security: **PASS**;
- supply-chain policy: **PASS**;
- Linux `jpackage --type app-image`: **PASS**;
- stage23 release artifact check: **PASS**;
- packaged launcher `--release-smoke`: **PASS**.

17 skipped tests — це явно gated real-data / external-tool / Windows-only probes та benchmark guards; вони не були зараховані як PASS.

## 2. Correctness defects: regression test → minimal fix → downstream

### 2.1 Canonically equivalent Unicode archive paths

**Дефект.** Логічно однакові archive-entry paths у NFC/NFD могли не збігатися, хоча користувацьке ім'я є канонічно еквівалентним Unicode-текстом.

**Regression barrier.** Додано тест на canonical-equivalent logical paths.

**Мінімальне виправлення.** `ArchiveEntryNameSupport.sameLogicalPath(...)` нормалізує decoded logical path у `Normalizer.Form.NFC` перед case-insensitive comparison. Архівні safety limits/path traversal rules не змінювалися.

**Перевірка.** Regression red → green; archive downstream tests і повний reactor verify — PASS.

### 2.2 Canonically equivalent delimited tokens in archive names

**Дефект.** `containsDelimitedToken(...)` міг пропустити токен, якщо filename і token використовували різні canonical Unicode forms.

**Regression barrier.** Додано окремий canonical-equivalence token test.

**Мінімальне виправлення.** Filename і token нормалізуються в NFC до побудови Unicode-aware regex; delimiter semantics збережено.

**Перевірка.** Regression red → green; downstream/full verify — PASS.

### 2.3 Search annotation summary could split a surrogate pair

**Дефект.** 140-character UI summary truncation використовувала UTF-16 `substring` boundary і могла завершувати рядок висячим high surrogate, якщо emoji/astral code point перетинав межу обрізання.

**Regression barrier.** Behavioral test із supplementary Unicode character на boundary спочатку відтворив дефект.

**Мінімальне виправлення.** Межа `137` відсувається на один UTF-16 code unit лише коли вона розрізає валідну high/low surrogate pair. Інша presentation-логіка не змінена.

**Перевірка.** Regression red → green; UI suite під Xvfb — PASS.

### 2.4 Portable restore could persist a dangling surrogate at 10,000-character limit

**Дефект.** `VersionedUserDataTransferAdapter.truncate(...)` міг обрізати imported user-data string між high/low surrogate перед записом у SQLite.

**Regression barrier.** Behavioral restore test із 9999 BMP chars + supplementary character відтворив висячий surrogate до fix.

**Мінімальне виправлення.** При `MAX_STRING_LENGTH` boundary кінець зменшується на один code unit тільки якщо boundary розрізає surrogate pair.

**Перевірка.** Targeted regression — green; backup downstream **18/18**; infrastructure/full verify — PASS.

## 3. Responsibility-boundary refactoring

Жоден великий клас не переписувався цілком. Кожна зміна — один локальний responsibility boundary.

### 3.1 `ReaderBookMapCoordinator`

Із `NewReaderWorkspaceController` винесено pure projection/orchestration для Book Map rows. Reader lifecycle, navigation, rendering, bookmarks/TTS/text-provider logic не переносилися.

- новий production support: `ReaderBookMapCoordinator`;
- новий behavioral test: `ReaderBookMapCoordinatorTest`;
- downstream UI suite: PASS.

`NewReaderWorkspaceController` після extraction: **1937 lines**. Це все ще великий controller, але подальшу декомпозицію слід продовжувати лише окремими responsibility boundaries.

### 3.2 `SearchAnnotationPresentation`

Із `SearchWorkspaceController` винесено pure annotation row summary/details projection. Query lifecycle, cancellation/current-query guards, result loading та Smart Collection scope не змінені.

- новий support: `SearchAnnotationPresentation`;
- новий test: `SearchAnnotationPresentationTest`;
- одночасно regression barrier для surrogate-boundary defect;
- `SearchWorkspaceController`: **1251 lines**.

### 3.3 `PortableUserDataManifestValidator`

Із `VersionedUserDataTransferAdapter` винесено validation current portable manifest header/structure. Restore traversal, DB writes, identity resolution і rollback semantics залишилися в adapter.

- новий validator + contract tests;
- `VersionedUserDataTransferAdapter`: **1225 lines**;
- targeted backup tests і full infrastructure/reactor verify — PASS.

### 3.4 `InpxSearchStateSupport`

Із `InpxImportPipeline` винесено лише search-state policy: fingerprint model/version, classification і рішення, коли потрібне book/search-state persistence. JDBC load/persist, batching, transaction ownership і import orchestration не переносилися.

- `InpxSearchStateSupportTest` + `InpxImportPipelineTest`: **22/22 PASS** у targeted set;
- `InpxImportPipeline`: **1124 lines**.

### 3.5 `WebDavSyncStateStore`

Із `DesktopIntegrationBackend` винесено лише settings-backed persistence WebDAV sync state: last local hash, cursors, commit/reset state, stable device ID.

Network flow, conflict resolution, pull/push sequencing, plugin/AI orchestration залишилися в backend.

- targeted state/backend support set: **6/6 PASS**;
- full bootstrap: **44 tests, 0 failures/errors, 2 external-tool skips**;
- `DesktopIntegrationBackend`: **1390 lines**.

## 4. Structural clone audit

Literal checker уже має **0 exact cross-file method clones >=180 chars**, тому додатково переглянуто structural/token candidates і їхній semantic contract.

Найпомітніші пари після normalized/token review:

| Candidate | Structural signal | Рішення |
| --- | ---: | --- |
| DeepL vs Google translation providers | 9 shared 140-token windows | **Не виносити далі**: shared HTTP boundary уже існує; auth/endpoint/request/response semantics provider-specific. |
| MetadataProviderException vs TextProviderException | 6 | **Не виносити**: паралельні domain contracts, shared superclass не прибирає складний алгоритм. |
| ComicReaderView vs PdfReaderView | 6 | **Не виносити далі**: lifecycle already shared через paged-reader coordinators; control/render semantics різні. |
| LibMobiEngine vs PandocEngine | 5 | **Не виносити**: CLI/process contracts і tool-specific failure semantics різні. |
| MetadataRequestContext vs TextProviderRequestContext | 2 | **Не узагальнювати**: domain-specific request contracts. |
| ConversionPathSafety vs EpubPathSafety | 1 | **Не об'єднувати** без спільного change pressure: safety domains мають різні callers/invariants. |

Натомість реальні shared-policy candidates із divergence risk були винесені окремо: `InpxSearchStateSupport` і `WebDavSyncStateStore`.

Висновок: механічне зменшення LOC/кількості схожих блоків не використовувалося як метрика якості.

## 5. Packaged memory baseline

### Методика

- Linux `jpackage` app-image, актуальний V8 source;
- однаково підготовлений isolated profile/library/index seed для повторюваних запусків;
- readiness — реальне main window **1200×800**, а не splash;
- 5 s idle після readiness;
- explicit full GC перед зняттям показників;
- primary run — звичайний packaged runtime;
- окремий diagnostic run — той самий app-image/profile state з `-XX:NativeMemoryTracking=summary`, щоб не змішувати NMT overhead із primary RSS/PSS.

### Primary packaged run (без NMT)

- RSS: **414,264 kB ≈ 404.55 MiB**;
- PSS: **399,052 kB ≈ 389.70 MiB**;
- private dirty: **364,992 kB ≈ 356.44 MiB**;
- private clean: **20,560 kB ≈ 20.08 MiB**;
- live Java heap after full GC: **62,018 kB ≈ 60.56 MiB**;
- heap committed: **79,872 kB ≈ 78.00 MiB**;
- metaspace used: **79,139 kB ≈ 77.28 MiB**;
- loaded classes: **14,372**;
- OS thread count: **57**.

### Diagnostic NMT run

- RSS: **398,124 kB ≈ 388.79 MiB**;
- PSS: **382,912 kB ≈ 373.94 MiB**;
- NMT total committed: **263,771 kB ≈ 257.59 MiB**;
- Java Heap committed: **78,848 kB ≈ 77.00 MiB**;
- Metaspace committed: **69,834 kB ≈ 68.20 MiB**;
- Class committed: **12,306 kB ≈ 12.02 MiB**;
- Thread committed: **3,284 kB ≈ 3.21 MiB** (`40` JVM-tracked threads in NMT);
- Code committed: **23,781 kB ≈ 23.22 MiB**;
- GC committed: **44,006 kB ≈ 42.97 MiB**;
- NMT tracking overhead: **4,290 kB ≈ 4.19 MiB**.

Run-to-run RSS/PSS variance помітна, тому ці два запуски **не використовуються як A/B порівняння один проти одного**. Новий baseline також не є доказом причинної різниці від старого V7 baseline: startup/profile state між циклами не був зафіксований як strict paired A/B.

Жодних змін default GC або `-Xmx1024m` у V8 не прийнято.

## 6. Final verification evidence

| Gate | Результат |
| --- | --- |
| Full UI under Xvfb | **197/197 PASS** |
| Consolidated `clean verify` under Xvfb | **20/20 modules BUILD SUCCESS**, 03:07 |
| Aggregate Surefire | **1726 tests; 0 failures; 0 errors; 17 skipped** |
| Infrastructure | **632 tests; 0 failures/errors; 9 skipped** |
| Application | **453 tests; 0 failures/errors; 1 skipped** |
| Reader | **131 tests; 0 failures/errors; 1 skipped** |
| Bootstrap | **44 tests; 0 failures/errors; 2 skipped** |
| Architecture | **14/14 PASS** |
| E2E | **15/15 PASS** |
| Production reactor package | **20/20 BUILD SUCCESS** |
| Implementation completeness | **PASS**, production Java 1199, exact method clones >=180 chars: 0 |
| Static release | **PASS**, 49 XML/POM/FXML, 29 FXML workspaces, 261 handlers, 63 SQLite migrations |
| XML/archive security | **PASS** |
| Supply-chain policy | **PASS** |
| Linux jpackage app-image | **PASS** |
| Stage23 artifact check | **PASS** |
| Packaged launcher smoke | **PASS** |

## 7. Що свідомо не змінювалося

- default G1 GC;
- `-Xmx1024m`;
- stable provider-specific code тільки заради зовнішньої схожості;
- entire `NewReaderWorkspaceController`, `DesktopIntegrationBackend`, `SearchWorkspaceController`, `VersionedUserDataTransferAdapter`, `InpxImportPipeline` або organizer use case;
- network sync/conflict semantics під час WebDAV state extraction;
- INPX JDBC transaction/batch lifecycle під час search-state extraction.

Це відповідає правилу: performance/memory зміни — лише після чесного A/B; refactoring — один responsibility boundary за раз.

## 8. Залишкові обмеження

1. Реальний Windows x64 `jpackage`/launcher/runtime smoke не може бути чесно підтверджений у Linux-середовищі.
2. Fair G1 vs Serial A/B на однакових prepared homes + production-scale workloads ще не виконаний.
3. `-Xmx` 512/768/1024 MiB benchmark на великому INPX + Lucene + reader workload ще не виконаний.
4. Real-data probes, для яких dataset/tool/platform не наданий у runtime, залишаються skipped; вони не рахуються як PASS.
5. Великі класи все ще потребують поступової декомпозиції, але тільки після визначення вузького responsibility boundary і behavioral tests.

## 9. V8 code delta від V7

Git diff від V7 baseline до checkpoint до додавання цього звіту:

- **18 source/test paths changed**;
- **861 insertions, 398 deletions**;
- нові reusable/support boundaries: `ReaderBookMapCoordinator`, `SearchAnnotationPresentation`, `PortableUserDataManifestValidator`, `InpxSearchStateSupport`, `WebDavSyncStateStore`;
- чотири Unicode/cut-boundary correctness fixes захищені regression tests.

Clean source package формується без `target/`, `dist/`, `.git/`, `.mvn/repository/` та runtime temp/log artifacts. CRC і SHA-256 перевіряються окремо після створення архіву.
