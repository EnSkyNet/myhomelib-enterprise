# MyHomeLib — Iteration 32 checkpoint: Smart Collections v1

**Дата:** 08.09.2026  
**Основний backlog:** MHL-114  
**Checkpoint:** 7.2 WIP

## Що реалізовано

### MHL-114 Smart Collections v1

- Розширено наявний `SavedSearch`, окрему паралельну систему колекцій не створено.
- Типізовані правила з режимами **AND / OR**.
- Поля: title, author, series, genre, keyword, publisher, language, format, year, progress, rating, local.
- Оператори: contains, equals, not equals, at least, at most, between, is true, is false.
- Сортування: title, author, series, year, rating, progress, added; ASC/DESC.
- `pin` і bounded `maxResults` 1..100000; максимум 20 правил.
- SQLite migration **V54__smart_collections.sql** з backward-compatible defaults для старих saved searches.
- JSON persistence smart-rule definition у `SqliteSavedSearchRepository`.
- Lucene query builder + DocValues для детермінованого сортування.
- Змінено Lucene schema marker: старий індекс перебудовується замість використання несумісної схеми.
- JavaFX rule-builder dialog і відкриття smart collection у наявному search workspace.
- Backup/export читає також legacy DB без V54 як `SEARCH`, `pinned=false`.

## Додаткові виправлення, знайдені реальною JDK 21 збіркою

1. Folder Watcher використовував lifecycle annotations без правильної модульної залежності. Замість додавання забороненої architecture dependency lifecycle переведено на Spring `ContextRefreshedEvent` / `ContextClosedEvent`.
2. UI спочатку напряму використовував нові domain smart-типи. Architecture gate це відхилив; додано application DTO/use cases (`SmartCollectionDefinition`, load/build/save bridges), а UI знову не створює новий UI→domain debt.
3. Нові localization keys були помилково записані на верхньому рівні JSON. Перенесено в `translations` у всіх `uk/en/bg` root і bundled каталогах.
4. Два старі UI test fixtures мали застарілу кількість аргументів `MainBookCommandCoordinator`; fixtures синхронізовано з production constructor.
5. Legacy backup schema без V54 тепер підтримується production export, а не лише виправленням тесту.

## Верифікація фінального snapshot

- Повний **13-модульний Maven `test-compile`: BUILD SUCCESS** на OpenJDK 21.
- Canonical static suite: **76 PASS / 0 FAIL**.
- Final targeted acceptance MHL-111/113/114: **30 tests PASS / 0 FAIL / 0 ERROR / 0 SKIP**.
- Smart Collections focused tests: domain 3 + application 1 + Lucene 4 + SQLite repository 2 = **10 PASS**.
- Folder Watcher acceptance у фінальному targeted run: coordinator 1 + adapter 3 = **4 PASS**.
- Migration/backup у фінальному targeted run: migration matrix 1 + backup 9 = **10 PASS**.
- Application full modular run після architecture/lifecycle refactor: **175 tests, 0 failures, 0 errors, 1 skip**.
- Infrastructure Smart/migration/backup run: **16 tests, 0 failures, 0 errors**; окремий фінальний acceptance набір — 19 tests PASS.
- UI full modular run після refactor: **53 tests, 0 failures, 0 errors**.
- Сукупні Surefire reports у workspace: **234/234 test classes**, **682 tests, 0 failures, 0 errors, 10 environment-dependent skips**. Це консолідований набір модульних/цільових прогонів. Фінальний повторний monolithic `mvn test` із лімітом 300 с успішно пройшов shared/domain/application/infrastructure та був перерваний під час `myhomelib-reader`; тому monolithic PASS свідомо не заявляється.

Докази: `verification/iteration32/static-results.json`, `verification/iteration32/test-report-summary.json`, `verification/iteration32/logs/maven-test-compile-final.log`, `verification/iteration32/logs/iteration32-acceptance-targeted-final.log`.

## Статуси backlog після Iteration 32

- **MHL-111 — Виконано:** 10k engine smoke був PASS у Iteration 30; тепер Maven/JUnit integration і UI contract також реально запускаються на JDK 21.
- **MHL-112 — В роботі:** реалізація є, але перед DONE потрібен окремий restart/reverse-order/retention integration acceptance для shared operation journal/undo.
- **MHL-113 — Виконано:** Maven/JUnit blocker знято; coordinator + real filesystem adapter tests проходять, попередній SQLite/filesystem contract smoke збережено.
- **MHL-114 — Виконано:** функціональний scope і documented 500k target закрито; новий 500k Lucene latency benchmark не вигадується й винесений у hardening.

## Межі checkpoint

- Windows DPI/installer/portable acceptance та live GitHub acceptance лишаються зовнішніми gates 7.1 Final і не позначені PASS.
- `maven-install-plugin:3.1.1` відсутній у наданому offline repository, тому `mvn install` не є частиною acceptance; `test-compile` і `test` працюють.
- Новий 500k Lucene Smart Collection benchmark не запускався; див. `docs/smart-collections-performance.md`.
