# MyHomeLib — наступні задачі після V34 External Test R1

**Дата handoff:** 2026-10-02  
**База:** `myhomelib-enterprise-v34-external-test-final-r1-2026-10-02`  
**Статус:** кодова база V30–V34 + R1 non-blocking Book Details fix. Цей handoff не містить нових production-змін після R1; нижче зафіксовані дефекти, знайдені під час Windows/live перевірки, та задачі на наступний прохід.

## 1. P0 — конвертація не повинна самовільно змінювати основний артефакт книги

### Спостереження
Після конвертації локальної книги з FB2 у EPUB у Book Details основним/активним форматом стає EPUB, а джерело відображається як `conversion:calibre-cli:epub`. Користувач очікує, що конвертація створює додатковий артефакт, але **не замінює primary artifact автоматично**.

### Потрібна поведінка
- Оригінальний FB2 залишається primary artifact після успішної конвертації.
- Новий EPUB додається до `Доступні формати` як secondary artifact.
- Primary змінюється **лише** після явної user action `Зробити основним`.
- Якщо primary вже був EPUB до конвертації іншого формату — не змінювати його без user action.
- Не змінювати stable book identity, reading progress, annotations або source provenance оригіналу.
- Після restart порядок/primary semantics зберігаються.

### Обов'язкові regression tests
1. FB2 primary → convert EPUB → FB2 лишився primary, EPUB secondary.
2. Explicit `Зробити основним` → EPUB стає primary.
3. Повторна конвертація в той самий формат не створює дубль artifact rows.
4. Conversion failure не змінює primary.
5. Restart/reopen зберігає primary selection.
6. Multi-artifact export/open використовує явно обраний або primary artifact за чинною policy.

## 2. P0 — автор має повторно відкриватися з правої панелі / контексту книги

### Спостереження
Після переходу з workspace автора в `Операції`, Reader або інший workspace ім'я автора продовжує відображатися у правій колонці, але повторний перехід до цього автора не спрацьовує.

### Потрібна поведінка
- Ім'я автора в Book Details/right details panel завжди є працездатною навігаційною дією, коли author id відомий.
- Клік на того самого автора повторно відкриває/фокусує author workspace незалежно від попереднього navigation state.
- Не використовувати тільки equality поточного selected-author state як причину ігнорувати navigation action.
- Після `Операції → книга → автор`, `Reader → книга → автор`, Dashboard/Search → author навігація має працювати однаково.
- Back/forward history не повинна ламатися або накопичувати некоректний duplicate state.

### Обов'язкові regression tests
- same author clicked twice із проміжним переходом в Operations;
- same author clicked після Reader;
- author link із Search/Book Details;
- author deleted/missing → fail-safe message без exception;
- navigation history / workspace lifecycle.

## 3. P0 — незавантажена книга: коректний стан замість технічної помилки inspection

### Спостереження
Для remote-only книги без локального файлу Book Details може показувати повідомлення на кшталт `Не вдалося отримати розширені дані з файлу` / `Файл недоступний для аналізу`. Це очікуваний стан книги, а не технічна помилка.

### Потрібна поведінка
- Якщо в книги немає доступного local artifact, показувати чітко: **`Книгу не завантажено`**.
- Додатково показати доступну user action `Завантажити`, якщо remote source підтримує download.
- Не запускати parser/inspection для відсутнього local artifact.
- Не писати WARN/ERROR для штатного `remote-only` стану.
- Технічне повідомлення `не вдалося прочитати/проаналізувати файл` залишити тільки для випадку, коли local artifact заявлений, але файл пошкоджений/недоступний.

### Обов'язкові regression tests
- remote-only book;
- local missing file;
- corrupt local file;
- valid local file;
- download completion → details автоматично переходять зі стану `не завантажено` у local details без повторного download.

## 4. P1 — повторна перевірка R1: passive Book Details не блокує програму

R1 уже змінює passive Book Details analysis так, щоб він не брав global library lease. Це треба підтвердити на Windows/live сценарії.

### Acceptance
- Один раз завантажити велику FB2 книгу.
- Вибрати її кілька разів, перейти на іншу книгу й назад.
- Не повинно бути повторного HTTP download.
- Passive local FB2 parsing може виконуватись у фоні, але не блокує:
  - Operations Center;
  - Statistics;
  - Reader;
  - Conversion;
  - navigation;
  - download іншої книги, якщо operation policy це дозволяє.
- Status bar не повинен повторно показувати старе `Завантажено: <книга>` як ознаку нової операції.
- Якщо якась операція реально блокує library-wide actions, вона має бути видима в Operation Center або мати явний UX reason.

## 5. P1 — оптимізація використання пам'яті без погіршення великих імпортів

