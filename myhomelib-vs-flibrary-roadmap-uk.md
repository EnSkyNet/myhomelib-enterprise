# Порівняння MyHomeLib Enterprise, MyHomeLib та FLibrary

Дата аналізу: 24 серпня 2026

## 1. Висновок

Поточний MyHomeLib Enterprise не варто перетворювати на копію MyHomeLib або FLibrary. Найкраща стратегія — зберегти його сучасну Java/Maven архітектуру, вбудований Canvas-reader, Lucene, archive layer, MCP та файлову локалізацію, а з двох зрілих проєктів перенести перевірені моделі поведінки та UX.

Найцінніше з оригінального MyHomeLib: зріла модель колекцій, сумісність INPX/HLC2, зовнішні reader/device/converter workflows, переносимість користувацьких даних, локалізація жанрів та контекстна довідка F1.

Найцінніше з FLibrary: багаторежимна навігація, централізована генерація дерева/списку, єдина система фільтрів, багата панель анотації, автоматичне відстеження оновлень колекції, cleaner/repair, hotkeys/menu customization, recent/history, OPDS як окремий сервіс, script actions та кросплатформений release pipeline.

## 2. Стан поточного проєкту

Поточна кодова база має 11 Maven-модулів: shared, domain, application, infrastructure, ui, bootstrap, architecture-tests, e2e-tests, benchmark, reader та mcp. У reader є окремі `ReaderCanvas`, `TextLayoutEngine`, `Fb2StreamingParser`, тобто фактична архітектура вже не відповідає старому опису WebView у `ARCHITECTURE.md`.

Сильні сторони:
- сучасна Java 21 / JavaFX база;
- модульне розділення domain/application/infrastructure/UI;
- окремий reader-модуль з Canvas rendering;
- FB2/FBD/EPUB/TXT читання, TOC, bookmarks, search, themes, reading position;
- Lucene-пошук по title/authors/series/genres/keywords/annotation/file/publisher/language/year/rating;
- багато форматів архівів та archive-aware open/export;
- MCP;
- Flyway, architecture/e2e/benchmark modules;
- динамічна `Lang/*.json` локалізація.

Основні слабкі місця:
- навігаційна панель фактично має лише Authors / Series / Genres;
- BookDetails набагато бідніший за FLibrary annotation pane;
- немає системи настроюваних hotkeys;
- немає OPDS;
- стан колонок таблиці не є повноцінним persisted profile;
- немає єдиного FilterSpec, який однаково працює для navigation/search/table;
- `ARCHITECTURE.md` застарів: описує 9 модулів і WebView замість 11 модулів і Canvas-reader;
- `myhomelib-ui/pom.xml` досі має пряму залежність на infrastructure, хоча Java UI-код не імпортує infrastructure напряму;
- у поточному packaged baseline немає завершеної моделі `catalog revision` / `downloaded revision` для надійного визначення нових та оновлених книг автора.

## 3. Порівняльна матриця

