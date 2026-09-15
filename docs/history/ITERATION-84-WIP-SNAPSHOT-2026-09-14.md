# MyHomeLib 8.0.0 — Iteration 84 WIP snapshot

Дата snapshot: 2026-09-14
Статус: робочий проміжний кандидат; фінальний regression/test cycle ще НЕ запускався.
База: Iteration 83 source від 2026-09-13.

## Що вже реалізовано

### 1. Reader: анотації та нотатки
- Повна Reader-модель анотації: type, quote, note text, tags, state/relocation metadata замість одного boolean note.
- Детермінований hit-test анотацій і активація мишею/клавіатурою.
- Пріоритет note-marker при перекритті діапазонів.
- Спільний AnnotationEditorDialog для create/edit: цитата, текст нотатки, колір, теги, validation.
- Reader annotation popover: edit, delete, copy quote, copy quote + note, reanchor/rebind.
- Явні стани resolved / relocated / artifact mismatch / unresolved.
- Безпечний artifact rebind: тільки при достатньо надійному quote/context match і після дії користувача.
- Annotation workflow винесено з великого NewReaderWorkspaceController в ReaderAnnotationCoordinator.

### 2. Reader UI/UX
- Єдина бічна панель: Зміст / Пошук / Закладки / Нотатки / Карта книги.
- Фільтрація анотацій за текстом, типом і тегами.
- Групування списку анотацій за главами.
- Book Map: прогрес + щільність notes/highlights/bookmarks по главах.
- Текстовий пошук у Reader може працювати в боковій панелі для звичайних текстових форматів.
- Основний Reader toolbar спрощено; рідкі команди перенесено в overflow `⋮`.
- Повернення фокусу в Reader після popover/dialog actions.
- Restore-position використовує semantic textOffset як джерело істини і переобчислює chapterIndex після reflow/змін документа.

### 3. Annotation Manager / knowledge workflow
- Узгоджена multi-selection семантика: Open/Edit — один запис; Delete/Color/Tags/Export — batch.
- Undo history до 20 логічних операцій; batch delete — одна undo-операція.
- Batch color, add/remove tags, selected export.
- Markdown «Конспект з анотацій» із групуванням книга → глава → цитата → нотатка → теги → MyHomeLib backlink; порядок відповідає позиції в книзі, а книги з однаковою назвою розрізняються за `bookId`.
- Digest export публікується атомарно через тимчасовий файл.

### 4. Main UI / search / library
- Main toolbar переведено на адаптивний однорядковий HBox.
- Selection actions винесено в контекстну панель, що з’являється лише при виборі книг.
- Pinned Smart Collection зберігається як явний scope під час text/advanced search.
- «Скинути область» прибирає scope без втрати введеного запиту/фільтрів.
- Unsupported content-index scope не маскується глобальними результатами.
- Book Details структуровано на Main / Reading / Files / Library / Technical.
- Follow Author UI чіткіше показує follow state і new-book count.

### 5. Масштаб / runtime / QA-підготовка
- Виправлено replacement-accounting у Reader ImageCache: заміна існуючого ключа більше не витісняє зайві сусідні зображення; статистичні/read-методи синхронізовані.
- CSV та selected-digest export у Annotation Manager переведено на temp + atomic move (із safe replace fallback), щоб cancel/error не залишав частково записаний фінальний файл.
- CSV-експорт нейтралізує spreadsheet formula markers (`=`, `+`, `-`, `@`) лише у вихідному CSV, не змінюючи дані нотаток у БД.
- Для повного INPX-каталогу >=100k записів дозволений bounded batch 5000 замість стандартного 1000.
- Доданий cancellation-контракт до початку bulk DB mutation.
- Runtime UTF-8 default-charset guard; Windows native.encoding лишається діагностикою, а не false failure.
- Доданий JavaFX CI gate через Xvfb та перевірка, що всі 3 *FxTest suites реально виконались.
- Доданий C.UTF-8 regression gate для Unicode path/export.
- Додані/оновлені acceptance/contract tests для annotation lifecycle, relocation ambiguity, hit-test, restore-position/reflow, UI structure.

### 6. Версія і локалізація
- Активну product/Maven версію уніфіковано на 8.0.0.
- Legacy v71-* entrypoints не перейменовуються для сумісності, але більше не позиціонуються як поточна версія продукту.
- Нові UI-ключі синхронізовано для UK/EN/BG; root Lang і bundled lang узгоджені.

## Важливо: що ще НЕ підтверджено

Цей snapshot створено ДО фінального тестового циклу за домовленістю. Тому цей архів не є release/PASS build.

Ще належить:
- завершити статичне оновлення застарілих UI/version regression-contracts;
- code freeze;
- один фінальний Maven/build/test cycle;
- JavaFX/Xvfb gate;
- Unicode/C.UTF-8 gate;
- великий INPX acceptance/performance run;
- Windows-specific TTS/FXML acceptance там, де потрібне Windows-середовище;
- виправити всі знайдені фінальним прогоном регресії;
- сформувати фінальний Iteration 84 source ZIP і test report.

## Поточний change-set проти Iteration 83

На момент підготовки snapshot: понад 100 змінених/нових файлів; окремий детальний changelog: `docs/release/ITERATION-84-CHANGELOG.md`.


## Додатково виправлено після первинного snapshot-pass

- Усунуто production FXML type mismatch: `MainView.fxml` використовує `HBox mainToolbar`, і `MainController` тепер теж очікує `HBox`, а не старий `FlowPane`.
- Прибрано застарілу залежність MainController від `themeButton`; зміна теми лишається доступною через overflow menu.
- Оновлено toolbar CSS з wrapping FlowPane contract на single-row adaptive HBox contract.
- Оновлено старі `MainToolbarLayoutContractTest`, `MainToolbarWrapFxTest`, `UiFxmlRegressionTest` і `search-toolbar-genre-followup-check.py`, щоб вони перевіряли новий UI, а не вимагали повернення старого.
- Статично звірено FXML wiring для Main/Annotations/Author/Details/Reader/Search: відсутніх handler-методів у змінених workspace FXML не знайдено.
- Статично звірено Maven identity: root + усі 15 модулів використовують/успадковують 8.0.0.
- Windows desktop acceptance report більше не друкує активний продукт як «7.1»: заголовок і JSON `projectVersion` беруть фактичну версію з root `pom.xml`; legacy script filenames лишаються сумісними.

- Додано acceptance-contract POST через повний `HttpOnlineBookDownloadAdapter` та redirect для валідного remote INPX.
- Оновлено `stage36-reader-annotations-check.py` під повну Iteration 84 annotation-модель/coordinator і `inpx-import-performance-check.py` під нову large-catalog batch policy.
