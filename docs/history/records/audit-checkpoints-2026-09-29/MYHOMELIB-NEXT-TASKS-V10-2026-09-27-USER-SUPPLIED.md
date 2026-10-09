# MyHomeLib — завдання для продовження після V10 checkpoint

Дата фіксації: 2026-09-27

Базовий архів для наступної сесії:
`myhomelib-enterprise-optimized-v10-checkpoint-2026-09-27.zip`

## 1. Незмінні правила

1. correctness і поведінкові контракти — перший пріоритет;
2. defect: red regression → мінімальний fix → targeted/downstream tests;
3. refactoring: один responsibility boundary за раз;
4. performance/memory: тільки контрольовані A/B або matrix-вимірювання;
5. не переписувати стабільний код без тестової або вимірюваної причини;
6. не створювати generic async/framework abstractions, поки щонайменше два consumers не мають однакового lifecycle contract;
7. не повертати вже прибрані eager/lifecycle/runtime дублювання.

## 2. Перевірений V10 state

- consolidated `clean verify`: **BUILD SUCCESS**, 20/20 modules, 02:55;
- aggregate Surefire: **1755 tests, 0 failures, 0 errors, 17 skipped**;
- `myhomelib-ui`: **217/217 PASS** під Xvfb;
- `myhomelib-infrastructure`: **634**, 0 failures/errors, 9 skipped;
- `myhomelib-application`: **453**, 0 failures/errors, 1 skipped;
- `myhomelib-reader`: **132**, 0 failures/errors, 1 skipped;
- `myhomelib-bootstrap`: **47**, 0 failures/errors, 2 skipped;
- implementation completeness / static release / XML-archive security / supply-chain: **PASS**;
- production bootstrap reactor package (`-Pproduction -DskipTests`): **BUILD SUCCESS**;
- Linux `jpackage` app-image: **PASS**;
- stage23 release checker: **PASS**;
- packaged launcher `--release-smoke`: **PASS**.

## 3. V10 changes, які не можна втрачати

- blank Reader/Search queries invalidate generation and cancel in-flight work, preventing stale-result repopulation;
- Reader sidebar search cancellation is generation-safe;
- `BookWorkspaceController` download completion can no longer reload a stale book after workspace navigation;
- Reader open progress is guarded both against dispose and against stale open generations;
- surrogate-safe truncation is centralized through `Utf16BoundarySupport` for relevant UI boundaries;
- saved-search default names, annotation issue previews, annotation sidebar previews and bookmark titles no longer split surrogate pairs;
- `SearchBookPagingState` owns search-result paging state;
- `ReaderAnnotationSidebarFilter` owns pure annotation sidebar tag/filter/ordering selection.

## 4. P0 — Windows release coverage

Still requires a real Windows x64 environment:

- production build/package;
- launcher from ASCII and non-ASCII paths;
- packaged `--release-smoke`;
- runtime tools availability;
- plugin install/enable/disable/restart lifecycle;
- EPUB/FB2/PDF reader smoke;
- strict/full release fail-closed behavior for incomplete licensed/bundled libmobi runtime/source bundle.

Linux evidence is not a substitute for Windows runtime validation.

## 5. P1 — Organizer: рекурсивне впорядкування змішаної бібліотеки

Закрити повний користувацький сценарій: користувач вказує **одну кореневу папку**, у якій може бути довільна кількість вкладених папок і змішані формати книг; MyHomeLib має рекурсивно знайти підтримувані книги, отримати доступні метадані та сформувати єдину впорядковану структуру за автором і серією.

Цільовий pipeline:

1. рекурсивний scan усієї source tree незалежно від поточної структури папок;
2. визначення підтримуваного формату книги;
3. metadata extraction для кожного формату: щонайменше `Author`, `Title`, `Series`, `SeriesNo`, де формат це підтримує;
4. нормалізація автора/серії/назви й filesystem-safe path components;
5. preview/dry-run усіх запланованих move/rename операцій;
6. застосування структури за замовчуванням:
   `{Author}/{Series}/{SeriesNo:00} - {Title}.{ext}`;
7. книги без серії мають потрапляти безпосередньо в каталог автора;
8. книги без надійно визначеного автора — у явний fallback-каталог, без вигадування metadata;
9. колізії, дублікати, rollback і повторний запуск мають бути deterministic та non-destructive;
10. source і destination можуть бути однією library root лише за умови безпечного plan/apply/rollback contract.

Обов'язково перевірити фактичне metadata coverage **для всіх форматів, які Organizer уже визнає підтримуваними**. Не вважати формат повністю підтриманим для цього сценарію лише тому, що файл знаходиться scanner-ом. Для форматів без повного extraction додати format-specific extraction тільки після fixture/contract tests. Пріоритет перевірки/доробки: `MOBI/AZW/AZW3`, `PDF`, `ODT`, потім інші підтримувані формати (`DOCX`, `RTF`, `TXT/HTML`, `DJVU`, `CHM`, `CBZ/CBR/CB7` тощо) відповідно до реальних можливостей їхніх metadata containers.