| Напрям | MyHomeLib Enterprise | MyHomeLib | FLibrary | Рішення |
|---|---|---|---|---|
| Архітектура | Найкраща база, але є drift документації | Зрілий Delphi/VCL, більше legacy/global-state | C++23/Qt, хороші interfaces, але великі GUI-класи | Залишити Java architecture |
| Вбудований reader | Найсильніша сторона | Орієнтація на зовнішні reader-и | Орієнтація на open/extract + annotation | Не відмовлятися від Canvas-reader |
| Навігація | 3 основні режими | Authors/Series/Genres | 14 NavigationMode | Перенести модель FLibrary |
| Пошук | Дуже сильний Lucene | Дуже зрілий classic syntax | Сильний пошук + filters | Зберегти Lucene, додати FLibrary filter UX |
| Фільтри | Розрізнені між екранами | Переважно search-oriented | Єдиний FilterController, AND/OR, quick filters | Адаптувати FLibrary pattern |
| Annotation/details | Базовий | Зріле редагування описів | Дуже багатий annotation pane | Суттєво розширити |
| Колекції | Широкий функціонал | Найзріліша семантика MHL/INPX | AutoUpdater/Cleaner/Recreate | Об'єднати MHL compatibility + FLibrary maintenance |
| Online updates | Потрібна надійна revision model | Оновлення online collections | Updates mode + collection watcher | Власна revision model + FLibrary UX |
| Hotkeys/menu | Майже відсутні | Класичний desktop UX | Кастомні hotkeys/hide/icons | Перенести action registry pattern |
| Recent/history | Є внутрішні building blocks | Класичний workflow | Recent books + history/read modes + undo/redo nav | Зробити user-facing |
| Export/device | Уже сильний | Дуже сильний | Profiles/scripts/collision handling | Розвинути існуюче |
| OPDS | Немає | Не ключова функція | Окремий OPDS controller/process | Додати окремим модулем |
| Локалізація | Drop-in JSON — найгнучкіше | Built-ins + external signed catalogs; localized genres | Qt translation model | Зберегти вашу схему, взяти genre localization |
| Довідка | Неповна | 55-сторінкова context F1 help | Є документація | Взяти модель MyHomeLib |
| MCP | Є | Є | Не є основною особливістю | Зберегти й розвивати |
| Cross-platform release | Технічно можливий, але pipeline треба зміцнити | Windows | Windows/Linux/macOS + CI | Взяти release discipline FLibrary |

## 4. Що конкретно взяти з MyHomeLib

### 4.1. Контекстну довідку
Повна HTML/Markdown довідка з mapping `workspace/dialog -> help page`; F1 відкриває сторінку поточного контексту. Це дає набагато кращий UX, ніж один загальний README.

### 4.2. Локалізацію жанрів
FB2 genre code повинен залишатися стабільним ID, а display name — приходити з мовного каталогу. При зміні UI language дерево жанрів повинно змінювати назви без зміни book-genre relations.

### 4.3. Регресійну сумісність колекцій
MyHomeLib добре задає поведінковий контракт: HLC2/INPX, copy between collections, external/online collections, user data by LibID, portable mode. Ваш проєкт уже покриває значну частину; потрібно перетворити це на автоматизовані regression scenarios.

### 4.4. Зовнішні reader/device workflows
Ваш проєкт уже має команди reader-ів, конвертери, шаблони і post-command. З MyHomeLib варто брати не нову архітектуру, а compatibility semantics: placeholder-и, quoting, predictable file naming, portable paths і user data migration.

## 5. Що конкретно взяти з FLibrary

### 5.1. NavigationMode
У локальному FLibrary є 14 режимів: Authors, Series, Genres, PublishYear, Keywords, Updates, Archives, Languages, Groups, Search, Reviews, AlreadyRead, History, AllBooks. Це найцінніша UX-модель для вашого головного вікна.

### 5.2. BooksTreeGenerator
Не будувати кожне дерево окремо в controller. Потрібен application-level `NavigationQueryService` / `NavigationTreeBuilder`, який повертає однакову view-model структуру для UI.

### 5.3. FilterController
Єдиний filter state, persisted settings, rating range, hide-unrated, language та інші flags, AND/OR accumulation. У вашому проєкті це треба накласти поверх Lucene/SQL, а не замінювати Lucene.

### 5.4. AnnotationController
FLibrary віддає annotation, language/source language, FB2 keywords, covers, text size, word count, content, translators, publisher та інші поля. Ваш BookDetails має стати інформаційною панеллю, а не тільки набором кількох labels.

### 5.5. CollectionAutoUpdater
FLibrary використовує filesystem watcher, debounce приблизно хвилину та hash check. Java-аналог: `WatchService` + scheduled debounce + SHA-256/fingerprint + перевірка читабельності INPX/archive перед update.

### 5.6. CollectionCleaner
Потрібен окремий safe maintenance workflow: analyze -> preview -> dry run -> apply -> report. Ніякого автоматичного destructive cleanup без preview.

### 5.7. MenuCustomizer + hotkeys
Потрібен ActionRegistry: command ID, localized title, default shortcut, current shortcut, visibility, context predicate, handler. FXML/controller не повинні бути джерелом shortcut-логіки.

