# MyHomeLib — deep technical audit V15 checkpoint

Дата: 2026-09-28  
Єдина кодова baseline цієї сесії: `myhomelib-enterprise-optimized-v14-checkpoint-2026-09-28.zip`  
Runtime evidence: `myhomelib.log`, `log idea.txt` від V13 user run.

## 1. Підсумок

Аудит продовжено regression-first. Стабільний код не переписувався без підтвердженої причини.

Підтверджено й мінімально виправлено **2 пов'язані concurrency/lifecycle дефекти**:

1. Довга/зависла `CONVERSION` тримала global collection operation barrier так широко, що блокувала `READER_STATE` і `DOWNLOAD`. Через це навігація, збереження workspace state та відкриття/завантаження книг ставали недоступними, хоча сама конвертація була file-local операцією.
2. Фоновий `INDEX` rebuild відхиляв `DOWNLOAD` уже на UI submission boundary. Після дозволу coexistence залишався downstream-ризик: synchronous selective Lucene sync після фізичного download міг чекати завершення full rebuild і знову затримувати користувача.

Fix зберігає collection isolation: `SWITCH` не дозволяється, доки `INDEX`, `CONVERSION` або deferred Lucene repair тримають shared barrier.

## 2. Defect 1 — conversion робила застосунок фактично недоступним

### Runtime evidence

У V13 user log під час активної `CONVERSION`:

- `SessionService.saveWorkspaceState()` через `READER_STATE` отримував `LibraryOperationConflictException`;
- відкриття/отримання книги багаторазово отримувало `Операція DOWNLOAD недоступна, поки виконується CONVERSION`;
- конвертація завершилась лише при shutdown через `InterruptedException` у `Process.waitFor(...)` → `ProcessExecutionSupport.run(...)` → `CalibreCliBookConverter`.

Це доводить підтверджений UI/lifecycle defect: незалежні стабільні collection operations були помилково заблоковані конвертацією.

### Regression

`LibraryOperationCoordinatorTest.runningConversionDoesNotBlockReaderStateOrBookDownload`

До fix: RED — `READER_STATE` не міг приєднатися до `CONVERSION` root (і `DOWNLOAD` також був несумісний).

### Мінімальний fix

У `LibraryOperationCoordinator.isSharedCompatible(...)` додано тільки асиметричну сумісність:

- `CONVERSION -> READER_STATE`;
- `CONVERSION -> DOWNLOAD`.

`SWITCH` залишається несумісним. Reverse-acquisition policy не розширювалась без потреби.

### Calibre process behavior

`CalibreCliBookConverter` вже має hard timeout: default `300 s`, clamp `10..3600 s`, через `ProcessExecutionSupport`. У наданому runtime trace shutdown стався раніше default timeout, тому нескінченний `waitFor()` не підтверджено.

`ProcessExecutionSupportTest`: **5/5 PASS** у мінімальному коректному classpath. Windows-specific warning про тимчасовий workspace/file handle після interrupted shutdown у цьому Linux environment детерміновано не відтворено; production process-tree code не переписувався.

## 3. Defect 2 — download блокувався background index rebuild

### Відтворення

У V14 baseline `UiBackgroundExecutor.submitCollectionScoped(DOWNLOAD, ...)` pre-acquire-ив `DOWNLOAD` lease. `LibraryOperationCoordinator` не дозволяв `INDEX -> DOWNLOAD`, тому користувацьке завантаження відхилялося **до запуску download task**.

Regression-и:

- `LibraryOperationCoordinatorTest.backgroundIndexAllowsUserDownloadWithoutOpeningSwitchRace` — до fix RED;
- `UiBackgroundExecutorCollectionScopeTest.backgroundIndexDoesNotRejectUserDownloadAtUiSubmissionBoundary` — перевіряє реальний UI submission boundary.

### Мінімальний coordinator fix

Дозволено **лише** `INDEX -> DOWNLOAD`.

Reverse `DOWNLOAD -> INDEX` навмисно лишено blocked. Це не дає full rebuild стартувати всередині довільного download root і спрощує коректний post-download synchronization contract.

### Downstream Lucene contract

Просто дозволити `INDEX + DOWNLOAD` було недостатньо: `DownloadBookUseCase` після physical/storage commit синхронно викликав `SearchIndexSynchronizer.synchronizeSafelyNow(...)`. Full rebuild у `LuceneSearchService` тримає synchronized writer lifecycle, тому selective sync міг чекати rebuild на caller thread.

Додано regression:

`DownloadBookUseCaseIndexConcurrencyTest.downloadDuringBackgroundRebuildReturnsWithoutWaitingAndDefersSelectiveLuceneSync`

До downstream fix: RED — selective Lucene sync викликався синхронно.

### Мінімальний downstream fix

