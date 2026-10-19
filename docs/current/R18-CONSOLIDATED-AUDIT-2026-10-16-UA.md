# MyHomeLib Enterprise 8.0.0 R18 — консолідований аудит, цикл 2

**Дата:** 16 жовтня 2026 року  
**Статус:** виправлений вихідний код із частковим технічним підтвердженням; повне Maven/Windows-приймання не підтверджене.  
**Базовий архів:** `MyHomeLib-R18-8.0.0-CONSOLIDATED-READER-SAFETY-2026-10-16-SOURCE(1).zip`  
**SHA-256 базового архіву:** `58ed0309a4ffb15f440bc4bb43eaf9abf7fa48675572f53e70413e58ff85c4b7`

## 1. Межі та принципи

Цей цикл продовжено саме від прикріпленого консолідованого R18-архіву. Реальна бібліотека користувача, зокрема `D:\Flib\Flib.db` та `D:\Другие`, не відкривалася, не перейменовувалася і не змінювалася.

До фінального джерельного пакета не включалися `target`, `__pycache__`, тестові БД, паролі або журнали виконання. Тестові тимчасові каталоги після перевірок видалені.

Під час цього циклу виконано не косметичне перейменування, а кілька логічно пов'язаних виправлень у черзі індексації, атомарності SQLite та приватності користувацького UI.

## 2. Реєстр підтверджених дефектів цього циклу

| ID | Компонент | Дефект | Виправлення | Підтвердження |
|---|---|---|---|---|
| R18-10 | `ContentIndexingQueueService` | Синхронний `clear()`/freshness checkpoint міг виконуватися паралельно з уже запущеним асинхронним `save()`. Старий snapshot міг залишитися на диску після логічного очищення; аварія в цьому вікні могла відновити вже виконану роботу. | Усі записи checkpoint (`save` і `clear`) серіалізовано одним `checkpointWriteLock`. | Baseline: `clearBeforeRelease=true`, `clearWhileSave=true`; fixed: обидва `false`. Java 21 harness PASS. |
| R18-11 | `ContentIndexingQueueService` | Повторне додавання того самого `taskId` створювало другий елемент у pending queue; один логічний task міг виконатися двічі. | Ідентичне повторне додавання стало ідемпотентним; той самий ID з іншим payload відхиляється. | Baseline harness: `AssertionError: duplicate pending`; fixed harness: `QUEUE_REGRESSIONS_PASS`. |
| R18-12 | `ContentIndexingQueueService.enqueueAll` | Конфліктний duplicate `taskId` усередині batch міг виявитися вже після часткової публікації першого task. | Попередня валідація всіх ID до першої мутації черги; exact duplicates дедуплікуються. | Новий regression test + Java harness PASS. |
| R18-13 | checkpoint restore | Пошкоджений checkpoint із двома task з однаковим ID або однаковою artifact-роботою не відповідав інваріантам черги. | Restore тепер fail-closed: duplicate task ID, duplicate artifact work та чужа collection відхиляються до відновлення. | Новий JUnit regression test. |
| R18-14 | `SqliteReadingProgressRepository` | `reading_progress` та `books.progress` записувалися окремими autocommit-операціями. Помилка другої операції могла залишити дві логічні проєкції в різних станах. | Один SQLite transaction на обидва записи + `INSERT ... ON CONFLICT DO UPDATE` замість `INSERT OR REPLACE`. | Java 21 compile PASS; реальний SQLite semantic test: старий стан став `(new,77)` + `books=10`, fixed transaction повернув обидва поля до старого стану. |
| R18-15 | Reader/Sync UI | У звичайному UI залишалися технічні ідентифікатори та контрольні значення: `bookId`, `mergeId`, `deviceId`, SHA-256 fingerprint/package hash тощо. | Прибрано прямий рендер технічних ID/hash; для книги у preview використовується порядковий номер, для пристрою — лише зрозумілий статус. Технічні деталі залишаються для логування. | UI privacy contract: 4/4 PASS; direct hash/ID output: 4 → 0; direct `bookId` output: 2 → 0. |
| R18-16 | Portable snapshot conflict UI | Конфліктні значення показували SHA-256 кожного snapshot у звичайному діалозі. | SHA-256 вилучено з UI; показуються кількість полів/записів або скорочене значення. | Статичний аудит і локалізація PASS. |
| R18-17 | OPDS UI | Fingerprint сертифіката SHA-256 був безпосередньо показаний у звичайній формі. | Значення fingerprint приховано; UI показує стан сертифіката та зрозуміле попередження без технічного хешу. | Статичний privacy contract PASS. |