Не зменшувати RAM навмання тільки через `-Xmx`. Спочатку same-environment baseline.

### Baseline
Заміряти на одній машині та одній БД:
1. cold startup → Dashboard;
2. 60 s idle після GC stabilization;
3. відкриття автора з 500+ книгами;
4. Book Details великого FB2;
5. Search;
6. Reader;
7. full/large INPX import;
8. post-import idle;
9. Lucene rebuild/reopen.

Фіксувати мінімум: process working set/RSS, JVM heap used/committed, GC pauses, native memory де можливо, elapsed time.

### Кандидати для A/B
- Hikari pool: перевірити `maxPoolSize 4 → 2` для normal interactive workload; bulk import може мати окремий profile тільки за evidence.
- SQLite per-connection cache: A/B `16 MiB → 8 MiB`.
- SQLite mmap: A/B `64 MiB → 32 MiB`.
- `BookCache`: A/B `64 MiB → 32 MiB`.
- `CoverCache`: A/B `64 MiB → 32 MiB`.
- author/series/genre/search cache sizes і TTL — зменшувати тільки за hit-rate/latency evidence.
- перевірити lazy/open-on-demand для resources, які не потрібні до першого пошуку/reader action.
- не знижувати `Xmx=1024m`, доки A/B не покаже виграш без import/GC regression.

### Acceptance
- цільовий idle RSS: прагнути приблизно до 280–330 MiB, але **не приймати це як жорсткий gate** без same-machine baseline;
- startup/search/reader latency не погіршується відчутно;
- 707k-class INPX import не отримує GC thrash/OOM;
- після закриття Reader/великих details temporary buffers не утримуються без потреби.

## 6. P1 — operation model / статус-бар

Після знайденого DOWNLOAD/QUERY блокування провести окремий consistency audit:
- passive `QUERY`/inspection не має бути library-exclusive;
- blocking operations мають бути видимі у Operation Center;
- completed status не повинен виглядати як активна нова операція;
- old completed message не має повертатися після фонової details task;
- exception `LibraryOperationConflictException` не повинен вилітати через JavaFX event handler як uncaught runtime exception;
- UX має пояснювати реальний conflict, якщо він справді необхідний.

## 7. P2 — live/platform acceptance, що залишився

Після P0/P1 regression fixes продовжити:
- V30 physical Kobo: first sync, library/open/read, progress обома напрямками, metadata/cover, reconnect, revoke/rotate.
- V31 Windows updater: installer/portable/upgrade, process/file locks, rollback, DPAPI/DPI, signing/distribution.
- V32 Readwise: real account auth, dedup/update, rate-limit, timeout/partial failure, idempotent retry.
- V29 PWA: unrestricted browser install/offline/reconnect/auth revocation.
- real large CBR/CB7 corpus.
- 1–2 h production soak на великій бібліотеці.

## 8. Порядок наступного проходу

1. RED tests для primary artifact semantics при conversion.
2. Fix conversion artifact promotion.
3. RED tests для repeated author navigation.
4. Fix author navigation state/lifecycle.
5. RED tests для remote-only Book Details state.
6. Fix user-facing message + skip parser for no-local-file.
7. Windows regression R1 для repeated selection/background analysis.
8. Operation coordinator/status-bar audit.
9. Memory baseline → A/B оптимізації → залишити тільки виміряні покращення.
10. Full targeted suites + UI action inventory/behavior + localization/help.
11. Full offline `clean verify`.
12. Clean source ZIP + SHA-256.
13. Повторити зовнішній Windows/live acceptance на новому candidate.

## 9. Не робити в цьому проході

- не відкривати V35 product scope;
- не міняти primary artifact policy за title/path heuristics;
- не створювати новий generic operation framework без доказу, що чинний coordinator не можна виправити локально;
- не зменшувати JVM heap/SQLite/cache limits без A/B;
- не маскувати blocking defects retries/sleeps;
- не видавати Linux fake/local tests за Windows/Kobo/Readwise live acceptance.

## 10. Definition of Done наступного candidate

- Conversion додає формат і не змінює primary без явної user action.
- Повторний перехід до автора завжди працює з усіх основних workspace.
- Remote-only книга явно показує `Книгу не завантажено`; parser не запускається.
- R1 non-blocking selection підтверджений на Windows.
- Blocking operation завжди має зрозумілий статус/відображення; passive details не блокує бібліотеку.
- Memory changes мають same-environment A/B evidence; немає import/search/reader regression.
- Full Maven `clean verify` green.
- UI action inventory + action-specific behavior + UK/EN/BG help/localization green.
- Clean ZIP + SHA-256 + updated history/current validation.
