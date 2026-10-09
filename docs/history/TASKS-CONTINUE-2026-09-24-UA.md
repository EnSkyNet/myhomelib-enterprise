# MyHomeLib — завдання для продовження роботи
## Handoff після deep-audit 2026-09-24

### 1. База, з якої продовжувати

Цей архів уже містить поточні WIP-виправлення поверх:
`MyHomeLib-8.0.0-R4-FB2-CALIBRE-RUNTIME-FIX-CHECKPOINT-2026-09-24`.

Не повертатися до старішого checkpoint без окремої причини. У поточному source уже інтегровані зміни для масового download, lifecycle INDEX/QUERY, shutdown, online catalog import/update, Lucene rebuild, JavaFX theme popup CSS та EPUB 3.4 promotion.

### 2. Масове завантаження книг — перевірити на Windows

У source вже внесено:
- весь `ensureLocal` workflow працює у `DOWNLOAD` scope замість переходу `QUERY -> DOWNLOAD`;
- `UiBackgroundExecutor.submitCollectionScoped()` при зайнятому lifecycle повертає exceptional future, а не синхронно кидає exception;
- batch не ставить сотні задач одразу в bounded queue; кількість одночасних download-lanes обмежена `online.maxParallelDownloads` (default 2).

Перевірка:
1. Вибрати 200–500 online-книг.
2. Запустити завантаження.
3. У «Операціях» не повинні залишатися вічні `Виконується` після terminal failure/cancel.
4. Queue не повинна переповнюватися.
5. Реально паралельно мають працювати лише configured lanes.
6. Переключення колекції під час активного download повинно бути або коректно заблоковано, або завершити jobs без stale callbacks.

### 3. Online library update — головний performance-напрям

Лог 2026-09-24 показав не малий incremental update, а практично bootstrap із порожньої БД:
- recovery checkpoint: `10:06:43.602`;
- full snapshot `707154` записів, `fullSnapshot=true`, `emptyCatalog=true`, `emptyAuthors=true`;
- full import: `476378 ms` (~7 хв 56 с);
- потім delta `23841` записів: `208379 ms` (~3 хв 28 с) — непропорційно повільно;
- Lucene rebuild `594822` документів: `221438 ms` (~3 хв 41 с), з них DB read ~144 с, write ~59 с;
- statistics refresh ~6.5 с;
- фінально: `594822` active/remote, `136172` deleted.

У source уже додані WIP-оптимізації:
- preflight placeholder `sync-state:unapplied` не вважається реально застосованим fingerprint/baseline;
- перший реальний sync не перескакує помилково на revision 2;
- для великих online delta (`>=10000`) дозволений preload авторів;
- для initial empty baseline не робляться зайві DELETE relation links перед insert;
- частина write-amplification indexes тимчасово знімається/rebuildиться на fast import;
- Lucene full rebuild використовує `addDocument()` після `deleteAll()` замість зайвого `updateDocument()`;
- search streaming не робить range-load custom fields/annotations, якщо таблиці фактично порожні.

Наступна перевірка має бути **на вже створеній Flib**, не на порожній колекції:
1. Запустити update ще раз без видалення DB.
2. У логах перевірити, що normal incremental update НЕ починається з `fullSnapshot=true` на 700k записів.
3. Зафіксувати окремо download/manifest, INPX delta import, Lucene, statistics.
4. Ціль для звичайного incremental update: максимум ~3 хв; не змішувати її з першим bootstrap 700k-каталогу.
5. Якщо existing DB знову отримує full snapshot — першими перевіряти `catalog_sources.fingerprint`, source revision/applied version та remote manifest selection.

### 4. EPUB 3.4 — виправлення є в source, потрібен runtime-gate

У source додано `EpubPackageVersionPromoter` і змінено `EpubOptimizer`:
- `MODERN_EPUB34` тепер може безпечно підняти вже EPUB 3.x до OPF package `version="3.4"`;
- EPUB 2 навмисно не «апгрейдиться» лише зміною version;
- після promotion виконуються structural validation та semantic equivalence check;
- output для `MODERN_EPUB34` повторно перевіряється на package version 3.4.

Обов'язково перевірити на Windows:
1. Settings: `converter.epubOptimizationProfile=MODERN_EPUB34`.
2. Settings: `converter.epubReadingSystemProfile=MODERN_EPUB34_EXPERIMENTAL`.
3. Взяти original FB2, виконати FB2 -> EPUB.
4. Source для конвертації має залишатися FB2, а не derived PDF.
5. Відкрити готовий EPUB як ZIP та перевірити OPF `package version="3.4"`.
6. Відкрити EPUB у внутрішньому Reader.
7. Якщо EPUBCheck доступний — прогнати його.
8. Перевірити cover/resources/TOC.
9. Якщо падає — потрібен **лог саме з моменту conversion**; наданий `myhomelib.log` не містив EPUB conversion stacktrace/provider execution.

