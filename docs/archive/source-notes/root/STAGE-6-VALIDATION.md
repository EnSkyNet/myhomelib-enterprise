# Stage 6 Validation — Online update model: data layer

Дата: 24 серпня 2026

## Підсумок

Stage 6 пройшов усі доступні в цьому середовищі offline/static/data-layer checks. SQLite migration chain та Stage 6 behavior regression — PASS. Targeted `javac --release 21` compile для ключових нових Stage 6 source units — PASS. Повний Maven/JUnit/ArchUnit run не зміг стартувати, тому що Maven Wrapper distribution 3.9.16 відсутній локально, а середовище не може resolve `repo.maven.apache.org`.

## Виконані автоматичні перевірки

### Stage regression checks

```text
python3 tools/stage3-navigation-check.py
STAGE 3 NAVIGATION CHECK: PASS

python3 tools/stage4-navigation-check.py
STAGE 4 NAVIGATION CHECK: PASS

python3 tools/stage5-history-check.py
STAGE 5 HISTORY CHECK: PASS

python3 tools/stage6-online-update-check.py
STAGE 6 ONLINE UPDATE CHECK: PASS
 - stable remote source identity independent of temp INPX path: PASS
 - initial sync establishes baseline without false updates: PASS
 - repeated identical sync produces zero new events: PASS
 - changed downloaded book produces exactly one pending update: PASS
 - local storage/rating/progress/review/bookmarks survive remote UPSERT: PASS
 - new book by explicitly followed author is detected after baseline: PASS
 - successful download establishes baseline and acknowledges pending book events: PASS
```

Stage 6 offline check застосовує повний Flyway SQL chain через stdlib SQLite та відтворює ключові Stage 6 classification/UPSERT rules на реальних таблицях.

### Architecture guard

```text
python3 tools/architecture-check.py
PASS: architecture baseline is intact
INFO: UI debt ratchet: output-port users 18/18 baseline; non-value domain-model users 28/28 baseline
```

Stage 6 guardrails перевіряють application/persistence boundary, V31 markers, stable remote source wiring, protected local-storage UPSERT та download-baseline hook.

### Language catalogues

Stage 6 не додає UI text, але існуючі каталоги повторно перевірено:

```text
python3 tools/validate-language-catalogs.py
Language catalogue validation OK
 - bg: bg.json, 158 keys
 - en: en.json, 158 keys
 - uk: uk.json, 158 keys
```

### Offline release check

```text
python3 tools/static_release_check.py
XML (POM + FXML): 36; errors: 0
FXML workspaces: 24; handler references: 138; missing: 0
SQLite migrations: 31; errors: 0; integrity: ok
Root shell scripts: 7; static issues: 0
Java sources: 516; test sources: 34
OFFLINE STATIC RELEASE CHECK: PASS
```

Цей check застосував усі 31 migration до clean SQLite DB і отримав `PRAGMA integrity_check = ok`.

### Targeted Java compile

Через відсутність Maven distribution ключові фактичні Stage 6 sources були додатково скомпільовані `javac 21.0.11` із мінімальними compile-time stubs тільки для зовнішніх Spring/JDBC/Lombok boundaries:

```text
application_catalog_targeted_compile=PASS
sqlite_catalog_adapter_targeted_compile=PASS
inpx_pipeline_targeted_compile=PASS
catalog_update_service_targeted_compile=PASS
```

Окремо actual SQL form `INSERT ... SELECT ... ON CONFLICT` із SQLite adapter перевірено SQLite runtime:

```text
actual_adapter_sql_syntax=PASS
```

Java `UUID.nameUUIDFromBytes` і offline regression implementation stable source ID також були звірені на однаковому source key.

## Maven/JUnit limitation

Спроба:

```text
./mvnw -o -q -pl myhomelib-application,myhomelib-infrastructure,myhomelib-architecture-tests -am test
```

не дійшла до Maven test lifecycle. Wrapper спочатку потребує локально відсутній Maven 3.9.16 і намагається завантажити distribution, але DNS/network access до Maven Central у validation environment недоступний:

```text
Downloading Maven 3.9.16...
curl: (6) Could not resolve host: repo.maven.apache.org
```

Це infrastructure limitation середовища, а не JUnit failure. На машині з доступним Maven wrapper distribution/dependency cache потрібно виконати повний:

```text
./mvnw clean verify
```