### 5.8. Recent/history/navigation undo-redo
Список недавно відкритих книг з timestamp, history workspace та back/forward по навігації. У вас уже є частина history services, отже це дешевше, ніж реалізація з нуля.

### 5.9. OPDS
Реалізувати як окремий `myhomelib-opds` module/process із read-only доступом. Не запускати HTTP-server всередині JavaFX controller.

### 5.10. Script actions
Замість одного post-command — named profiles, ordered commands, args, working directory, placeholders та прив'язка до контекстного меню книги.

### 5.11. Release pipeline
FLibrary уже має Windows/Linux/macOS build paths і CI. Для Java-проєкту аналогом мають стати GitHub Actions matrix + `jpackage`/portable artifacts + checksum + clean-machine smoke test.

## 6. Що НЕ переносити

- Не переписувати Java/JavaFX на C++/Qt.
- Не копіювати монолітний стиль великих `MainWindow.cpp` / `TreeView.cpp`.
- Не переносити SQL-рядки прямо в JavaFX controllers.
- Не повертатися до external-reader-only концепції: вбудований reader — конкурентна перевага вашого проєкту.
- Не переносити VCL/DataModule/global-state модель оригінального MyHomeLib.
- Не робити mandatory signature для `Lang/*.json`: це суперечить вашій вимозі drop-in мов. Доцільні schema validation, metadata та diagnostics; signing — тільки як optional trusted-pack mechanism.
- Не видаляти Spring/DI або Lucene лише заради “полегшення”, доки benchmark не показав реальну проблему.
- Не починати повноцінний PDF/DjVu/MOBI reader до стабілізації основної бібліотеки та FB2/EPUB reader.

# 7. Roadmap: один етап = один запит

Кожен етап нижче спеціально обмежений так, щоб його можна було виконати за один запит із видачею повного ZIP. Правило для кожного етапу: працювати від останнього архіву, не переходити до наступного етапу, додати/оновити тести, зробити доступні статичні/офлайн перевірки, у кінці видати повний ZIP + `STAGE-N-CHANGELOG.md` + `STAGE-N-VALIDATION.md`.

## Етап 1. Architecture baseline
**Мета:** прибрати drift перед новими функціями.

Роботи:
- переписати `ARCHITECTURE.md` під фактичні 11 модулів і Canvas-reader;
- перевірити/прибрати непотрібну `ui -> infrastructure` Maven dependency;
- звірити ArchUnit rules з фактичними boundaries;
- додати architecture regression tests;
- оновити README module map.

**Готово коли:** dependency graph документований і architecture tests не суперечать фактичному коду.

## Етап 2. Navigation core
**Мета:** закласти FLibrary-like navigation без розширення всіх режимів одразу.

Роботи:
- винести `NavigationMode` з controller у application/shared API;
- створити `NavigationQueryService` і універсальні navigation node DTO;
- перевести Authors/Series/Genres на новий контракт;
- додати AllBooks;
- зберегти alphabet filter та існуючу поведінку workspace.

**Готово коли:** UI controller більше не знає, як конкретно будується дерево Authors/Series/Genres.

## Етап 3. Navigation: Year / Language / Archive
**Мета:** додати три дешеві й корисні режими.

Роботи:
- PublishYear;
- Languages;
- Archives / source files;
- counts для navigation nodes;
- однакові sort/filter semantics.

**Готово коли:** всі три режими використовують той самий NavigationQueryService.

## Етап 4. Navigation: Keywords / Groups / Reviews
**Мета:** перенести user-data та metadata навігацію.

Роботи:
- Keywords;
- Groups/Favorites;
- Reviews/rated-reviewed subset;
- deep links з details pane у відповідний navigation mode.

## Етап 5. Recent / AlreadyRead / History + navigation history
**Мета:** зробити історію видимою користувачу.

Роботи:
- Recent books menu з timestamp;
- AlreadyRead workspace/mode;
- History mode;
- Back/Forward або Undo/Redo navigation поверх наявного history service;
- clear history action.

