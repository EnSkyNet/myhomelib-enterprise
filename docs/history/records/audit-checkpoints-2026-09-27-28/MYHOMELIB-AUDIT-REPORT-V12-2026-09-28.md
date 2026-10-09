# MyHomeLib — deep technical audit / V12 checkpoint

Дата: 2026-09-28  
Єдина baseline для цього аудиту: `myhomelib-enterprise-optimized-v10-checkpoint-2026-09-27.zip`

## 1. Executive summary

V12 продовжує аудит без переписування стабільного коду. Усі production changes мають конкретний regression або build-entrypoint contract; широкої архітектурної декомпозиції не робилося, тому що consolidated Maven/release runtime gate у цьому execution environment неможливо виконати через відсутність DNS/network access до Maven Central.

Кумулятивно від V10 закрито:

1. Maven developer-source wrapper checkpoint mismatch; wrapper оновлено до Maven **3.9.16** і переведено на checked-in `MavenWrapperMain`.
2. HTTP retry після truncation без `ETag`/`Last-Modified`: більше немає unsafe `Range` resume без validator.
3. UTF-16 truncation: surrogate pair і base+combining boundaries у централізованому UI cut helper.
4. Reader keyboard selection: offset більше не завершується всередині surrogate/combining sequence.
5. malformed MOBI header-length overflow: контрольований bounds failure замість low-level `ArrayIndexOutOfBoundsException` diagnostic.
6. `ReaderAnnotationCoordinator`: stale queued focus після втрати workspace ownership.
7. AI large-book sampling: head/tail/final trim більше не ріжуть surrogate/combining sequence.
8. Annotation anchor/rebind: NFC/NFD canonical-equivalence matching із поверненням raw offsets поточного тексту.
9. Canonical matcher: прибрані raw UTF-16 length prefilters, що помилково відкидали довгі NFD quotes проти коротшого NFC text.
10. Portable user-data sync conflict preview: bounded scalar preview більше не ріже surrogate/combining sequence.
11. Bootstrap diagnostic `safeMessage`: bounded user-visible message більше не ріже surrogate/combining sequence.

## 2. Maven 3.9.16 / build entrypoint

### Підтверджена проблема V10

V10 developer-source checkpoint містив Maven Wrapper JAR/properties та `mvnw`/`mvnw.cmd`, але launcher-и шукали відсутній `.mvn/maven/apache-maven-3.9.6/...`. Тобто checked-in wrapper entrypoint не був самодостатнім у межах заявленого developer-source contract.

### Red contract

`tools/supply-chain-policy-check.py` отримав deterministic contract:

- wrapper distribution pinned до Maven 3.9.16;
- Unix/Windows launcher використовують `org.apache.maven.wrapper.MavenWrapperMain`;
- launcher не залежить від `.mvn/maven/apache-maven-*`.

До fix checker падав на Unix launcher contract.

### Minimal fix

- `.mvn/wrapper/maven-wrapper.properties`: Maven 3.9.16;
- `mvnw` / `mvnw.cmd`: запуск checked-in wrapper JAR;
- developer-source docs синхронізовані з фактичним contract;
- formal-source/external compatibility minimum **Maven 3.9.6+** не піднімався без compatibility evidence.

### Verification

- `supply-chain-policy-check.py`: PASS;
- wrapper configuration містить `apache-maven-3.9.16-bin.zip`;
- `mvnw`/`mvnw.cmd` викликають `MavenWrapperMain`.

Фактичний `./mvnw -v` доходить до downloader-а, але завершується `UnknownHostException: repo.maven.apache.org`. Тому реальний Maven 3.9.16 reactor у цьому environment **не виконано і не позначено як PASS**.

## 3. HTTP resume integrity

### Дефект

Після truncated initial response без resume validator partial file міг пережити retry, а наступна ітерація — сформувати `Range` request без `If-Range`. Partial у такому випадку не був прив'язаний до стабільної remote entity.

### Regression-first

