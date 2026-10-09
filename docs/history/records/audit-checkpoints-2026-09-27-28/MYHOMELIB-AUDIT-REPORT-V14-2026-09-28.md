# MyHomeLib — deep technical audit V14 checkpoint

Дата: 2026-09-28  
Єдина кодова baseline цієї сесії: `myhomelib-enterprise-optimized-v13-checkpoint-2026-09-28.zip`  
Вхідний task file: `MYHOMELIB-NEXT-TASKS-V12-2026-09-28.md`

## 1. Підсумок

Аудит виконувався regression-first. Стабільний код не переписувався без відтвореного дефекту.

Підтверджено й мінімально виправлено **2 продуктні дефекти**:

1. HTTP resume міг опублікувати усічений `206 Partial Content`, якщо `Content-Range` мав unknown total (`bytes X-Y/*`) і chunked body фактично містив менше байтів, ніж `Y-X+1`.
2. `LocalMetadataExtractionService` міг розрізати UTF-16 surrogate pair на межі 16 384 code units і передати в user-visible Integration Center невалідний UTF-16.

Окремо виправлено **1 tooling defect**: `architecture-check.py` перевіряв Stage-6 UPSERT SQL у застарілому owner (`JdbcBatchWriter`), хоча canonical SQL уже знаходиться в `BookUpsertSql`. П'ять false-positive violations прибрано regression-тестом checker-а; реальний UI architecture debt лишився видимим.

## 2. Defect 1 — HTTP resume short body з `Content-Range: */unknown`

### Відтворення

Regression: `HttpOnlineBookDownloadAdapterTest.rejectsTruncatedChunkedResumeBodyWhenTotalSizeIsUnknown`.

Fixture:

- перша відповідь: `200`, `Content-Length: 11`, strong ETag, фактично 6 байтів → залишається `.part`;
- друга відповідь: `206`, `Content-Range: bytes 6-10/*`, без `Content-Length`, фактично лише 3 байти замість обіцяних 5.

До fix baseline опублікував 9-байтовий target, хоча range contract вимагав 11 байтів загалом. Regression був RED.

### Мінімальний fix

- `HttpResumeSupport.expectedPartialBodyLength(...)` обчислює `end - start + 1` для валідного `206 Content-Range` незалежно від total `*`;
- `HttpOnlineBookDownloadAdapter` після завершення body session звіряє фактично дописані байти з очікуваною довжиною range до publication.

Не змінювались retry framework, validator policy або publication framework.

### Targeted/downstream evidence

- новий regression: PASS;
- сусідні resume/validator/race тести: PASS;
- весь `com.myhomelibcorp.infrastructure.download`: **100/100 PASS**.

## 3. Defect 2 — user-visible UTF-16 truncation

### Відтворення

Regression: `LocalMetadataExtractionServiceTest.truncationDoesNotSplitSurrogatePairInUserVisiblePluginMetadata`.

Plugin metadata value: `"a".repeat(16_383) + "😀tail"`. Стара логіка `substring(0, 16_384)` залишала lone high surrogate. Значення далі показується в `IntegrationCenterDialog` у `TextArea`, тому шлях user-visible і reachable. Regression був RED (`hasUnpairedSurrogate == true`).

### Мінімальний fix

Перед truncation межа зміщується на один UTF-16 code unit назад тільки тоді, коли вона потрапляє між high/low surrogate. Ліміт, ellipsis і решта sanitization policy не змінювалися.

### Targeted/downstream evidence

- новий regression: PASS;
- `com.myhomelibcorp.application.extension`: **4/4 PASS**;
- весь application package у UTF-8 locale: **459 succeeded, 1 skipped, 0 failed**.

Примітка: перший ручний package-run у POSIX locale дав 2 `InvalidPathException` на наявних Unicode-path tests. Повтор у `C.UTF-8` — повністю green; це environment/locale artifact, не зміна production behavior.

## 4. Tooling defect — stale Stage-6 architecture checker

`JdbcBatchWriter` використовує `BookUpsertSql.UPSERT`; persistence behavior tests підтверджують збереження локальних/user fields під час remote UPSERT. Checker продовжував шукати SQL fragments у старому класі.

Додано `tools/architecture-check-regression-test.py`:

- до fix: RED — Stage-6 false positives присутні;
- після fix: PASS — checker читає canonical SQL owner;
- незалежний UI debt навмисно лишається видимим.

Downstream persistence contracts:

- `JdbcBatchWriterStage6Test.remoteUpsertPreservesLocalStorageAndUserDataIncludingBookmarks`: PASS;
- `JdbcCatalogImportAdapterTest.fullSnapshotMarksMissingSourceBookDeletedAndPreservesUserFields`: PASS.

Після fix `architecture-check.py` має **1** violation замість 6:

`DeferredMainFeatureActions`, `ReaderBookMapCoordinator`, `ReaderBookmarkCoordinator` — UI → non-value domain model debt.