## Етап 6. Online update model — data layer
**Мета:** надійно відрізняти catalog update від локальної зміни.

Роботи:
- Flyway migration: catalog/source revision/fingerprint + downloaded revision/baseline;
- stable source identity для remote INPX;
- UPSERT, який не перезаписує local file/rating/progress/review/bookmarks;
- визначення `NEW_BY_FOLLOWED_AUTHOR` та `UPDATED_DOWNLOADED_BOOK`;
- regression tests повторного sync без false positives.

**Готово коли:** повторний імпорт того самого INPX не породжує updates, а зміна metadata/file revision породжує саме один update.

## Етап 7. Online Updates — UI
**Мета:** реалізувати початкове побажання користувача.

Роботи:
- navigation `Updates`;
- структура `Автор -> Нові книги / Оновлені книги -> книги`;
- counters/badge;
- clear/acknowledge після успішного download;
- open author/book from update;
- empty state.

## Етап 8. Unified Filter Engine
**Мета:** єдиний фільтр для navigation/search/table.

Роботи:
- `BookFilterSpec`;
- persisted filter state;
- language/year/format/local/read/rating min-max/unrated;
- AND/OR mode;
- filter counts;
- adapter до SQL/Lucene.

## Етап 9. Table profiles + quick filters
**Мета:** взяти найкраще з FLibrary table UX.

Роботи:
- persist width/order/visibility/sort;
- окремий profile per workspace/view mode;
- quick filter по колонці;
- indicator активного filter;
- reset defaults;
- regression для series grouping під filter/sort.

## Етап 10. Rich Book Details / Annotation — FB2/EPUB
**Мета:** перетворити BookDetails у багату annotation panel.

Роботи:
- structured metadata sections;
- cover, annotation, authors/series/genres/keywords as links;
- publisher/year/ISBN/language/source language/translators;
- TOC preview;
- text size/word count;
- reading/user-data section.

## Етап 11. Extra-format metadata and images
**Мета:** розширити annotation без написання нових reader engines.

Роботи:
- MOBI metadata/cover/annotation;
- PDF cover/basic metadata;
- DjVu cover/basic metadata;
- all-images gallery API where technically safe;
- graceful fallback for unsupported/corrupt files.

## Етап 12. Collection AutoUpdater
**Мета:** FLibrary-like автоматична реакція на зміни джерела.

Роботи:
- Java NIO `WatchService`;
- debounce;
- SHA-256/fingerprint;
- readable/archive validation;
- notification `collection update available`;
- manual refresh fallback;
- no update storm on mass file changes.

## Етап 13. Collection Cleaner / Repair
**Мета:** безпечне обслуговування великих колекцій.

Роботи:
- consistency analyzer;
- missing/orphaned files;
- invalid archive/index references;
- duplicates where deterministically identifiable;
- dry-run report;
- explicit apply step;
- recreate/repair action with backup.

## Етап 14. ActionRegistry + configurable hotkeys
**Мета:** прибрати shortcut logic із FXML/controllers.

Роботи:
- command registry;
- default/current shortcuts;
- conflict validation;
- visibility/context;
- customization dialog;
- persistence;
- migrate основні menu actions.

## Етап 15. User scripts / book actions
**Мета:** FLibrary-style customizable actions.

Роботи:
- named script profiles;
- ordered commands;
- executable + args + working directory;
- safe placeholders;
- context menu binding;
- migrate existing post-command as default profile;
- test-command preview without destructive execution.

## Етап 16. Export/device profiles
**Мета:** довести до зрілого workflow MyHomeLib + FLibrary.

Роботи:
- named conversion/export profiles;
- collision policy: overwrite/skip/auto-rename/ask;
- batch progress/cancel;
- profile-specific filename/subfolder templates;
- export history/status.

## Етап 17. OPDS core
**Мета:** дати локальний каталог іншим reader-ам.