Попередні R18-01…R18-09 з базового консолідованого циклу збережені й повторно враховані у фінальному пакеті: Reader recovery, безпечний порядок авторів, захист SQLite/файлів, Python-інструменти та базова локалізація.

## 3. Автори та фізичні папки

Логіка безпечного ремонту старих папок не послаблювалася. Автоматичне перейменування реальної бібліотеки в цьому циклі **не виконувалося**.

Збережено принцип:

`preview → перевірка SQLite/фізичних файлів/SHA-256/колізій → резервна копія → явне підтвердження → поетапне застосування з журналом → recovery/rollback → post-audit`.

Структурований порядок ПІБ формується з підтверджених полів. Невизначені або неповні імена не повинні перетворюватися на автоматично «підтверджені» кандидати лише через збіг тексту.

Python-тести ремонту перевіряють зв'язки `books`/`book_artifacts`, багатокнижкові ZIP-контейнери, конфлікти, stale approval, rollback і блокування.

## 4. Reader: текстові та аудіопозиції

Попередні виправлення Reader збережені: перевірка актуального SQLite-якоря перед пропуском запису, інвалідація текстового кешу після аудіозапису, копіювання DTO перед мутацією, захист від `audio:*` як текстового якоря та recovery-журнал.

Додатково в цьому циклі перевірено взаємодію цих змін із чергою та SQLite-проєкцією прогресу.

**Залишкове обмеження:** текстова й аудіопозиція одного `bookId` усе ще мають спільну модель SQLite-поля `anchor_id`. Поточні виправлення запобігають неправильному пропуску/відновленню, але не створюють дві незалежні історії прогресу. Якщо вимога полягає саме в одночасному збереженні окремих текстової та аудіопозицій, потрібна окрема схема даних і міграція.

## 5. SQLite та фізичні файли

У цьому циклі виправлено конкретну неатомарність `reading_progress` + `books.progress`.

Застосовано:

- одну транзакцію на дві логічно пов'язані проєкції;
- `ON CONFLICT(book_id) DO UPDATE`;
- повторну спробу SQLite `BUSY` вже навколо транзакційного запису;
- збереження існуючого rowid для progress-запису замість `REPLACE` delete/insert semantics.

Окремо перевірено SQL-семантику через SQLite з примусовим падінням другої операції: fixed implementation не залишає перший запис закоміченим.

Це не є доказом стійкості до фізичного вимкнення живлення на конкретному NTFS/ReFS-носії.

## 6. Пам'ять, потоки, JavaFX, Lucene

У цьому циклі не було внесено ризикованого глобального збільшення/зменшення пулів потоків або Lucene RAM buffer без профілювання.

Натомість виправлено конкурентність checkpoint-публікації: тепер synchronous freshness/discard не може перехресно писати один checkpoint із застарілим async snapshot.

Повний JFR/heap-профіль на бібліотеці приблизно 596 тис. книг у цьому середовищі неможливий без реальної копії каталогу. Так само не виконано повний FX-thread profiling та Lucene benchmark на production-scale наборі.

## 7. Локалізація та UI

Перевірено обидва набори ресурсів:

- `Lang/uk.json`, `Lang/en.json`, `Lang/bg.json`;
- `myhomelib-ui/src/main/resources/lang/default/{uk,en,bg}.json`.

У кожній мові — **3 810 ключів**, ключі між дзеркальними наборами повністю збігаються.

Прибрано технічні значення з ordinary UI:

- internal book IDs;
- merge/scan IDs;
- plugin/package IDs та package hashes;
- device IDs;
- certificate SHA-256 fingerprint;
- snapshot SHA-256.

Технічна діагностика не видаляється: вона має залишатися у логах, де це потрібно для підтримки.

## 8. Фактично виконані тести

### Python

Команда:

```text
python -m unittest discover -s tools/tests -p 'test_*.py'
```

Результат:

**67/67 PASS.**

Для ізоляції тестів `TMPDIR` було перенесено в окремий тимчасовий каталог проєкту: системний `/tmp/myhomelib-author-repair-locks` у середовищі належав іншому runtime і давав `PermissionError`. Це особливість середовища виконання, а не зміна тестової логіки.

### Java 21 — ContentIndexingQueueService

Реальний змінений `ContentIndexingQueueService.java` скомпільовано `javac 21.0.12.1` разом із мінімальними compile-time stubs для зовнішніх залежностей.

Результат:

**PASS.**

Окремий harness перевірив:

- ідемпотентний duplicate task;
- conflicting batch ID;
- конкурентний checkpoint race.

Результат:

`QUEUE_REGRESSIONS_PASS`.

Додатково baseline проти fixed:

- duplicate task: baseline — `duplicate pending`; fixed — PASS;
- checkpoint race: baseline — `clearBeforeRelease=true clearWhileSave=true`; fixed — `false/false`.

### Java — SQLite repository

Модифікований `SqliteReadingProgressRepository.java` успішно пройшов ізольовану компіляцію `javac 21.0.12.1`.

Новий JUnit integration test додано до Maven-проєкту:

`SqliteReadingProgressRepositoryAtomicityTest`.

Його повний Maven-запуск у цьому середовищі неможливий, але SQL-поведінку перевірено напряму через SQLite:

- старий autocommit сценарій залишає `reading_progress` новим, а `books.progress` старим;
- fixed transaction rollback повертає обидва до попереднього стану;
- UPSERT зберігає rowid.

### Структурні перевірки

- XML/FXML parseable files: **53/53**;
- UK/EN/BG mirrored key parity: **PASS**;
- UI technical privacy contract: **4/4 PASS**;
- source archive до пакування: **3 248 файлів**;
- відмінних шляхів від базової версії: **29**.

## 9. Maven

Запитувана команда:

```text
xvfb-run -a mvn -B -ntp -Pproduction clean verify
```

**Не виконана успішно.**

Фактичний запуск завершився кодом `127` з повідомленням:

```text
mvn: Permission denied
```

Maven Wrapper `mvnw` у джерельному архіві також відсутній.

Тому не можна чесно заявити:

- `21/21` Maven modules PASS у цьому циклі;
- усі JUnit/Failsafe тести PASS;
- production JavaFX build PASS.

Цифри попереднього базового прогону `2 438 зареєстрованих / 2 415 виконаних / 23 пропущених` не повторюються як результат цього циклу.

## 10. Windows-приймання

Не виконано через відсутність Windows і реальної тестової копії бібліотеки.

Обов'язково перевірити на **копії**, а не на `D:\Flib`:

1. JDK 21 + Maven 3.9.16.
2. `mvn -B -ntp -Pproduction clean verify`.
3. Python unit-тести.
4. `PRAGMA integrity_check`.
5. Lucene initialize/search/rebuild.
6. Reader text/audio save → simulated crash → recovery.
7. NTFS файлові блокування.
8. case-insensitive collision.
9. UNC і довгі шляхи.
10. Defender/антивірус під час publish.
11. DPAPI/захищене сховище.
12. preview та пакетний repair 1–10 папок.
13. cancel/rollback/recovery.
14. реальна бібліотека масштабу близько 596 тис. книг — окремий performance run.

## 11. Залишкові ризики

### P0

1. Повний Maven build/JUnit suite цього циклу не підтверджено.
2. Реальна Windows-поведінка не підтверджена.
3. Фізична стійкість до відмови живлення під час файлового publish/recovery journal не доведена на NTFS/ReFS.
4. Текстова й аудіопозиція одного `bookId` не розділені фізично на дві незалежні SQLite-позиції.

### P1

5. Немає JFR/heap профілювання на 596 тис. книг.
6. Немає production-scale Lucene/SQLite benchmark.
7. Немає повного візуального аудиту кожного JavaFX-вікна та реального dynamic language switch.
8. Не виконано ремонт жодної реальної папки автора.

### P2

9. Частина технічних translation keys, які більше не використовуються, ще фізично присутня у ресурсах. Вони не рендеряться поточним UI; видаляти їх без повного пошуку зовнішніх consumers ризикованіше, ніж залишити.
10. Потрібен окремий Windows crash/power-loss test plan для остаточного release sign-off.

## 12. Фінальний висновок

Від базового консолідованого R18 виконано ще один повний доступний цикл із кількома пов'язаними виправленнями. Найважливіші нові зміни — **серіалізація recovery checkpoint, ідемпотентність/валідація task ID, fail-closed restore, транзакційне збереження SQLite progress та очищення технічних ID/hash із звичайного UI**.

Реальну бібліотеку не змінено.

Фінальний пакет є **консолідованим вихідним кодом, готовим до наступного Windows/Maven приймання**, але не повинен маркуватися як повністю прийнятий production-реліз до проходження наведених P0/P1 перевірок.

Доказові журнали цього циклу містяться в окремому `MyHomeLib-R18-AUDIT-EVIDENCE-2026-10-16.zip`.