Для archive/container cases окремо перевірити:

- `FB2.ZIP` та інші вже підтримувані single-book archive cases;
- multi-book ZIP;
- `7z`/RAR — тільки якщо вони реально заявлені/підтримуються поточним продуктом; не додавати новий archive stack без продуктового обґрунтування.

Acceptance tests для цього P1 повинні включати fixture-tree з кількома рівнями підпапок і сумішшю форматів, а також перевіряти кінцевий filesystem plan/result, а не лише окремий parser. Мінімальні сценарії: різні автори, кілька серій, книги без серії, невідомий автор, однакові назви, duplicate/collision, повторний запуск після частково вже впорядкованої бібліотеки та rollback після штучної помилки publication.

Не будувати universal metadata framework наперед. Спільний abstraction вводити лише тоді, коли щонайменше два format adapters мають однаковий стабільний контракт.


### P1.1 — Конвертація: прибрати технічний UUID/hex-суфікс із видимого імені файла

Виправити naming policy у `ConvertBookUseCase`: внутрішній `artifactId`/UUID не повинен автоматично потрапляти у користувацьке ім’я кожного конвертованого файла. Зараз ім’я формується з технічним суфіксом на кштал `Назва-a83f21c7.epub`; цільова поведінка для звичайного випадку — `Назва.epub`.

Вимоги до виправлення:

1. зберегти `artifactId` як внутрішній стабільний ідентифікатор артефакту; не змішувати його з public filename;
2. якщо цільового файла немає — публікувати `{safeTitle}.{ext}` без UUID/hex-суфікса;
3. якщо файл з таким ім’ям уже існує — застосовувати deterministic non-destructive collision policy (`Назва (2).epub`, інший уже наявний продуктово прийнятний варіант або existing shared collision resolver);
4. не перезаписувати чужий/попередній результат мовчки;
5. не додавати hash/UUID suffix заздалегідь «про всяк випадок»; технічний disambiguator допускається лише якщо це справді потрібно для розв’язання реальної колізії й зафіксовано контрактом;
6. не ламати safe filename normalization, atomic publication/rollback, conversion history та посилання на artifact metadata;
7. якщо в Organizer уже є придатний collision resolver, reuse робити лише за умови однакового контракту; не створювати generic naming framework заради одного use case.

Обов’язковий regression-first набір:

- звичайна конвертація: `Назва книги.epub`, без випадкового суфікса;
- повторна конвертація при вже існуючому target: deterministic collision name і відсутність overwrite;
- технічний `artifactId` не витікає у filename;
- unsafe/порожній title зберігає чинний safe fallback contract;
- різні target extensions формуються коректно;
- targeted `ConvertBookUseCase` tests → converter/application downstream tests → completeness-check.

Не змінювати naming інших підсистем без окремого regression або спільного підтвердженого контракту.

### P1.2 — Повний UI interaction/coverage suite: усі меню, кнопки та користувацькі дії

Побудувати системний автоматизований UI-suite, який перевіряє **всі доступні користувачу інтерактивні елементи MyHomeLib**, а не лише завантаження FXML або наявність handler-ів.

Мета: мати відтворюваний доказ, що кожен пункт меню, toolbar/action button, context-menu item, dialog action і основна workspace-дія або реально виконує свій контракт, або коректно переходить у передбачений disabled/unavailable state за відсутності preconditions.

Не робити один монолітний «натиснути все підряд» тест. Побудувати coverage registry / matrix з явним mapping:

`UI surface → control/action → preconditions → trigger → expected observable effect → test class`.

Обов’язкове покриття:

1. головне меню програми — кожний видимий `MenuItem`, включно з dynamic/submenu entries;
2. toolbar / header / footer buttons усіх основних workspace;
3. context menus для бібліотеки, результатів пошуку, книг, колекцій, reader/annotations та інших user-visible списків/таблиць;
4. dialogs і wizard-like flows: `OK/Apply/Save/Cancel/Close/Retry/Browse/...` та validation/error states;
5. основні кнопки й дії `Library`, `Search`, `Reader`, `Organizer`, `Converter`, import/export/backup/sync/plugin/settings та інших доступних модулів;
6. дії, що з’являються лише після вибору книги/колекції/файла або іншого context state;
7. keyboard accelerators/shortcuts, якщо вони заявлені в UI та мають окремий user contract;
8. disabled-state policy: дія не повинна бути enabled, якщо необхідний context/precondition відсутній;
9. navigation/open/close semantics: відкривається правильний workspace/dialog, повторне відкриття не створює неконтрольованих duplicate windows/tasks;
10. destructive actions: delete/remove/overwrite/reset мають тестуватися лише на isolated fixtures/temp storage з явним confirmation contract;
11. external/network/system actions тестувати через fake/test-double boundary або deterministic local fixture; автоматичний UI-suite не повинен надсилати реальні дані, відкривати реальні зовнішні сервіси чи змінювати користувацьке оточення;
12. asynchronous actions повинні чекати саме observable completion signal, а не використовувати flaky `sleep` як основний synchronization mechanism;
13. після кожного сценарію перевіряти uncaught FX/background exceptions і відсутність завислих tasks/dialogs, де це технічно доступно;
14. suite має стабільно проходити під `Xvfb`/virtual display у CI-like Linux environment.

