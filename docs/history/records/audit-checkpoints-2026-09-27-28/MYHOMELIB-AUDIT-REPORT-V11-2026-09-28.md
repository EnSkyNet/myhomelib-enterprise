# MyHomeLib — deep technical audit / V11 checkpoint

Дата: 2026-09-28  
Baseline: `myhomelib-enterprise-optimized-v10-checkpoint-2026-09-27.zip`

## 1. Executive summary

V11 продовжує V10 без широкого переписування стабільного коду. Зміни обмежені підтвердженими correctness/lifecycle дефектами, Maven build-entrypoint і regression/static contracts.

Закрито чотири correctness-дефекти та один build-entrypoint дефект:

1. HTTP retry після обірваної відповіді без `ETag`/`Last-Modified` міг повторно використати partial через `Range` без валідатора.
2. Unicode truncation helper не розривав surrogate pair, але міг відокремити base character від combining mark.
3. Reader keyboard selection міг ставити offset усередині surrogate pair / combining sequence.
4. malformed MOBI `headerLength` міг переповнити `int` у metadata inspector і витекти як `ArrayIndexOutOfBoundsException`-diagnostic.
5. `ReaderAnnotationCoordinator` міг виконати відкладений stale focus після втрати workspace ownership.

Окремо Maven wrapper оновлено з 3.9.6 до **3.9.16** і усунуто checkpoint mismatch: `mvnw`/`mvnw.cmd` більше не вимагають відсутній `.mvn/maven/...` runtime, а запускають checked-in Maven Wrapper JAR (`MavenWrapperMain`).

## 2. Maven 3.9.16 / build entrypoint

### Проблема

V10 checkpoint містив:

- `.mvn/wrapper/maven-wrapper.jar`;
- `.mvn/wrapper/maven-wrapper.properties`;
- `mvnw` / `mvnw.cmd`;

але не містив `.mvn/maven/apache-maven-3.9.6/...`. При цьому обидва launcher-и жорстко вимагали саме цей відсутній bundled runtime. Тобто repository/checkpoint wrapper entrypoint був нефункціональним без ручного додавання Maven payload.

### Red regression

У `tools/supply-chain-policy-check.py` додано machine-checkable contract:

- distribution pinned to Maven 3.9.16;
- Unix і Windows launchers використовують `org.apache.maven.wrapper.MavenWrapperMain`;
- launchers не залежать від `.mvn/maven/apache-maven-*`.

До виправлення: `Supply-chain policy check: FAIL: Unix mvnw must bootstrap through the checked-in Maven Wrapper JAR`.

### Fix

- `.mvn/wrapper/maven-wrapper.properties`: `3.9.6` → `3.9.16`;
- `mvnw` і `mvnw.cmd`: запуск через checked-in wrapper JAR;
- project-local `.mvn/repository` contract збережено;
- external/formal-source minimum Maven **3.9.6+** не підвищувався без compatibility-причини;
- актуальна developer-source документація виправлена: Maven distribution не заявляється як вбудований, якщо його фактично немає.

### Green evidence

- `tools/supply-chain-policy-check.py`: PASS;
- `bash -n mvnw`: PASS;
- wrapper доходить до `MavenWrapperMain` і намагається отримати pinned 3.9.16 distribution.

У цьому execution environment реальне завантаження Maven 3.9.16 неможливе: `UnknownHostException: repo.maven.apache.org`. Тому **`mvn clean verify` на Maven 3.9.16 тут не виконувався і не заявляється як PASS**.

## 3. HTTP resume integrity

### Дефект

Після truncation першої HTTP-відповіді без resume-validator partial залишався на диску. Наступна retry-ітерація могла сформувати `Range: bytes=N-` без `If-Range`, хоча partial не був прив'язаний до стабільної версії remote object.

### Red regression

Нові тести на V10 production code:

- `HttpOnlineBookDownloadAdapterTest.retriesFullDownloadWhenTruncatedResponseHasNoResumeValidator`;
- `HttpRemoteCatalogDownloadAdapterTest.retriesFullCatalogDownloadWhenTruncatedResponseHasNoResumeValidator`.

Результат: **39 found, 37 successful, 2 failed** — падають саме нові два regressions.

### Minimal fix

Перед retry, якщо `partial.size > 0` і resume metadata/validator відсутні:

- owned partial видаляється;
- owned resume metadata видаляється;
- snapshot/reset повертається до offset `0`;
- наступний запит є full request без `Range`/`If-Range`.

Змінено тільки:

- `HttpOnlineBookDownloadAdapter`;
- `HttpRemoteCatalogDownloadAdapter`.

### Green/downstream

- два цільові класи: **39/39 PASS**;
- весь `infrastructure/download` test package: **99/99 PASS**;
- `online-download-behavior-check.py`: PASS;
- `online-concurrency-check.py`: PASS.

## 4. Unicode / offset correctness

### 4.1 Combining mark at truncation boundary

V10 `Utf16BoundarySupport.safeCut()` вже не розривав UTF-16 surrogate pair, але cut перед combining mark міг залишити base character без його діакритики (наприклад `e` + U+0301).

Red:

- `Utf16BoundarySupportTest.movesCutBeforeBaseCharacterWhenNextCodePointIsCombiningMark`;
- `SearchAnnotationPresentationTest.summaryTruncationDoesNotDetachCombiningMarkFromBaseCharacter`.

До fix: **8 found, 6 successful, 2 failed**.