## 5. Аудит без змін

### Async ownership

`SearchWorkspaceController` уже має `searchGeneration`, snapshot/current token checks, cancellation попереднього content-search `Future`, collection identity guard та invalidation у `dispose()`. Детермінованого stale-completion defect не відтворено — код не змінювався.

`ReaderTextProviderCoordinator` перевіряє workspace/book context і cancellation token перед queued JavaFX callback. Дефект не відтворено — код не змінювався.

### Binary/archive bounds

Перевірені `ArchiveSafetyLimits`, `ArchiveEntrySafety`, `LimitedInputStream`, EPUB content extraction, ZIP importer, INPX reader та EPUB cover path. Для reachable paths наявні declared/actual bounds, cumulative limits або overflow-safe checks. Нового RED case не отримано — production code не змінювався.

### Converter filename

V13 baseline вже містить `{safeTitle}.{ext}`, deterministic `(2)/(3)` collision naming, blank-title fallback та відсутність technical `artifactId` suffix. P3.1 не переписувався.

### Persistence/concurrency

Існуючі busy timeout/retry та Stage-6 preservation contracts перевірені точково. Нового deterministic `SQLITE_BUSY` partial-state regression у цій сесії не відтворено, тому transaction/retry code не змінювався.

## 6. Verification evidence

- Java: OpenJDK 21.0.11.
- Clean manual production compile: **1 387 sources → 2 095 classes**.
- Clean manual test compile: **548 sources → 823 test classes**.
- UI under Xvfb: **223/223 PASS**.
- Reader under Xvfb: **136 succeeded, 1 skipped, 0 failed**.
- Application package (`C.UTF-8`): **459 succeeded, 1 skipped, 0 failed**.
- Download package: **100/100 PASS**.
- Application extension package: **4/4 PASS**.
- Stage-6 targeted persistence contracts: **2/2 PASS**.
- `implementation-completeness-check.py`: PASS.
- `xml-archive-security-check.py`: PASS.
- `supply-chain-policy-check.py`: PASS.
- `async-generation-audit-check.py`: PASS.
- `managed-executor-check.py`: PASS.
- `thread-sleep-audit-check.py`: PASS.
- `online-download-behavior-check.py`: PASS.
- `static_release_check.py`: PASS (49 XML/POM/FXML, 63 SQLite migrations, shell/release packaging/CI static contracts).
- `architecture-check.py`: **1 known debt violation**; five stale Stage-6 false positives removed.

Full ad-hoc infrastructure package selection was not used as a pass/fail release gate: it enters long performance/migration probes and exceeded the execution window. Modified infrastructure behavior is instead covered by the complete 100-test download package plus targeted Stage-6 persistence tests. The required authoritative module gate remains Maven lifecycle.

## 7. P0 Maven/release gate — NOT PASSED

`./mvnw -v` still fails before Maven starts:

`java.net.UnknownHostException: repo.maven.apache.org`

System Maven is absent, and the uploaded local dependency shards do not include the Maven 3.9.16 binary distribution. An external binary download was also unavailable in this execution environment.

Therefore the following are **not claimed PASS**:

- real Maven 3.9.16 `clean verify`;
- aggregate Surefire totals from Maven;
- production package;
- Linux `jpackage` app-image;
- stage23 check on real `dist/`;
- packaged launcher `--release-smoke`;
- ASCII/non-ASCII checkout Maven build;
- Windows x64 release acceptance.

## 8. Structural decomposition status

Code decomposition was **not started**. This is intentional: section P4 of the task file says to begin only after real Maven/release gates. Performing large-class refactoring now would violate the audit invariant and remove the required safety net.

Current sizes for the planned sequence:

1. `NewReaderWorkspaceController` — 1 932 lines;
2. `SearchWorkspaceController` — 1 254 lines;
3. `DesktopIntegrationBackend` — 1 367 lines;
4. `VersionedUserDataTransferAdapter` — 1 225 lines;
5. `InpxImportPipeline` — 1 124 lines.

The next session should first close P0, then extract one independently-testable responsibility boundary at a time with unchanged behavior and full module Maven tests after each step.

## 9. Exact code/tool delta vs V13 baseline

Modified:

- `myhomelib-application/.../LocalMetadataExtractionService.java`
- `myhomelib-application/.../LocalMetadataExtractionServiceTest.java`
- `myhomelib-infrastructure/.../download/HttpResumeSupport.java`
- `myhomelib-infrastructure/.../download/HttpOnlineBookDownloadAdapter.java`
- `myhomelib-infrastructure/.../download/HttpOnlineBookDownloadAdapterTest.java`
- `tools/architecture-check.py`

Added:

- `tools/architecture-check-regression-test.py`

No baseline source files were deleted.

Packaging note: Maven wrapper files (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/*`) are retained from the pristine V13 baseline; only local Maven repository payload (`.mvn/repository/`) is excluded from the clean source ZIP.
