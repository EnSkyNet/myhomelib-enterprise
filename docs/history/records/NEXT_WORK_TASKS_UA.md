# MyHomeLib — завдання для подальшої роботи

Дата зрізу: 2026-09-26

Цей файл описує наступний етап після вже внесених виправлень. Він сформований за фактичним станом `audit_stage3_work` і повторним запуском проблемних regression/static-check скриптів.

## 1. Пріоритет P0 — привести regression-checks у відповідність до нової реалізації

Частина поточних FAIL не означає дефект продукту: перевірки шукають старі точні рядки коду, хоча реалізація вже перейшла на `SafeFilePublication`, розширені сигнатури та інші безпечні абстракції. Не можна просто вимкнути ці перевірки — їх треба переписати на поведінкові/семантичні контракти.

### 1.1 Reader settings + autosave

Поточні FAIL:
- `tools/stage19-20-reader-check.py`
- `tools/review4-critical-behavior-check.py`

Фактичний код:
- `ReaderPreferencesService` вже пише налаштування через `SafeFilePublication` із staging/snapshot/publish.
- `ReaderPositionAutosaver` запускає `scheduleWithFixedDelay(this::flush, 3, 3, TimeUnit.SECONDS)` і зберігає невдалі записи у `pendingFailures`.

Завдання:
1. Замінити перевірку конкретних рядків `AtomicFileSupport.moveReplacing` / `flushIfDirty` / `restoreDirty(snapshot)` на перевірку фактичної семантики.
2. Додати unit/integration-тести:
   - позиція автоматично зберігається не пізніше ніж приблизно через 3 с;
   - помилка SQLite не губить позицію;
   - retry зберігає саме останню revision для книги;
   - перемикання між книгами не переносить dirty-state на іншу книгу;
   - `close()` робить фінальний flush і чітко сигналізує про незбережений стан.
3. Після цього `stage19-20-reader-check.py` і `review4-critical-behavior-check.py` мають завершуватись кодом 0.

### 1.2 Updates workspace

Поточний FAIL: `tools/stage7-online-updates-ui-check.py`.

Причина guard-а: він шукає точний виклик `downloadUpdate(book)`, а поточний код використовує `downloadUpdate(book, expectedCollectionId)` для захисту від зміни активної колекції.

Завдання:
1. Оновити guard під актуальну сигнатуру.
2. Додати тест, що `UPDATED_DOWNLOADED_BOOK` завжди використовує force-download authoritative версії.
3. Перевірити, що після успіху baseline acknowledge виконується лише для тієї самої колекції.

### 1.3 Book actions harness

Поточний FAIL: `tools/stage14-15-actions-check.py`.

Причина: isolated `javac` harness не включає нові залежності:
- `SafeFilePublication`;
- `ExecutablePathSafety`.

Завдання:
1. Додати ці класи та їх транзитивні мінімальні залежності до harness.
2. Не послаблювати перевірку executable path safety.
3. Додати негативні кейси для symlink/relative traversal/заміни executable між validation і запуском.

### 1.4 Export-to-device guard

Поточний FAIL: `tools/stage16-export-profiles-check.py`.

Фактична реалізація вже використовує:
- `SafeFilePublication.copyFresh(...)`;
- staging workspace;
- `verifyExportedFile(...)`;
- `commitExportedFile(...)`;
- `SafeFilePublication.moveNoReplace(...)`;
- rollback попередньої цілі при помилці completion.

Завдання:
1. Переписати checker з пошуку `Files.copy(...)` та literal `ATOMIC_MOVE` на перевірку контракту `SafeFilePublication`.
2. Додати runtime-тести crash-safe publication:
   - ціль не з'являється до завершення staging;
   - overwrite не знищує чужу зміну файлу;
   - помилка completion відновлює попередній файл;
   - hash після publication збігається зі staged output.

### 1.5 Backup/restore guard

Поточний FAIL: `tools/stage22-versioned-user-data-check.py` (`restore staging/atomic swap missing`).

У поточному `BackupRestoreService` вже є candidate/swap flow (`installDatabaseCandidate`, `RestoreSwap`) та `SafeFilePublication`.

Завдання:
1. Оновити guard під фактичний candidate/swap API.
2. Додати інтеграційний тест аварії між:
   - candidate validation;
   - detach старої DB;
   - publication нової DB;
   - reopen/migration.
3. Після будь-якої помилки має бути або стара валідна DB, або нова валідна DB — ніколи частково замінена.

### 1.6 Collection maintenance watcher

Поточний FAIL: `tools/stage12-13-collection-maintenance-check.py`, який очікує literal `Sha256Support.file(source)`.

Завдання:
1. Перевірити, який централізований hash API реально використовується зараз.
2. Оновити guard на API-контракт, а не на назву конкретного helper-а.
3. Залишити вимоги: bounded/debounced watcher, SHA-256 перед refresh, відсутність destructive repair без preview/backup.

### 1.7 OPDS guard

Поточний FAIL: `tools/stage17-18-opds-check.py`.

Завдання:
1. Перевірити поточний streaming response path OPDS і замінити literal-вимогу `Files.copy(path, out)` на перевірку bounded/streaming file delivery без завантаження книги цілком у RAM.
2. Узгодити `ARCHITECTURE.md` із фактичним OPDS-модулем і залежностями.
3. Додати тест великого файла та client disconnect, щоб stream/file handle гарантовано закривались.

## 2. Пріоритет P0 — реальні поведінкові перевірки перед наступним релізом

### 2.1 Повний тест 707k INPX після всіх наступних змін

Використовувати реальний `flibusta_online_fb2.inpx`.

