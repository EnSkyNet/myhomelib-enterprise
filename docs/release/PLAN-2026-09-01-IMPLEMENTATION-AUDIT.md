# MyHomeLib Enterprise — виконання плану 01.09.2026

Дата аудиту: 2026-09-01

## 1. Підсумок

План реалізовано на рівні вихідного коду та офлайн regression/acceptance guard-ів. Основний принцип змін — не створювати паралельні механізми вибору книг і не використовувати відсутність запису в одному INPX snapshot як ознаку видалення.

## 2. Виконані блоки

### P0 — безпечний INPX-імпорт
- Прибрано snapshot-логіку, за якої відсутній запис міг масово перейти в `deleted`.
- Видалення конкретної книги визначається явним `DEL`.
- Відсутній автор нормалізується як `Без автора`.
- Відсутній жанр не блокує імпорт; жанри з INPX зберігаються у зв'язках книги.
- Розширено статистику імпорту: без автора, без жанру, явно видалені тощо.
- Додано/оновлено regression fixtures для INPX normalizer/pipeline.

### P0 — єдиний checkbox selection
- Додано `BookSelectionService` як один batch-selection source (`Set<BookId>`).
- Download / Remove Local / Delete / Export / Copy між колекціями / TreeTable batch actions переведено на snapshot цього сервісу.
- При порожньому checkbox selection поточний рядок більше не підставляється автоматично.
- Selection очищається при реальній зміні активної колекції.
- Bulk Select All оптимізований: count/state не перераховується N разів на N книг.

### P0/P1 — master checkbox
- Підтримуються NONE / PARTIAL / ALL.
- Master checkbox працює лише з поточним видимим набором книг.
- Group/series rows не рахуються як книги.
- Статус batch selection показує окремий лічильник.

### P1 — Author Workspace
- Видиму пагінацію прибрано.
- `LoadBooksByAuthorUseCase.executeAll()` читає SQL порціями, але користувач бачить один workspace.
- Дані вантажаться у background executor; TableView зберігає virtualization.
- Серії мають group header, collapse/expand та series checkbox з indeterminate state.
- Додано Collapse All / Expand All; команда за потреби повертає сортування до SERIES, щоб дія була видимою.
- Selection не губиться при collapse/expand та refresh.
- Повернуто колонку жанрів; жанри беруться з фактичної моделі/SQL-пошуку.
- Додано колонку року та фізичний локальний статус.
- Додано фільтр Усі / Завантажені / Не завантажені.
- Space, Ctrl+A, Left/Right та збереження table profile/sort підключені.
- Після refresh перша книга автоматично не вибирається.

### P1 — фізична локальна доступність
- Додано `ResolveBookLocalAvailabilityUseCase` поверх `BookResourcePort`.
- UI не вважає книгу локальною лише за прапорцем БД.
- Batch download пропускає фактично локальні книги та розділяє downloaded/already-local/failed у результаті.

### P1 — тема всієї програми
- Додано SYSTEM / LIGHT / DARK / CUSTOM.
- Налаштування зберігаються через application preference facade.
- Додано live preview і reset до стандартних.
- Централізовано background/panel/text/accent/series/book/downloaded-row/font-size.
- Шестизначні hardcoded UI colors прибрано з FXML/CSS/контролерів за межі центрального theme service.
- Тема застосовується до наявних і нових JavaFX windows/scenes.

### P1 — експорт на пристрій
- Експорт працює тільки з checkbox selection.
- Перед експортом перевіряється фактичний локальний ресурс.
- Archive entry читається через `BookResourcePort`; shared archive не копіюється замість книги.
- Перевіряються destination directory, write access і usable space.
- Підтримуються collision policies OVERWRITE / SKIP / RENAME / ASK.
- Експорт/конвертація спочатку пише у staging-файл у destination directory.
- Після перевірки staging виконується atomic move (з fallback), після чого фінальний файл повторно перевіряється на існування, ненульовий розмір і читабельність.
- Частковий staging-файл видаляється при помилці.

## 3. Додатково виправлено під час аудиту

- Listener accumulation у virtualized checkbox cells Author/Tree таблиць.
- Залишки старої selection-моделі в Copy Between Collections і TreeTable.
- Надлишкові hardcoded UI colors у JavaFX dialogs/progress/alphabet toolbar/Reader settings chrome.
- Regression guards, які все ще вимагали стару Author pagination, переведено на новий acceptance contract, а не вимкнено.
- `stage16-export-profiles-check.py` посилено вимогою staging + atomic commit + final verification.
- Додано `tools/plan-2026-09-01-acceptance-check.py`.
- `mvnw` має executable bit у фінальному ZIP для Linux/macOS середовища.

## 4. Перевірки

Після останніх змін пройдено 49 із 49 доступних source/offline `*check.py` перевірок, за винятком `stage23-cross-platform-release-check.py`, який вимагає вже сформований `dist/` і тому не входить до source-only прогону.

Ключові PASS:
- architecture-check
- implementation-completeness-check
- ui-function-reachability-check
- startup-nonblocking-check
- static_release_check
- functional-regression-check (27 critical behavior ratchets)
- review4-critical-behavior-check
- plan-2026-09-01-acceptance-check
- inpx-import-performance-check
- stage16-export-profiles-check
- Lucene/search/lifecycle/online/Reader/user-data checks
- stages 3–22, 24, 25A, 25B, 25C

## 5. Що НЕ можна чесно вважати runtime-перевіреним у цьому середовищі

1. `./mvnw compile/test/verify`: Maven Wrapper намагається завантажити Maven з `repo.maven.apache.org`, але середовище не має зовнішнього DNS/network access (`UnknownHostException`).
2. `stage23-cross-platform-release-check.py`: немає зібраного `dist/`, а створити коректний package без Maven/JavaFX build toolchain тут неможливо.
3. Реальний повторний імпорт каталогу приблизно на 700 000 записів: dataset/реальна БД для такого runtime benchmark у задачі не надані. Hot-path/static guards проходять, але це не заміна фактичного 700k run.
4. Фізичний E2E export на реальний USB/e-reader/мережевий пристрій: у контейнері такого пристрою немає. Код перевіряє destination, I/O, staging/commit і final file, але реальний hardware disconnect/read-only/full-device сценарій має бути прогнаний у Windows/Linux runtime environment.
5. Live JavaFX smoke для SYSTEM/LIGHT/DARK/CUSTOM теми: source/FXML/static guards проходять, але повний visual runtime smoke потребує JavaFX build/runtime.

## 6. Рекомендований RC-gate поза контейнером

Перед позначенням збірки RC:
1. `./mvnw clean verify` у середовищі з доступом до Maven dependencies.
2. JavaFX smoke: MainView, Author Workspace, Search, Reader, Settings/theme.
3. INPX: повний 700k import + повторний import + cancel/forced exception; порівняти counts/deleted state.
4. Selection: A checked / B current для Download, Remove Local, Delete, Export, Copy.
5. Author 1/99/100/101/1000+ books; series partial checkbox; collapse/expand.
6. Export 10+ books на реальний пристрій: FB2, EPUB, ZIP entry, collisions, insufficient space/read-only/disconnect.
7. Побудувати `dist/` та виконати stage23 cross-platform release check.

## 7. Висновок

На рівні source code та доступних offline guards план 01.09.2026 виконаний. Відомих source-level regression failures після фінального прогону немає. Для повного RC залишаються лише перевірки, які потребують Maven/JavaFX runtime, великого реального INPX dataset і фізичного зовнішнього пристрою.