## Regression semantics, які перевірено

1. Перший sync створює baseline і не генерує false-positive updates.
2. Повторний import exact same INPX fingerprint не збільшує source revision і не створює events.
3. Зміна source INPX збільшує source revision; незмінена конкретна книга не повинна автоматично стати updated лише через source-level change.
4. Зміна catalog fingerprint downloaded book породжує один `UPDATED_DOWNLOADED_BOOK`; повторний sync тієї самої revision не дублює його.
5. Успішний download фіксує current catalog baseline і acknowledge-ить pending events книги.
6. Нова книга followed author породжує `NEW_BY_FOLLOWED_AUTHOR` тільки після initial source baseline.
7. Remote UPSERT не перезаписує installed `file_name/folder/archive_entry/file_size/collection_root`, `local`, rating, progress або review.
8. Bookmarks переживають remote UPSERT без змін.
9. Книга, яка зникла з нового catalog, може стати `deleted`, але downloaded local state не знищується.
10. Remote source key стабільний відносно random cache filename; persisted diagnostic URL не зберігає credentials/query/fragment.

## Рекомендований manual/integration smoke test

1. На копії реальної online collection DB запустити application і дозволити Flyway застосувати V31.
2. Виконати network update тим самим INPX двічі; перевірити, що `catalog_sources.source_revision` після другого identical sync не змінився, а pending updates не з'явились.
3. Завантажити книгу локально, перевірити `catalog_book_state.downloaded_revision/downloaded_fingerprint`.
4. Зберегти для книги rating/progress/review/bookmark і переконатися, що local storage points на downloaded bytes.
5. Оновити INPX metadata/file record цієї книги й виконати sync; має бути рівно один pending `UPDATED_DOWNLOADED_BOOK`, а local/user state має лишитися незмінним.
6. Повторити той самий changed INPX; event не повинен дублюватися.
7. У data/application layer позначити автора followed, виконати baseline, потім додати нову його книгу в наступну revision; очікується один `NEW_BY_FOLLOWED_AUTHOR`.
8. Завантажити цю нову книгу; downloaded baseline має бути встановлено, pending event книги — acknowledged.
9. Видалити local copy через existing action; downloaded baseline має очиститися, catalog row/user data — залишитися.
10. Перевірити source URL із credentials/query token: `catalog_sources.source_location` не повинен містити credentials, query або fragment.

## Відомі межі Stage 6

- Stage 6 не додає `Updates` navigation/tree, badge/counter UI, empty state або open-from-update actions — це Stage 7.
- Followed author тепер має явний data/application contract, але Stage 6 не додає окремий UI для follow/unfollow.
- Для legacy remote INPX записів без `LIBID` попередній Stage 5 temp-path fallback сам по собі не був стабільним; Stage 6 робить майбутні remote identities deterministic. Реальні upgrade-набори без `LIBID` варто окремо перевірити перед масовим production sync.
- Offline/static checks не замінюють повний Maven/JUnit/JavaFX runtime run.

## Scope check

- Stage 6 data layer: виконано.
- Stage 7 Updates UI: не розпочинався.

## Post-delivery Windows compile feedback / hotfix

На реальному Windows Maven build main sources модуля `myhomelib-infrastructure` успішно скомпілювали 127 Java source files, після чого `testCompile` виявив одну compile-time помилку в `JdbcBatchWriterStage6Test.java:43`: `List.of(row)` для `Object[] row` було виведено як `List<Object>`, тоді як `batchInsertFull` очікує `List<Object[]>`.

Виправлено на:

```java
writer.batchInsertFull(List.<Object[]>of(row), new HashMap<>(), new HashMap<>());
```

Після hotfix повторно виконано доступні offline checks:

```text
STAGE 6 ONLINE UPDATE CHECK: PASS
STAGE 5 HISTORY CHECK: PASS
STAGE 4 NAVIGATION CHECK: PASS
STAGE 3 NAVIGATION CHECK: PASS
architecture baseline: PASS
language catalogues: PASS
OFFLINE STATIC RELEASE CHECK: PASS
explicit_generic_list_compile=PASS
```

У validation container повний Maven lifecycle як і раніше недоступний через відсутній локальний Maven distribution/dependency cache. На Windows-машині з Maven потрібно повторити `mvn clean verify`; саме цей rerun є остаточним підтвердженням JUnit runtime після compile hotfix.