Роботи:
- новий `myhomelib-opds` module;
- read-only application/query ports;
- OPDS root/authors/series/genres/search/book/download;
- localhost bind by default;
- tests без JavaFX.

## Етап 18. OPDS lifecycle UI
**Мета:** керування sidecar/server з desktop UI.

Роботи:
- start/stop/status;
- port/bind settings;
- optional basic auth;
- optional autostart;
- health endpoint/status;
- clear indication when exposed beyond localhost.

## Етап 19. Reader settings UX — AlReader-like
**Мета:** повноцінне меню без переписування render engine.

Роботи:
- categories: typography/colors/layout/navigation/status;
- presets;
- per-book override vs global default;
- tap zones/actions;
- status bar options;
- live preview + reset per section.

## Етап 20. Reader engine quality
**Мета:** якість тексту та стабільність.

Роботи:
- language-aware hyphenation dictionaries;
- crash-safe periodic position persistence;
- EPUB navigation/NCX refinements;
- selection/copy refinements;
- text-layout regression fixtures;
- performance checks on very large FB2/EPUB.

## Етап 21. Context help + genre localization
**Мета:** взяти сильну сторону MyHomeLib і поєднати з вашими `Lang/*.json`.

Роботи:
- help registry by workspace/dialog;
- F1 contextual open;
- Markdown/HTML help bundle;
- genre display names per language by stable FB2 genre code;
- language catalog schema/version/missing-key diagnostics;
- без mandatory signing.

## Етап 22. Versioned user-data backup/restore
**Мета:** безпечна еволюція БД і перенесення профілю.

Роботи:
- explicit backup schema version;
- groups/favorites/saved searches/filters/history/rating/progress/reviews/bookmarks/reader settings;
- sequential restore migrations;
- compatibility tests з попереднім export format;
- за можливості import legacy MyHomeLib user data by LibID.

## Етап 23. Cross-platform CI/release
**Мета:** відтворювані Windows/Linux/macOS збірки.

Роботи:
- GitHub Actions matrix;
- `mvn verify`;
- `jpackage`/portable artifacts;
- checksums;
- smoke launch tests;
- package docs;
- no network assumptions at runtime.

## Етап 24. Performance baseline
**Мета:** оптимізувати лише те, що виміряно.

Роботи:
- benchmark startup/search/navigation/import;
- 100k/500k/1M synthetic or sanitized catalog;
- huge FB2/EPUB reader benchmarks;
- heap/GC observations;
- thresholds and regression guardrails.

## Етап 25. Targeted refactor after tests
**Мета:** розбити найбільші класи без зміни поведінки.

Роботи в одному етапі тільки для одного піднапряму. Рекомендовано три окремі запити:
- 25A: `MainController` / table/navigation UI orchestration;
- 25B: `ReaderCanvas` / `TextLayoutEngine` / `Fb2StreamingParser`;
- 25C: `LuceneSearchService` / `FolderSyncService`.

# 8. Пріоритет

Рекомендована послідовність без перестрибування: **1 -> 2 -> 3 -> 4 -> 5 -> 6 -> 7 -> 8 -> 9 -> 10 -> 12 -> 13 -> 14 -> 15 -> 16 -> 17 -> 18 -> 19 -> 20 -> 21 -> 22 -> 23 -> 24 -> 25**.

Етап 11 (extra formats) можна виконати після 10 або відкласти після OPDS. Найбільший користувацький ефект дадуть етапи 2–10; найбільше зниження ризику — 1, 6, 12, 13, 22, 23, 24.

# 9. Стандартний текст наступного запиту

Для кожного етапу достатньо написати:

> Виконай етап N з узгодженого roadmap. Візьми за основу останній повний архів проєкту. Виконай тільки цей етап, не переходь до наступного. Збережи сумісність з уже внесеними змінами. Додай/онови тести та документацію. Проведи всі доступні validation checks. Наприкінці надай повний ZIP проєкту, STAGE-N-CHANGELOG.md і STAGE-N-VALIDATION.md.

Це дозволяє вести розвиток послідовними атомарними релізами, де кожен наступний архів стає базою для наступного запиту.
