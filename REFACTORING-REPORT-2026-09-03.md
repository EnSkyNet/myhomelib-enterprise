# MyHomeLib Enterprise — refactoring & hardening report

Дата: 2026-09-03  
Базова версія: 7.1.0  
Базовий commit робочої копії: `d789bb7`  
Фінальний source-refactoring commit перед цим звітом: `f500d60`

## 1. Що зроблено

Проведено послідовний refactoring/hardening без повного rewrite. Основні зміни розбиті на окремі перевірені commits.

### Дані, SQLite та великі каталоги

- Unicode-нормалізація пошуку авторів, включно з кирилицею.
- Keywords переведені з runtime `WITH RECURSIVE` parsing на нормалізовані `keywords` + `keyword_books` з backfill/indexes.
- Pageable/search book rows переведені на lightweight projection; повна модель завантажується лише там, де вона реально потрібна.
- Author navigation переведена на keyset pagination.
- Main catalog TITLE navigation переведена на двонаправлену keyset pagination.
- Author/Group/catalog continuation повторно використовує відомий total і не виконує `COUNT(*)` на кожній сторінці.
- Додано індекс для normalized-language facet/filter (`V47`).
- Додано вузький partial covering index активних книг для exact count (`V48`).
- Persistence errors більше не маскуються як `0`, `List.of()`, `Optional.empty()` у критичних DB/Lucene paths.

### Local import та проблеми, відтворені на реальних книгах

- FB2 cover parser більше не бере останню картинку книги замість cover; declared `<coverpage>` має пріоритет, fallback — перше валідне зображення.
- Виправлено Book Details/Inspection для FB2 з named main body (`<body name="...">`): довільний named body більше не вважається footnotes body.
- Local author normalization об'єднує типові перестановки `first-name/last-name` і розділяє типові concatenated creator strings на окремих авторів.
- Directory rescan оновлює існуючі метадані (`updateExisting=true`) зі збереженням user state.
- ZIP archive entry handling більше не залежить від filesystem locale; підтримано Windows-1251/CP866 legacy names.
- Пошкоджений archive/resource більше не маскується як нормальна відсутність entry.

### Reader

- Додана структурована semantic style model: title/chapter/section/subtitle/epigraph/quote/poem/poem-author/text-author/annotation/link/footnote/strong/emphasis/code.
- Для semantic styles підтримані font/size/weight/color/alignment/spacing before/after.
- Reader settings/preferences зберігають semantic styles структуровано; `customCss` не є основним API.
- FB2 semantic parsing розширений для chapter/section title, subtitle, epigraph, cite/quote, verse/poem, authors, annotation, footnotes, strong/emphasis.
- Large image resources залишаються bounded: великі ресурси переходять у temp storage, а failure temp-storage не перетворюється на unbounded heap fallback.
- EPUB metadata parsing та Reader EPUB parsing уніфіковані й hardened.

### Async/UI/lifecycle

- Search, Author, Book Details, Reader, Dashboard, Statistics та online-update UI захищені collection-scoped request token (`requestId + collectionId`).
- Collection runtime state (`CREATING/READY/IMPORTING/INDEXING/UPDATING/ERROR/DELETING`) проектується з Operation Center lifecycle замість незалежних flags.
- Integrity UX розширено: Books/Authors/Genres/Series/Relations/SQLite/Lucene/problem count/export report/safe repair flow.
- Search query construction винесено з великого controller у immutable query snapshot/helper.

### Online update / consistency

- Перед online catalog update створюється SQLite checkpoint.
- При failure/cancellation після commit відновлюються SQLite, Lucene та statistics.
- Failed rollback зберігає recovery checkpoint.
- UI error model містить stage/source/last successful version/local state/rollback result/safe Retry.
- Backup/restore використовує єдиний CollectionDatabasePathResolver.

### Parser/security/hardening

- XML/StAX creation централізоване через fail-closed `SecureXmlInputFactory`; DTD та external entities вимкнені.
- INPX importer переведений на єдиний streaming `InpxReader`, включно зі standalone `.inp`.
- Додані archive-bomb/entry-size/compression-ratio guards для ZIP/EPUB/INPX.
- EPUB mixed-content metadata читаються bounded reader-ом.
- TXT importer відрізняє expected charset fallback від реальної I/O помилки.
- Backup export не видає success при пошкоджених reader preferences.
- `Thread.sleep()` у JavaFX/UI: 0; production sleeps залишені лише у перевірених retry/backoff paths.

## 2. Performance baseline

Актуальний deterministic SQLite baseline збережений у `docs/performance-baseline.json`.