- `HttpOnlineBookDownloadAdapterTest.retriesFullDownloadWhenTruncatedResponseHasNoResumeValidator`;
- `HttpRemoteCatalogDownloadAdapterTest.retriesFullCatalogDownloadWhenTruncatedResponseHasNoResumeValidator`.

Red на V10 production code: **39 found / 37 successful / 2 failed**.

### Minimal fix

Якщо retry бачить non-empty owned partial без resume metadata/validator:

- partial очищається;
- metadata очищається;
- snapshot reset до offset 0;
- наступний запит є full request без `Range`/`If-Range`.

### Green/downstream

- targeted HTTP pair: **39/39 PASS**;
- final full `infrastructure/download` package: **99/99 PASS**;
- `online-download-behavior-check.py`: PASS;
- `online-concurrency-check.py`: PASS.

## 4. Unicode / offset correctness

### 4.1 Combining marks у shared UI truncation boundary

V10 `Utf16BoundarySupport` уже захищав surrogate pair, але cut перед combining mark міг залишити base character без його mark.

Red:

- `Utf16BoundarySupportTest.movesCutBeforeBaseCharacterWhenNextCodePointIsCombiningMark`;
- `SearchAnnotationPresentationTest.summaryTruncationDoesNotDetachCombiningMarkFromBaseCharacter`.

До fix: **8 found / 6 successful / 2 failed**.  
Після мінімального boundary adjustment: **8/8 PASS**.

### 4.2 Reader keyboard selection

`ReaderSelectionController.extendByCharacters()` міг сформувати selection offset усередині surrogate pair або між base character та combining mark.

Red: **5 found / 4 successful / 1 failed**.  
Fix: step нормалізується до підтверджених Unicode boundaries без зміни layout/render contract.

Final downstream Reader під Xvfb: **134 found / 1 skipped / 133 started / 133 successful / 0 failed**.

### 4.3 AI large-book sampling

`AiBookAssistantUiService.extract()` формував bounded sample raw `substring()` на head/tail/final limit. На фіксованому head cut (`~70%` від `AiRequest.MAX_BOOK_CONTENT_CHARS`) можна було залишити половину emoji або відокремити combining mark; tail start мав той самий ризик.

Новий regression class:

- `AiBookAssistantUiServiceUnicodeTest.largeBookSamplingDoesNotSplitSurrogatePairAtHeadBoundary`;
- `...largeBookSamplingDoesNotDetachCombiningMarkAtHeadBoundary`;
- `...largeBookSamplingDoesNotSplitSurrogatePairAtTailBoundary`.

Red на pre-fix code: **3/3 failed**.  
Minimal fix: reused existing `Utf16BoundarySupport.safeCut()` для head, tail start і final trim.  
Green: **3/3 PASS**; final full UI suite під Xvfb: **223/223 PASS**.

### 4.4 Annotation NFC/NFD canonical equivalence

`AnnotationAnchorRelocator` і cross-artifact `AnnotationReaderResolver.findRebindCandidate()` використовували raw UTF-16 equality/index search. Візуально однаковий quote міг не знайтись після canonical representation change, наприклад stored NFC `café` проти current NFD `cafe + U+0301`.

Red:

- `AnnotationModelTest.relocatorMatchesCanonicallyEquivalentQuoteAndReturnsCurrentTextOffsets`;
- `AnnotationReaderResolverTest.crossArtifactCandidateMatchesCanonicallyEquivalentQuote`.

До fix: **9 found / 7 successful / 2 failed**.

Minimal fix:

- canonical matching виконується в NFC;
- normalized character boundaries map-яться назад у raw UTF-16 offsets поточного text;
- exact normalized match приймається лише на mapped original boundaries;
- context scoring використовує фактичну raw current span length;
- cross-artifact resolver reuse-ить той самий match contract.

Після першого fix окремий reverse-direction regression виявив надто агресивні raw-length prefilters: довгий stored NFD quote (`e + combining mark`) міг бути довшим за current NFC text і відкидався до normalization.

Red reverse-direction:

- `AnnotationModelTest.relocatorMatchesLongDecomposedStoredQuoteAgainstComposedCurrentText`;
- `AnnotationReaderResolverTest.crossArtifactCandidateMatchesLongDecomposedQuoteAgainstComposedText`.

До остаточного fix: **11 found / 9 successful / 2 failed**.  
Minimal fix: прибрані лише raw `quote.length()` prefilters; normalized matcher лишився без розширення framework.  
Green: **11/11 PASS**.

Final downstream:

- domain: **32/32 PASS**;
- application: **455 found / 1 skipped / 454 started / 454 successful / 0 failed**;
- UI: **223/223 PASS**.

### 4.5 Portable sync conflict preview

`PortableSnapshotConflictService` обрізав scalar conflict preview через `substring(0, 160)`. Це user-visible text у conflict review і реально міг містити unpaired high surrogate або base character без combining mark.

Red regressions:

- `scalarConflictPreviewDoesNotSplitSurrogatePairAtLimit`;
- `scalarConflictPreviewKeepsCombiningSequenceAtLimit`.

До fix: **6 found / 4 successful / 2 failed**.  
Green: **6/6 PASS**.

### 4.6 Bootstrap bounded diagnostic message

`DesktopIntegrationSupport.safeMessage()` обрізав user-visible/technical diagnostic через `substring(0, 600)`.

Red: `safeMessageDoesNotSplitUnicodeSequenceAtLimit` — **4 found / 3 successful / 1 failed**.

Оскільки на цьому етапі вже було два bootstrap consumers з однаковим доведеним boundary contract (sync preview + diagnostic), введено лише package-local `DesktopIntegrationSupport.safeTextCut()` і reused у двох місцях. Це не generic framework і не змінює presentation length policy.

Combined targeted green: **10/10 PASS**.

## 5. Binary overflow / bounds

### MOBI metadata inspector defect

`BinaryMetadataInspector.inspectMobi()` читав unsigned 32-bit MOBI header length і cast-ив до `int`; malformed value біля `Integer.MAX_VALUE` могло переповнити `mobiStart + headerLength + 12`, обійти intended bounds guard і завершитися низькорівневим `ArrayIndexOutOfBoundsException` diagnostic.

Red: malformed MOBI set — **5 found / 4 successful / 1 failed**.

Minimal fix:

- declared length тримається як `long` до bounds validation;
- перевіряється мінімальна довжина та `(long)mobiStart + declaredHeaderLength <= record0.length`;
- malformed input повертає контрольований `IOException("Некоректний MOBI header length")`.

Green: **5/5 PASS**; final full Reader suite також green.

Інші переглянуті MOBI cover/parser paths уже мали long-safe bounds для зовнішніх розмірів; без failing contract їх не переписано.

## 6. Resource lifecycle / concurrency

### `ReaderAnnotationCoordinator` stale queued focus

Перший completion callback перевіряв current workspace context, але ставив другий `Platform.runLater` для focus без повторної ownership verification. Між callbacks workspace міг змінитися.

Red: `ReaderAnnotationCoordinatorLifecycleTest.queuedFocusDoesNotRunAfterCompletionLosesWorkspaceOwnership` — stale focus count **1**, очікувалось **0**.

Minimal fix: перед `focusReader().run()` другий queued callback повторно перевіряє `current(context)`.

Green: targeted PASS; final UI **223/223 PASS**.

Generic cancellable-task abstraction не додано.

## 7. Final source-level verification

### Clean manual cross-module compile

Через недоступний Maven distribution остаточний source state скомпільовано в нові output directories без reuse старих `.class`:

- main Java source files: **1387** — PASS;
- test Java source files: **548** — PASS;
- generated/compiled main classes: **2095**;
- generated/compiled test classes: **822**;
- Java 21 `--release 21`;
- MapStruct/Lombok annotation processing з наданого offline Maven repository.

Це compile proof, але **не заміна Maven reactor lifecycle**.

### Final module/downstream suites