Приймальні критерії:
- 707k записів імпортуються без втрат;
- `PRAGMA quick_check = ok`;
- кількість deferred index jobs після стабілізації = 0;
- повторний запуск не робить повний повторний імпорт без зміни джерела;
- UI стає придатним до роботи до завершення необов'язкових background rebuild-ів;
- цільовий critical path завантаження колекції ≤ 5 хв на типовому ПК користувача.

### 2.2 Конвертація на Windows з реальним Calibre

Linux-тести не замінюють реальний `ebook-convert.exe`.

Приймальні критерії:
- після FB2→EPUB `books.file_name/folder/archive_entry/collection_root` залишаються оригінальними;
- EPUB реєструється лише як додатковий artifact;
- після перезапуску доступні і original, і converted artifact;
- відмова/timeout Calibre не змінює preferred/original path;
- temporary workspace очищується після success/failure/cancel.

### 2.3 Startup/splash на Windows

Перевірити реальний packaged desktop build.

Приймальні критерії:
- splash з'являється практично одразу після запуску;
- немає двох «порожніх» пауз до/після splash;
- splash показує фактичні етапи startup;
- main window відкривається тільки у ready-state;
- автори/навігація не «догружаються» через десятки секунд після появи порожнього UI, якщо це критичні дані першого екрана.

## 3. Пріоритет P1 — архітектурний борг

### 3.1 Розділити `MainController`

Поточний `tools/stage25a-ui-orchestration-check.py` фіксує 687 рядків.

Ціль: залишити `MainController` як thin shell/composition root, винісши щонайменше:
- menu/action orchestration;
- collection commands;
- window/dialog launch logic;
- layout/theme/language reactions;
- workspace/navigation coordination.

Приймальний критерій: controller суттєво менший, constructor dependency count зменшений, функціональні UI-тести не регресують.

### 3.2 Усунути 19 cross-file clone warnings

`implementation-completeness-check.py` знаходить 19 exact-method clones. Найцінніші кандидати для першого проходу:
- query/edit lease wrappers;
- SQLite sidecar cleanup;
- safe publication/hash helpers;
- ZIP safety scoring;
- reader/audio autosave retry code;
- recovery-file helpers.

Не об'єднувати код лише заради метрики: виносити тільки стабільні спільні абстракції з чіткою відповідальністю.

## 4. Пріоритет P1 — release/tooling

### 4.1 Maven Wrapper має бути консистентним

`tools/supply-chain-policy-check.py` зараз бачить `mvnw`/`mvnw.cmd`, але немає повного `.mvn/wrapper` payload.

Обрати один варіант і закріпити політикою:
- або повний Maven Wrapper з pinned version + SHA-256;
- або прибрати `mvnw`/`mvnw.cmd` із source distribution та явно вимагати системний Maven.

Не залишати «половину wrapper-а».

### 4.2 Release artifact validation

`tools/stage23-cross-platform-release-check.py` падає, коли немає `dist/`.

Завдання:
1. Розділити source-check і packaged-artifact-check.
2. `dist`-валідацію запускати після `package-*`.
3. Додати CI job для Linux source/test і окремий Windows packaging/smoke job.

### 4.3 FX test execution checker

`fx-test-execution-check.py` потребує параметр `reports`; попередній orchestration запускав його без аргументу.

Завдання: передавати каталог Surefire/Failsafe reports і падати, якщо звітів немає або кількість тестів нижча за очікуваний поріг.

## 5. Пріоритет P2 — додаткове посилення

1. Property/fuzz tests для INPX, ZIP/FB2/EPUB та malformed XML.
2. Crash/fault injection для atomic publication, backup/restore, catalog update, organizer undo.
3. JFR/async-profiler профіль на 707k колекції: startup, author browse, global search, Lucene rebuild, import delta.
4. Перевірка bounded memory на великих ZIP/EPUB/FB2 та при паралельних downloads.
5. Windows-only smoke: DPAPI, file associations, Desktop/Shell integration, long paths, non-ASCII paths.
6. Перевірка SQLite busy/locked сценаріїв і backoff при антивірусі/індексаторі Windows.
7. Додати regression benchmark thresholds у CI без надто жорстких абсолютних таймінгів.

## 6. Порядок наступного циклу

Рекомендований порядок:
1. Спочатку оновити застарілі regression guards і додати behavioral tests замість literal source checks.
2. Закрити Reader autosave/restore/export/OPDS tests.
3. Вирішити Maven Wrapper + release pipeline.
4. Розділити `MainController`.
5. Повторити повний Maven verify.
6. Повторити реальний 707k INPX benchmark.
7. На Windows прогнати packaged app + splash + Calibre end-to-end.
8. Лише після цього формувати release candidate.

## 7. Відомий стан на момент цього архіву

Повторно запущені проблемні guards, які все ще повертають non-zero:
- `stage19-20-reader-check.py`;
- `stage7-online-updates-ui-check.py`;
- `stage14-15-actions-check.py`;
- `review4-critical-behavior-check.py`;
- `stage25a-ui-orchestration-check.py`;
- `stage23-cross-platform-release-check.py`;
- `stage16-export-profiles-check.py`;
- `supply-chain-policy-check.py`;
- `stage17-18-opds-check.py`;
- `stage22-versioned-user-data-check.py`;
- `stage12-13-collection-maintenance-check.py`.

Важливо: частина цих FAIL — застарілі source-pattern checks після переходу на безпечніші абстракції, а не доведені runtime-дефекти. Саме тому наступний цикл має починатися з поведінкової верифікації, а не з механічного «повернення» старих рядків коду.