| Books | First page p95 | TITLE keyset after 50k p95 | OFFSET 50k p95 | Exact active COUNT p95 | Authors first keyset p95 | Languages p95 | Filtered page p95 | Import probe |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 100k | 0.123 ms | 0.126 ms | 24.230 ms | 1.036 ms | 4.671 ms | 9.738 ms | 0.179 ms | 37,842 books/s |
| 500k | 0.137 ms | 0.139 ms | 27.913 ms | 5.168 ms | 15.208 ms | 38.601 ms | 0.161 ms | 46,187 books/s |
| 1M | 0.144 ms | 0.355 ms | 32.197 ms | 10.162 ms | 32.508 ms | 76.473 ms | 0.192 ms | 41,957 books/s |

Окремий paired A/B на 1M для `V48`: exact active count приблизно `147 ms` без partial index проти `13–15 ms` з `idx_books_active_id`.

## 3. Build / acceptance status

### Maven

- `test-compile` у всіх 13 reactor projects: PASS.
- `clean verify -DskipTests`: PASS.
- `clean verify -Pproduction -DskipTests`: PASS.
- Bootstrap executable JAR та MCP shaded JAR збираються.

### JUnit runtime

Після Windows Surefire-прогонів були виправлені застарілі test contracts/fixtures, що проявилися після refactoring: rollback statistics lifecycle, normalized language SQL, UUID BookId fixture, current Flyway schema для catalog import tests, `BookListRowMapper` Spring fixture та явний Lucene query-availability lifecycle у standalone tests.

Через відсутність `surefire-junit-platform:3.2.5` у переданому Linux offline-cache ті самі JUnit Jupiter тести додатково виконані без Maven Surefire напряму через `junit-jupiter-engine`:

- application: **84/84 PASS**;
- infrastructure: **189/189 PASS** (UTF-8 locale);
- domain: **5/5 PASS**;
- reader: **35/35 PASS**;
- OPDS: **2/2 PASS**;
- MCP: **6/6 PASS**;
- architecture-tests: **12/12 PASS**.

Разом: **333 JUnit tests PASS** поза JavaFX UI runtime. UI JUnit: 21 тест проходить у Linux, 2 navigation tests потребують реального JavaFX toolkit і тому залишаються Windows-runtime acceptance, а не code failure.

### Quality gates

PASS:

- architecture-check
- implementation-completeness-check
- functional-regression-check
- refactoring-p0-progress-consistency-check
- large-library-pre-stage7-check
- catalog-keyset-pagination-check
- list-continuation-count-reuse-check
- async-generation-audit-check
- collection-runtime-state-check
- persistence-error-transparency-check
- thread-sleep-audit-check
- Reader Stage 19/20
- UI orchestration Stage 25A
- Reader refactor Stage 25B
- Search/sync refactor Stage 25C
- online-update-rollback-check
- XML/archive-security-check
- Stage 24 performance check
- author-search-normalization-check
- directory-import-lifecycle-check
- INPX/Lucene consistency
- reading-statistics consistency
- user-data consistency
- startup nonblocking / transaction checks
- catalog lifecycle regression
- import-index lifecycle
- V7.1 standalone Java smoke
- genre/export authority
- UI function reachability
- static release check
- release artifact presence/checksum validation

## 4. Що не можна чесно назвати runtime PASS у цьому контейнері

1. Maven Surefire `clean verify -Pproduction` із **усіма** тестами все ще треба повторити на Windows після RC3. У Linux offline-cache немає `org.apache.maven.surefire:surefire-junit-platform:3.2.5`; 333 non-UI JUnit tests перевірені напряму через JUnit Jupiter engine, але це не підміняє фінальний Windows Maven reactor run.
2. Повний JavaFX Windows runtime acceptance (реальні кліки, DPI 100/125/150/200%, installer/jpackage) неможливо коректно виконати у Linux container без Windows GUI environment. Два `WorkspaceManagerNavigationStateTest` у Linux упираються саме у відсутність JavaFX toolkit/QuantumRenderer; інші UI JUnit tests проходять.
3. Windows/macOS native installers не будувалися тут; перевірені source/release contracts та JAR artifacts.
4. Реальні 700k/1M production library files користувача не надавалися; large-catalog SQL baseline використовує deterministic synthetic fixture. Два надані користувачем ZIP/FB2 кейси були використані для targeted parser/cover/author runtime regression.

## 5. Рекомендований фінальний acceptance на Windows

У корені проєкту:

```powershell
.\mvnw.cmd clean verify -Pproduction
```

Потім перевірити вручну:

- повторне сканування локальної колекції;
- авторів `Дмитрий Дорничев / Дорничев Дмитрий` та concatenated creator case;
- обидва передані ZIP з книгами;
- Book Details: content/images/TOC;
- Reader open/close/navigation;
- Followed Authors;
- online update failure/retry;
- Import 100k/500k/700k/1M;
- Windows DPI 100/125/150/200%;
- package/release scripts.