- domain: **32/32 PASS**;
- application: **455 found / 1 skipped / 454 PASS**;
- bootstrap (Maven-like harness classpath, test resources, без unrelated benchmark module): **50 found / 2 skipped / 48 PASS**;
- reader під Xvfb: **134 found / 1 skipped / 133 PASS**;
- infrastructure download package: **99/99 PASS**;
- UI під Xvfb: **223/223 PASS**.

Перший aggregate bootstrap harness навмисно не враховується як product failure: спільний ручний output directory зробив unrelated `myhomelib-benchmark` Spring component видимим bootstrap classpath, а bootstrap test resources не були додані. Після приведення harness до module dependency/resource boundary усі started bootstrap tests green. Аналогічно Reader без DISPLAY відтворив `AWTError` у PDFBox raster test; під Xvfb suite green.

## 8. Static / release-oriented gates

PASS на фінальному source state:

- `implementation-completeness-check.py`;
- `static_release_check.py`;
- `xml-archive-security-check.py`;
- `supply-chain-policy-check.py`;
- `online-download-behavior-check.py`;
- `online-concurrency-check.py`;
- `async-generation-audit-check.py`;
- `managed-executor-check.py`;
- `thread-sleep-audit-check.py`;
- `stage36-reader-annotations-check.py`.

`static_release_check.py` на фінальному state:

- Java sources: **1935**;
- test sources: **548**;
- XML (POM + FXML): **49**, errors 0;
- FXML workspaces: **29**;
- handler references: **261**, missing 0;
- SQLite migrations: **63**, integrity OK;
- release packaging/CI static issues: 0.

`implementation-completeness-check.py`:

- production Java files scanned: **1202**;
- TODO/FIXME/unsupported markers: 0;
- empty public/protected methods: 0;
- exact cross-file method clones >=180 chars: 0 warnings.

## 9. Architecture checker — pre-existing baseline debt, not V12 regression

`tools/architecture-check.py` на фінальному V12 source state повертає 6 violations. Той самий checker на untouched V10 baseline повертає **ті самі 6**:

- 5 Stage-6 remote UPSERT preservation expectations;
- 1 UI non-value domain-model debt group (`DeferredMainFeatureActions`, `ReaderBookMapCoordinator`, `ReaderBookmarkCoordinator`).

Отже V12 не додає architecture-check violations. Цей debt треба triage окремо: спочатку встановити, чи checker stale, чи behavioral contract реально порушений.

## 10. Release gates, які НЕ виконані

У цьому environment не можна чесно позначити як PASS:

- `./mvnw -v` з реально завантаженим Maven 3.9.16;
- consolidated `./mvnw clean verify`;
- `-Pproduction` package;
- новий Linux `jpackage` app-image;
- stage23 release artifact validation;
- packaged launcher `--release-smoke`;
- Windows x64 acceptance.

Причина Maven gate: `UnknownHostException: repo.maven.apache.org`.  
Причина stage23 у цьому source-only environment: `dist/` не існує, оскільки production package не може бути зібраний без Maven distribution.

Жоден із цих gate-ів не підмінено manual compile/test harness.

## 11. Scope / consciously unchanged

Не змінювались без red evidence:

- GC/heap defaults — лишається V9 decision G1 + `-Xmx1024m`;
- DB schema/migrations;
- persistence public schema;
- generic async/task framework;
- organizer/converter broad architecture;
- structural decomposition великих controllers/backends;
- unproven truncation candidates (`FfmpegAudioPlaybackBackend`, `LineLayout.toString()` та інші raw `substring()` occurrences);
- parser/bounds paths, де current long/bounds guards не вдалося довести дефектними.

## 12. Checkpoint decision

V12 придатний як новий **clean-source audit checkpoint** від V10 baseline: підтверджені defects мають red-before/green-after evidence, modified/downstream source-level suites і static gates green, нових architecture violations немає.

V12 **не оголошується release candidate**, доки у network-enabled/pre-cached environment не пройдуть реальний Maven 3.9.16 `clean verify`, production packaging, stage23 та packaged launcher smoke; Windows acceptance також залишається окремим обов'язковим gate.