### Coverage completeness contract

Додати machine-checkable інвентаризацію user actions, щоб нова кнопка/пункт меню не могла непомітно з’явитися без тесту.

Бажаний підхід без overengineering:

- статично/рефлексивно зібрати відомі FXML controls, menu actions та command/action registrations;
- підтримувати невеликий explicit allowlist тільки для декоративних/неінтерактивних controls або дій, що фізично неможливо виконати в automated environment;
- fail test, якщо user-action існує, але не має coverage entry;
- fail test, якщо coverage entry посилається на control/action, якого більше немає;
- для dynamic menus мати окремий provider contract test, а не hard-coded список випадкових runtime labels.

Не прив’язувати тест до координат екрану. Використовувати `fx:id`, semantic lookup, action/command identifiers або інший стабільний селектор. Не створювати brittle screenshot-only automation як основний доказ функціональності.

### Мінімальні acceptance criteria для P1.2

- inventory усіх user-triggerable actions сформований і зафіксований;
- **100% inventory entries** мають test mapping або документований justified exclusion;
- жодного missing FXML handler/action binding;
- кожний enabled action має observable behavior assertion, а не лише `click()` без перевірки результату;
- негативні/validation paths перевірені для дій із обов’язковими preconditions;
- повний UI interaction suite — 0 failures/errors під Xvfb;
- існуючий `myhomelib-ui` full suite залишається green;
- після додавання coverage suite `implementation-completeness-check.py` і `static_release_check.py` залишаються PASS.

Результатом має бути не тільки набір тестів, а й короткий generated/maintained coverage report, наприклад:

`docs/MYHOMELIB-UI-ACTION-COVERAGE.md`

з колонками: `Surface | Action | Control/Menu | Preconditions | Expected result | Automated test | Status/Exclusion reason`.

### P1.3 — Повний user-facing опис функцій MyHomeLib

Створити й підтримувати окремий документ:

`MYHOMELIB-USER-FUNCTIONS.md`

Документ має описувати **всі функції, реально доступні користувачу у поточній збірці**, на основі фактичного UI/FXML/controllers/use-cases та перевіреного runtime behavior. Не доповнювати список функцій припущеннями або roadmap-можливостями, яких ще немає у продукті.

Для кожної функції описати щонайменше:

1. **Назва функції**;
2. **Де вона знаходиться в UI**: меню / workspace / toolbar / context menu / dialog;
3. **Призначення** — що отримує користувач;
4. **Передумови** — що має бути вибрано/налаштовано;
5. **Основний сценарій використання** покроково;
6. **Вхідні дані / підтримувані формати**, якщо застосовно;
7. **Результат / side effects**: файл, зміна БД, відкриття Reader, export, sync тощо;
8. **Обмеження / fallback behavior / помилки**, які користувач реально може побачити;
9. **Пов’язані налаштування**;
10. **Підтвердження тестом** — посилання/назва automated test або coverage entry, якщо функція покрита P1.2.

Обов’язкові розділи документа повинні відповідати реальній функціональності продукту та щонайменше перевірити наявність/відсутність таких областей:

- створення/відкриття/керування бібліотеками й колекціями;
- імпорт локальних книг та INPX/каталогів;
- пошук, фільтри, сортування та saved searches;
- картка/метадані книги та редагування;
- Reader для підтримуваних форматів;
- bookmarks, annotations/highlights, search in book, text-provider/dictionary/translation/TTS, якщо доступні користувачу;
- конвертація книг;
- Organizer і фізичне впорядкування файлів;
- export/backup/restore/user-data transfer;
- online/OPDS/catalog/download flows;
- plugins/extensions;
- synchronization/WebDAV або інші sync-функції, якщо вони реально expose-яться користувачу;
- налаштування, appearance/language/hotkeys та інші preferences;
- diagnostics/about/update/release-related user actions, якщо вони доступні;
- усі context-menu та secondary actions, які не очевидні з головного меню.

