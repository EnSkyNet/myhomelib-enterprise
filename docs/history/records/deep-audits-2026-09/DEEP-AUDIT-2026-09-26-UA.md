# Глибокий аудит MyHomeLib Enterprise 8.0.0 — 2026-09-26

## Підсумок

Проведено повторний глибокий аудит збірки, архітектури, SQLite-транзакцій, INPX hot path, crash recovery, Lucene, Reader/Audio autosave, артефактів книг, JavaFX/UI, навігації та release-guards. Окремо проаналізовано наданий лог від 2026-09-25 і реальний `flibusta_online_fb2.inpx` на 707154 записи.

Головна вимога після аналізу логу: великий мережевий update не повинен тримати програму близько 15 хвилин. На поточному коді чистий full-snapshot імпорт того самого INPX займає 54879 мс; локальний критичний шлях без мережевого завантаження оцінено приблизно у 64,1 с. Відкладені SQLite-індекси та Lucene більше не блокують завершення користувацької UPDATE-операції.

## Виправлені функціональні дефекти

1. **INPX batch regression.** Для online/full-snapshot шляху відновлено bounded batch 5000 і явно зафіксовано `app.import.online-batch-size: 5000`.
2. **Небезпечні connection-scoped PRAGMA у Hikari pool.** `SqliteBulkImportOptimizer` більше не змінює випадкові pooled connections: bulk PRAGMA застосовуються лише до transaction-bound connection і відновлюються до його повернення в pool.
3. **Preferred artifact + legacy projection були неатомарними.** `SqliteBookCommandRepository` тепер змінює їх в одній collection transaction; додано rollback regression test.
4. **Custom fields використовували не той transaction manager.** `SqliteCustomFieldRepository` явно використовує `collectionTransactionManager`; rollback перевірено на двох окремих SQLite БД при `metadataTransactionManager @Primary`.
5. **Catalog download baseline / pending update могли розійтися.** `markDownloadedBaseline()` і `clearDownloadedBaseline()` зроблено атомарними; rollback другого SQL перевірено тестами.
6. **Створення групи залежало від `last_insert_rowid()` на потенційно іншому connection.** Переписано на один `INSERT ... RETURNING id`; видалення membership + group зроблено транзакційним.
7. **Restart-safe download queue робила двокрокові status transitions.** Переписано на один UPSERT для `IN_PROGRESS/COMPLETED/FAILED/CANCELLED`, без проміжного неправильного `PENDING` стану.
8. **ZIP Reader хибно оголошував MOBI/PRC/AZW/AZW3 як FB2-сумісні вкладення.** Неправильний dispatch прибрано і зафіксовано regression test.
9. **Reader/Audio могли втратити сигнал про незбережену останню позицію.** Final flush тепер повертає результат, retained failure повторюється, а UI може показати попередження.
10. **Відсутній generated/preferred EPUB ламав читання, хоча оригінальний FB2 існував.** Reader та content resolver перевіряють фізичну доступність і тимчасово fallback-яться на доступний оригінальний артефакт без зміни збереженого preferred; чужий full-text offset до fallback не застосовується.
11. **Сире `CancellationException.getMessage()` потрапляло в UI.** Повідомлення проходить через локалізований `UiExceptionSupport`.
12. **Подвійний navigation refresh після update.** `MainController.handleRefresh()` публікував `NavigationRefreshEvent`, який уже викликає `refreshAll()`, і відразу викликав `refreshAll()` вдруге. Прямий дубль прибрано; додано contract test.
13. **SQLite planner вибирав гірший індекс для keyset author navigation.** Перші сторінки авторів тепер явно використовують `idx_authors_navigation_page`.
14. **Великий update синхронно чекав похідні індекси.** Для incomplete change-set Lucene позначається dirty, SQLite secondary-index restore планується після UPDATE lease, а Lucene rebuild стартує після цієї maintenance. Додано regression test, який доводить, що `execute()` повертає SUCCESS до завершення deferred future.
15. **Недостатня діагностика часу update.** Додано структурований `catalog_update_timing` з `recovery/download/checkpoint/import/search/statistics/finalization` та ознакою background Lucene.
16. **Застарілі release guards.** Stage 7, 12/13, 14/15, 16, 17/18, 19/20, 22, 25A та Review4 синхронізовано з актуальними безпечними реалізаціями.

## Аналіз наданого логу від 2026-09-25

Лог показує, що звичайний повторний startup уже після сформованої FLib-колекції сам по собі не є 15-хвилинною проблемою: Spring context та готовність `MainController` відділені приблизно двома секундами. Основна затримка була в initial/full catalog update: повний INPX на 707154 записів завершував імпорт за 444579 мс, після чого виконувалися додаткові фази update/search.

Також у логу видно старі JavaFX CSS warnings, подвійні refresh navigation та сценарій із відсутнім generated EPUB. На поточному коді повний UI regression не відтворює CSS `ClassCastException/Could not resolve`, подвійний refresh прибрано, а Reader має artifact fallback.