Minimal fix: centralized helper відсуває cut назад через base character, якщо наступний code point — `NON_SPACING_MARK`, `COMBINING_SPACING_MARK` або `ENCLOSING_MARK`.

Green: **8/8 PASS**. Існуючі callers з V10 автоматично отримують той самий boundary contract без масової заміни `substring()`.

### 4.2 Reader keyboard selection

`ReaderSelectionController.extendByCharacters()` рухав source offset через `focusOffset + delta`, тобто міг завершити selection усередині surrogate pair або між base character і combining mark.

Red на V10 production code: **5 found, 4 successful, 1 failed** — новий `keyboardSelectionDoesNotSplitSurrogatePairsOrCombiningSequences`.

Minimal fix: keyboard step переходить по Unicode code-point/combining boundaries без зміни renderer/pagination contract.

Green/downstream: повний `myhomelib-reader` manual classpath suite під Xvfb — **134 found, 1 skipped, 133 successful, 0 failed**.

Це не заявляє повну UAX #29 grapheme-cluster реалізацію; закрито саме підтверджений surrogate/combining offset contract.

## 5. Binary overflow / bounds

### Дефект

`BinaryMetadataInspector.inspectMobi()` читав unsigned 32-bit MOBI header length і одразу cast-ив у `int`. Значення близьке до `Integer.MAX_VALUE` могло переповнити подальший вираз `mobiStart + headerLength + 12`, пропустити bounds guard і завершитися `ArrayIndexOutOfBoundsException`.

Red: malformed MOBI regression — **5 found, 4 successful, 1 failed**; user-facing warning містив low-level `Index ... out of bounds`.

Minimal fix:

- header length зберігається як `long`;
- до cast перевіряється `>= 84` і `(long)mobiStart + declaredHeaderLength <= record0.length`;
- malformed input отримує контрольоване `IOException("Некоректний MOBI header length")`.

Green: **5/5 PASS**. Основний `MobiParser` уже мав аналогічний long-bound contract; generic parser framework не вводився.

## 6. Resource lifecycle / concurrency

### Дефект

`ReaderAnnotationCoordinator.complete()` перевіряв `stillCurrent` у першому `Platform.runLater`, але `focus(context)` ставив другий callback без ownership-check. Workspace міг змінитися між двома callbacks, після чого stale reader отримував focus.

Red: `ReaderAnnotationCoordinatorLifecycleTest.queuedFocusDoesNotRunAfterCompletionLosesWorkspaceOwnership` — **1/1 FAIL**, фактичний stale focus count = 1 замість 0.

Minimal fix: другий queued focus повторно перевіряє `current(context)` перед `focusReader().run()`.

Green: targeted **1/1 PASS**; повний UI suite під Xvfb — **220/220 PASS**.

Generic cancellable-task abstraction не додано.

## 7. Verification

### Повна ручна Java compile verification

Через недоступний Maven 3.9.16 distribution виконано clean manual compile у нові output-каталоги:

- main Java sources: **1387** — compile PASS;
- test Java sources: **547** — compile PASS;
- Java 21 (`--release 21`), із фактичними project dependencies та MapStruct/Lombok processors з підготовленого offline repository.

Це cross-module compile evidence, але **не заміна Maven reactor lifecycle**.

### Downstream suites

- `myhomelib-reader` під Xvfb: **134 found / 1 skipped / 133 PASS / 0 failed**;
- `myhomelib-ui` під Xvfb: **220/220 PASS**;
- `myhomelib-infrastructure` download package: **99/99 PASS**;
- HTTP adapter targeted pair: **39/39 PASS**.

Повний infrastructure classpath-run не використовується як release evidence: standalone JUnit harness зависає під час Spring/SQLite `DatabaseTest`. Це не відтворює Maven Surefire contract, тому статус full infrastructure module не оголошується green на підставі цього harness.

### Static/release gates

PASS:

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

`static_release_check.py` бачить **1934 Java sources / 547 test sources**, 49 XML files, 29 FXML workspaces, 261 handler references, 63 SQLite migrations; static issues = 0.

### Pre-existing architecture-check debt

`tools/architecture-check.py` повертає 6 violations. Та сама команда на untouched V10 baseline повертає **ті самі 6 violations**, тому V11 їх не створив. Це окремий existing-debt item, не regression цього checkpoint.

## 8. Verification, яку не можна вважати виконаною

Через обмеження середовища не підтверджено:

- `./mvnw clean verify` з фактичним Maven 3.9.16;
- `-Pproduction` package;
- новий Linux `jpackage`/stage23/packaged `--release-smoke` для цього V11 source state;
- Windows x64 packaging/runtime acceptance.

Причина Maven gate: wrapper коректно запускається, але `repo.maven.apache.org` не резолвиться в execution environment (`UnknownHostException`).

## 9. Scope / non-changes

Не змінювались:

- heap/GC defaults (залишається V9 decision G1 + `-Xmx1024m`);
- DB schema/migrations;
- public persistence schema;
- generic async architecture;
- organizer/converter naming policy;
- великий structural refactor контролерів.

Декомпозицію великих класів у цьому checkpoint навмисно не роблено разом із correctness fixes: наступний refactor має бути окремим, independently-testable responsibility boundary.

## 10. Checkpoint decision

V11 source state придатний як **аудитний clean-source checkpoint**, але не як остаточно підтверджений release candidate: source-level correctness regressions закриті та downstream/static gates зелені, однак consolidated Maven 3.9.16 verify/package/runtime gates треба повторити в середовищі з доступним Maven distribution.