### 5. Startup INDEX/QUERY race — перевірити після rebuild JAR

Наданий runtime log показав crash головного вікна:
- background `INDEX` був активний;
- `NavigationPanelController` отримав `QUERY недоступна, поки виконується INDEX`;
- `StatusBarController.initialize()` також упав на тому ж conflict;
- FXML load головного вікна завершився помилкою.

Поточний source уже містить shared INDEX lease (`acquireDetachedShared*`) і compatibility INDEX -> QUERY. Ці зміни треба перевірити саме у новозібраному Windows JAR, бо лог зроблений на попередньому runtime.

Gate:
1. Змусити Lucene freshness marker бути dirty/absent.
2. Запустити програму на великій Flib.
3. Головне вікно повинно відкритися під час background rebuild.
4. AUTHORS/status bar/reader state не повинні валити FXML startup.
5. Destructive operations (SWITCH/SYNC тощо) мають залишатися заблокованими, якщо це передбачено lifecycle.

### 6. Shutdown під час довгого Lucene rebuild

У runtime log під час shutdown task executor чекав і отримав timeout, поки Lucene rebuild продовжувався.
У source `MyHomeLibApp.stop()` тепер перед shutdown executor викликає `cancelBackgroundRebuildAndAwait()`.

Перевірити:
- закрити програму під час rebuild 500k+ документів;
- rebuild має отримати cancellation;
- executor shutdown не повинен чекати старий довгий task до timeout;
- freshness marker не повинен хибно позначати неповний index як clean.

### 7. JavaFX CSS warnings

Лог містив `ClassCastException String -> Paint/Color` у ComboBox/ListCell popup і unresolved `-mhl-on-accent`.
У source popup selection переведено на typed Modena lookups `-fx-selection-bar` / `-fx-selection-bar-text`, а theme service їх задає.

Перевірити Settings ComboBox, списки, scrollbar у light/dark theme. Нових CSS ClassCastException бути не повинно.

### 8. Regression/build state цього checkpoint

Спроба запустити targeted Maven tests у поточному середовищі була зроблена, але Maven не зміг resolve BOM через відсутній DNS/Internet:
- `org.springframework.boot:spring-boot-dependencies:4.1.1`;
- `org.openjfx:javafx:21.0.12`;
- `Unknown host repo.maven.apache.org`.

Це **не test failure коду**; тести фактично не стартували.

Коли Maven dependencies доступні, першими прогнати:
- `CollectionLifecycleServiceTest`;
- `EpubPackageVersionPromoterTest`;
- `SqliteCatalogSourceStateAdapterTest`;
- `InpxImportPipelineTest`;
- `UiBackgroundExecutorCollectionScopeTest`;
- regression для `BookDownloadCoordinator`/batch download (додати окремий test, якщо його ще немає).

Після цього:
1. high-risk application/infrastructure/UI/converter gates;
2. full reactor verify за потреби;
3. `package -DskipTests -Pproduction`;
4. Windows JAR repack із JavaFX Windows x64;
5. JAR/ZIP integrity;
6. `--release-smoke`;
7. SHA-256.

### 9. Додатковий deep audit — не втратити

Продовжити:
- lifecycle default outcome `COMPLETED` на всіх acquisition boundaries;
- stale async callbacks після SWITCH;
- DB access без stable `collectionId`;
- Reader/session/bookmark/annotation delayed tasks;
- filesystem symlink/reparse/TOCTOU;
- external process lifecycle/drains/timeouts;
- conversion/export callers, що можуть обходити cancellation/limits;
- organizer analyze/apply atomicity та rollback;
- download/update bookkeeping.

### 10. Що попросити у користувача, якщо проблема повториться

Для update >3 хв:
- `myhomelib.log` саме після **другого/наступного** update існуючої Flib;
- за можливості копію Flib DB або хоча б SQL dump таблиць `catalog_sources` / update manifest state.

Для EPUB 3.4:
- `myhomelib.log` від старту конкретної conversion до error;
- source FB2 (одну проблемну книгу), якщо її можна передати;
- готовий/частково створений EPUB, якщо він лишився;
- screenshot Settings -> Converters з optimization profile + reading-system profile.

Для batch download:
- лог з моменту запуску 200+ книг;
- screenshot Operation Center після завершення/помилки.