`DownloadBookUseCase` отримав існуючий `ExecutorPort` і тепер:

1. якщо active root — не `INDEX`, поведінка не змінена: selective Lucene sync виконується відразу;
2. якщо download приєднався до active `INDEX`, selective Lucene sync переноситься у managed background executor;
3. перед scheduling береться додатковий shared `DOWNLOAD` lease під тим самим `INDEX` root;
4. цей lease утримує collection barrier після завершення full rebuild worker, доки selective repair не завершиться;
5. `SWITCH` тому не може перевести dynamic repositories/index на іншу collection у проміжку;
6. якщо executor відхилив scheduling, lease закривається і виконується synchronous fallback, щоб correctness не залежав від availability background queue.

Physical download/storage commit, content-index scheduling, download queue semantics та statistics invalidation не змінювались.

## 4. Verification evidence

Clean source compile на V15 state:

- Java: OpenJDK 21.0.11;
- production: **1 387 sources -> 2 095 classes**;
- tests: **549 sources -> 825 test classes**.

Targeted:

- `LibraryOperationCoordinatorTest`: **23/23 PASS**;
- `DownloadBookUseCaseIndexConcurrencyTest`: **1/1 PASS**;
- `UiBackgroundExecutorCollectionScopeTest`: **7/7 PASS**;
- `ProcessExecutionSupportTest`: **5/5 PASS** (minimal classpath).

Downstream:

- application package: **462 succeeded, 1 skipped, 0 failed**;
- infrastructure search: **28 succeeded, 2 skipped, 0 failed**;
- infrastructure download: **100/100 PASS**;
- infrastructure exporter: **15/15 PASS**;
- UI under Xvfb with module working directory/resources: **224/224 PASS**;
- Reader under Xvfb with test resources: **136 succeeded, 1 skipped, 0 failed**.

Static/security:

- `architecture-check-regression-test.py`: PASS;
- `static_release_check.py`: PASS — 49 XML/POM/FXML, 63 SQLite migrations, 1 936 Java sources, 549 test sources;
- `xml-archive-security-check.py`: PASS;
- `architecture-check.py`: **1 known debt group** (unchanged): `DeferredMainFeatureActions`, `ReaderBookMapCoordinator`, `ReaderBookmarkCoordinator`.

Примітка: перший UI package run був запущений не з `myhomelib-ui` working directory і закономірно дав relative-path/resource failures; повтор з правильним module cwd/resources — 224/224 PASS. Аналогічно Reader golden resources були додані до test classpath у повторі — 136/136 PASS + 1 skipped. Це harness configuration, не product failures.

## 5. Maven/release gate

Стан із V14 не змінився: authoritative Maven 3.9.16 lifecycle у цьому environment не доступний, бо Maven distribution відсутній, а wrapper потребує network access. Тому **не заявляється PASS** для:

- `./mvnw clean verify`;
- production Maven package/jpackage;
- packaged launcher smoke;
- Windows x64 acceptance.

Manual clean compile і package-level JUnit evidence вище не підміняють Maven release gate.

## 6. Windows conversion cleanup observation — без production change

V13 shutdown trace показував, що після interrupt Calibre workspace міг ще бути зайнятий Windows process/file handle. Existing process-tree timeout/cancellation tests проходять; у поточному Linux environment deterministic RED case для "run returns while descendant still owns file" не отримано.

Тому відповідно до правила "не переписувати стабільний код без підтвердженої причини" `ProcessExecutionSupport` не змінювався. Для Windows acceptance це лишається targeted observation: перевірити interrupt/timeout + immediate workspace cleanup на реальному Windows x64.

## 7. Explicit conversion UX residual

Operation Center зараз не дає користувачу окремого cancel action для book conversion; зовнішній Calibre має hard timeout, але user-initiated cancellation UX не реалізований. Це не було причиною глобального freeze, проте є окремою UX/resilience задачею. Додавати її в цей fix без generalized cancellation contract означало б розширити scope за межі підтвердженого дефекту.

## 8. Exact delta vs pristine V14 baseline

Modified:

- `myhomelib-application/src/main/java/com/myhomelibcorp/application/operation/LibraryOperationCoordinator.java`
- `myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/download/DownloadBookUseCase.java`
- `myhomelib-application/src/test/java/com/myhomelibcorp/application/operation/LibraryOperationCoordinatorTest.java`
- `myhomelib-ui/src/test/java/com/myhomelibcorp/ui/service/UiBackgroundExecutorCollectionScopeTest.java`

Added:

- `myhomelib-application/src/test/java/com/myhomelibcorp/application/usecase/download/DownloadBookUseCaseIndexConcurrencyTest.java`
- V15 audit/change/next-task documents.

No baseline production/source files were deleted.
