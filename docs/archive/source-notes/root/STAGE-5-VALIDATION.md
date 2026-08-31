# Stage 5 Validation — Recent / AlreadyRead / History + navigation history

Дата: 24 серпня 2026

## Підсумок

Stage 5 пройшов доступні в цьому середовищі offline/static checks. Повний Maven/JUnit/ArchUnit run не був можливий, тому що Maven Wrapper distribution відсутній локально, а середовище не може resolve `repo.maven.apache.org`.

## Виконані автоматичні перевірки

### Stage regression checks

```text
python3 tools/stage3-navigation-check.py
STAGE 3 NAVIGATION CHECK: PASS

python3 tools/stage4-navigation-check.py
STAGE 4 NAVIGATION CHECK: PASS

python3 tools/stage5-history-check.py
STAGE 5 HISTORY CHECK: PASS
 - V30 backfill from legacy reading state: PASS
 - AlreadyRead uses progress=100 and excludes deleted books: PASS
 - History ordering / deleted filtering / repeat-open UPSERT: PASS
 - clear history preserves reading progress and AlreadyRead state: PASS
```

### Architecture guard

```text
python3 tools/architecture-check.py
PASS: architecture baseline is intact
UI debt ratchet: output-port users 18/18 baseline; non-value domain-model users 28/28 baseline
```

Stage 5 guardrails перевіряють наявність `ALREADY_READ`/`HISTORY`, dedicated `reading_history`, `onlyInHistory`, history ordering, workspace restore, Recent/Clear UI wiring та record-on-success у Reader.

### Language catalogues

```text
python3 tools/validate-language-catalogs.py
Language catalogue validation OK
 - bg: 158 keys
 - en: 158 keys
 - uk: 158 keys
```

### Offline release check

```text
python3 tools/static_release_check.py
XML (POM + FXML): 36; errors: 0
FXML workspaces: 24; handler references: 138; missing: 0
SQLite migrations: 30; errors: 0; integrity: ok
Root shell scripts: 7; static issues: 0
Java sources: 504; test sources: 30
OFFLINE STATIC RELEASE CHECK: PASS
```

### Targeted Java compile

`SqliteReadingHistoryAdapter` було скомпільовано `javac --release 21` з мінімальними compile-time stubs для зовнішніх Spring/JDBC boundaries після останньої timestamp-правки:

```text
adapter_targeted_compile=PASS
javac 21.0.11
```

Раніше в межах цього Stage 5 також окремо пройшли targeted compile checks для `BookQuery`/`BookQueryBuilder`, `ReadingHistoryPort`/`ReadingHistoryService` та `DefaultNavigationQueryService` з їх фактичними Stage 5 source files і мінімальними dependency stubs.

## Maven/JUnit limitation

Команда:

```text
./mvnw -o -q -pl myhomelib-application,myhomelib-infrastructure,myhomelib-ui,myhomelib-architecture-tests -am test
```

не змогла стартувати Maven, тому що wrapper спробував завантажити Maven 3.9.16, а DNS/network access до Maven Central у середовищі недоступний:

```text
Downloading Maven 3.9.16...
curl: (6) Could not resolve host: repo.maven.apache.org
```

Це infrastructure limitation validation environment, а не test failure. На машині з уже встановленим Maven/wrapper distribution та dependencies потрібно виконати повний `./mvnw clean verify`.

## Рекомендований manual smoke test

1. Відкрити mode selector і перевірити наявність `Прочитані` та `Історія читання` поруч з існуючими режимами.
2. Перевірити, що `Прочитані` показує лише active books з progress `100%`.
3. У Reader послідовно відкрити кілька книг; відкрити `Недавні книги` та перевірити timestamp і порядок newest-first.
4. Повторно відкрити старішу книгу; вона має перейти на початок Recent без дубліката.
5. Відкрити History workspace та перевірити newest-first ordering і стандартну пагінацію table workspace.
6. Виконати `Очистити історію читання`: Recent і History мають спорожніти, але reading progress/bookmarks та `Прочитані` мають залишитися без змін.
7. Перейти між Authors/Keywords/Groups/Reviews/AlreadyRead/History та перевірити Back/Forward.
8. Перевірити `Alt+Left` / `Alt+Right`.
9. Швидко перемикати navigation modes і переконатися, що stale async result не перезаписує актуальний workspace.
10. Відкрити стару collection DB, дозволити Flyway застосувати V30 і перевірити backfill Recent/History з існуючого reading state.

## Scope check

- Stage 5: виконано.
- Stage 6 online update data model: не розпочинався.