## Поточні real-data performance probes

Усі цифри нижче отримані локально в audit sandbox і не є універсальною гарантією для іншого CPU/SSD/Windows/мережі.

### Full INPX — 707154 записи

- processed/imported: **707154 / 707154**;
- errors: **0**;
- duration: **54879 мс**;
- throughput: **12885,69 книг/с**;
- active inserted: **571947**;
- explicitly deleted: **135207**;
- authors: **166231**;
- book-author relations: **856591**;
- book-genre relations: **1102219**;
- deferred SQLite index restore: **9946 мс**;
- `PRAGMA quick_check`: **ok**, **2264 мс**.

Порівняно з п'ятничним full-import `444579 мс`, сама фаза імпорту стала приблизно **8,10× швидшою**: економія **389700 мс = 389,7 с ≈ 6 хв 29,7 с**, зменшення часу приблизно **87,66%**.

### Recovery checkpoint

Для SQLite БД **1 581 137 920 байт**:

- checkpoint: **8120 мс**;
- копія: **1 581 137 920 байт**;
- виміряна швидкість: **185,70 MiB/s**.

### Lucene

Для **571947** активних документів:

- full rebuild: **34847 мс**;
- throughput: **16428,18 docs/s**;
- DB read: **10796 мс**;
- document mapping: **5179 мс**;
- Lucene write: **18679 мс**;
- commit: **49 мс**.

Цей rebuild тепер не повинен блокувати користувацьке завершення великого update.

### Statistics і navigation

- refresh statistics: **1086 мс**;
- authors `А`: перша keyset-сторінка 500 записів — **130 мс**, друга — **34 мс**;
- `Д`: **19 / 17 мс**;
- `S`: **13 / 9 мс**;
- `#`: **41 мс**;
- backend Series facet (~61,9 тис. серій): близько **0,27 с** після одноразової series sync близько **0,51 с** на benchmark copy.

### Бюджет після головного update

Локальний blocking budget без network download та без benchmark-only `quick_check`:

- checkpoint: 8,120 с;
- full import: 54,879 с;
- statistics: 1,086 с;
- разом: **64,085 с ≈ 1 хв 04 с** + download/finalization.

Deferred work:

- SQLite index restore: 9,946 с;
- Lucene rebuild: 34,847 с;
- разом: **44,793 с**, поза UPDATE-критичним шляхом.

Навіть консервативне послідовне складання blocking + deferred дає **108,878 с ≈ 1 хв 49 с + network download**, тобто великий локальний запас до цілі **5 хв**. Реальний час на Windows потрібно підтвердити новим логом із `catalog_update_timing`, бо мережа, антивірус і швидкість диска можуть змінити результат. П'ятничний delta-артефакт не був наданий окремим файлом, тому точний current-code benchmark саме того delta не заявляється.

## Поточна перевірка коду

Після останніх змін завершено повні тести змінених модулів:

- Application: **117 suites / 433 tests / 0 failures / 0 errors / 1 skipped**;
- Infrastructure: **160 suites / 586 tests / 0 failures / 0 errors / 9 skipped**; через timeout монолітного runner останні 61 suite були визначені автоматично й завершені окремим другим запуском;
- Reader: **40 suites / 95 tests / 0 failures / 0 errors / 1 skipped**;
- UI під Xvfb: **80 suites / 177 tests / 0 failures / 0 errors / 0 skipped**.

Разом у чотирьох змінених модулях: **397 suites / 1291 tests / 0 failures / 0 errors / 11 skipped**.

Додаткові guards:

- offline static release check: **PASS**;
- 49 XML/POM/FXML — 0 parse errors;
- 261 FXML handler references — 0 missing;
- 63 SQLite migrations — PASS, integrity `ok`;
- Review4 — PASS;
- Stage 7 online updates — PASS;
- Stage 19/20 Reader — PASS;
- Stage 25A UI orchestration — PASS;
- source inventory: **1332 production Java / 492 test Java files**.

Окремий актуальний `clean verify -Pproduction` для всіх 20 reactor projects не завершився в ліміті audit runner: через 240 с він був ще на компіляції Infrastructure (11/20). До timeout не було test failure. Раніший повний 20/20 reactor run залишається історичним evidence для попереднього стану, але не видається за exact-final доказ після подальших транзакційних/performance виправлень.

## Залишкові ризики і технічний борг

- `MainController` залишається великим orchestration-класом — **687 рядків**; Stage 25A ratchet PASS, але подальша декомпозиція бажана.
- Mockito попереджає про майбутнє обмеження dynamic self-attach Java agent у наступних JDK; перед переходом на таку JDK-лінію слід налаштувати explicit test agent.
- Повна release acceptance на Windows (installer/portable, DPI, DPAPI, antivirus/SmartScreen, реальний upgrade) локальним Linux-аудитом не закривається.
- Current offline repository не містить новіших залежностей поза наданим набором; dependency upgrades треба робити окремим candidate з оновленим offline cache і повним regression run.