### Вимоги до повноти документації

- зіставити документ з P1.2 UI action inventory: user-visible action не повинна існувати без відповідного опису або явного короткого посилання на більший flow;
- окремо позначити **опційні функції**, що залежать від external tools/runtime (`EPUBCheck`, ffmpeg, converter engines, libmobi тощо);
- чітко відрізняти `available`, `available only when configured`, `optional/external dependency`, `not implemented/hidden`;
- не описувати internal/admin/developer-only endpoints як звичайні user features;
- приклади форматів/обмежень брати з actual product contracts/tests, а не з загальних можливостей сторонніх бібліотек;
- після кожної зміни UI/action inventory document completeness має перевірятися автоматично або простим deterministic consistency-check.

### Acceptance criteria для P1.3

Перед V11/V12 release-candidate має існувати актуальний `MYHOMELIB-USER-FUNCTIONS.md`, який:

- охоплює 100% user-action inventory з P1.2 на рівні функцій/flows;
- не містить заявлених можливостей, яких немає в коді;
- містить окрему таблицю підтримуваних user-visible форматів і операцій над ними (`import/read/metadata/convert/organize/export/validate`, де застосовно);
- містить matrix optional/external dependencies;
- проходить manual spot-check проти packaged app і automated coverage report;
- включається до clean source ZIP наступного checkpoint.

## 6. P2 — async ownership / cancellation continuation

Continue only where a stale completion can be demonstrated by a red test.

Priority review:

1. remaining `SearchWorkspaceController` content-search cancellation and workspace replacement semantics;
2. `ReaderTextProviderCoordinator` callbacks when active reader/book changes or workspace is disposed;
3. annotation async refresh/callback paths in `NewReaderWorkspaceController` / `ReaderAnnotationCoordinator`;
4. shutdown paths that cancel `Future`/executor work but may still allow queued JavaFX callbacks;
5. plugin restart/upgrade/uninstall lifecycle callbacks after package replacement.

Do not introduce a generic cancellable-task framework unless tests prove the same lifecycle contract exists in multiple consumers.

## 7. P3 — structural decomposition

One boundary at a time:

1. `NewReaderWorkspaceController`: remaining annotation/sidebar/search presentation responsibilities after `ReaderAnnotationSidebarFilter` extraction;
2. `SearchWorkspaceController`: remaining result-presentation/query orchestration after `SearchBookPagingState` extraction;
3. `DesktopIntegrationBackend`: remaining sync/export/plugin/settings boundaries only when independently testable;
4. `VersionedUserDataTransferAdapter`: traversal/restore/compensation only with a concrete failing or independently testable contract;
5. `InpxImportPipeline`: parsing/batching/progress/index-sync only where responsibility ownership is clear.

Literal clone checker is already 0 exact cross-file method clones >=180 chars; continue semantic/AST-style review only.

## 8. P4 — correctness / hidden defects

Targeted audit only:

- remaining reachable raw UTF-16 truncations; do not mass-replace `substring()`;
- canonical equivalence / combining-character offset boundaries in Reader/Search annotations;
- malformed/truncated ZIP/EPUB/FB2/MOBI/INPX with unknown sizes and cumulative limits;
- overflow in parser slice arithmetic (`offset + length`, `position + size`);
- HTTP resume after redirect/validator change/truncated 206 body;
- temp-file publication/rollback when target changes concurrently;
- SQLite busy/retry transaction boundaries under concurrent import/edit/index update;
- plugin package/JAR symlink/replacement/restart races.

Potential truncation candidates found but **not yet proven defective**: `FfmpegAudioPlaybackBackend` diagnostic string and `LineLayout.toString()` preview. Add a regression first before any production change.

## 9. P5 — performance continuation

V9 evidence remains authoritative: keep **G1 + `-Xmx1024m`**.

Only revisit defaults for a concrete product need and with paired workloads, e.g.:

- scripted packaged UI/JFR pause profile;
- Lucene search while rebuild runs, with UI responsiveness signal;
- combined Lucene rebuild + large Reader + packaged UI with PSS sampling;
- byte-equivalent datasets/homes and explicit logging of aborted runs.

## 10. Next checkpoint gates

Before V11 baseline:

- every new defect has red-before/green-after regression evidence;
- modified full module suites green;
- full UI under Xvfb green;
- completeness/static/XML/archive/supply-chain green;
- production package succeeds;
- consolidated `clean verify` succeeds;
- Linux stage23 + packaged launcher smoke green if packaging/runtime-affecting code changed;
- Windows smoke documented if a Windows environment becomes available;
- clean ZIP excludes `target/`, `dist/`, `.git/`, `.mvn/repository/`, logs/temp/cache;
- ZIP CRC passes and SHA-256 is recorded.
