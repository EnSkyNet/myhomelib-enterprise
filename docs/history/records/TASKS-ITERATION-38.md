# MyHomeLib — завдання на 10.09.2026

**Iteration 38**  
**База:** MyHomeLib 7.3 WIP / Iteration 37  
**Основний принцип роботи:** спочатку повністю вносяться всі заплановані зміни в source/docs/tests, далі виконується ручна ревізія, і лише після цього запускаються compile / acceptance / regression / release gates. Проміжні тестові запуски під час реалізації не виконуються.

## 1. MHL-205 — Export annotations

### Мета
Додати повноцінний експорт highlights/notes поза MyHomeLib без зміни persistence-моделі annotations.

### Реалізація
- Markdown exporter.
- JSON exporter.
- HTML exporter.
- Експорт для:
  - однієї вибраної книги;
  - кількох вибраних книг;
  - усієї бібліотеки.
- Вихідні дані повинні включати:
  - metadata книги;
  - chapter/section;
  - quote;
  - note;
  - tags;
  - color/type;
  - created/updated timestamp;
  - stable annotation identity.
- Configurable templates для Markdown/HTML.
- UTF-8 без втрати Unicode.
- Детермінований порядок записів і стабільний повторний export.
- Потоковий/bounded export без матеріалізації всіх annotations у RAM.
- UI action у Annotation Manager через application layer, без UI → SQLite/domain обходу.

### Acceptance criteria
- Markdown валідний і детермінований.
- JSON синтаксично валідний та має versioned schema marker.
- HTML валідний, UTF-8 і self-contained у межах погодженого scope.
- Selected / selected-books / all-books повертають правильний набір annotations.
- Повторний export однакових даних дає однаковий результат.
- Unicode/emoji/Cyrillic/HTML-special characters не пошкоджуються.
- Cancel/error не залишає частково опублікований фінальний файл.

---

## 2. MHL-206 — PDF Reader v1

### Мета
Додати повноцінне внутрішнє читання PDF як окремий Reader renderer adapter.

### Реалізація
- PDF renderer adapter у `myhomelib-reader`.
- Відкриття PDF через існуючий Reader workflow.
- Page navigation.
- Zoom in/out + reset.
- Fit Width.
- Fit Page.
- Continuous scrolling.
- Thumbnails/sidebar.
- Збереження та відновлення останньої сторінки/позиції.
- Lazy page rendering для великих PDF.
- Bounded image/page cache.
- Cancellation при switch/close книги.
- Жодного важкого PDF parsing/rendering на JavaFX thread.
- Graceful error для malformed/encrypted/unsupported PDF.

### Acceptance criteria
- Типові PDF відкриваються у внутрішньому Reader.
- Перехід між сторінками правильний.
- Fit Width / Fit Page працюють стабільно.
- Zoom не блокує UI.
- Позиція відновлюється після reopen.
- Великий PDF не рендериться повністю наперед.
- Швидке закриття/switch не дозволяє stale background result змінити поточний Reader.
- Existing FB2/EPUB Reader regressions не ламаються.

---

## 3. MHL-207 — PDF TOC / search / bookmarks / annotations — умовний scope

**Починати лише після повного закриття MHL-205 і MHL-206 без acceptance debt.**

### Мінімальний scope, якщо залишиться час
- PDF Outline/TOC.
- Перехід по TOC.
- Text search по PDF text layer.
- Search results: page + snippet.
- Jump до search result.
- Page bookmarks.
- Підготовка application contract для PDF annotations, якщо text layer доступний.
- Scanned/image-only PDF має деградувати коректно: Reader працює, але text search/selection недоступні без вигаданого OCR.

### Не робити в цій ітерації, якщо MHL-205/206 не закриті
- не розширювати MHL-207 за рахунок незавершених acceptance;
- не додавати OCR як прихований scope;
- не створювати паралельний annotation persistence;
- не обходити existing Reader/application architecture.

---

## 4. Ручна ревізія перед тестами

Після завершення всіх source/doc/test changes:
- перевірити UI → application boundary;
- перевірити відсутність Reader/UI → SQLite/JDBC залежностей;
- перевірити lifecycle/cancellation PDF background tasks;
- перевірити bounded memory/cache/export paging;
- перевірити UTF-8 і escaping exporters;
- перевірити localization keys UK/EN/BG;
- оновити checkpoint та changed-file inventory;
- заморозити дерево перед тестовим циклом.

---

## 5. Фінальний тестовий цикл — тільки після завершення всіх змін

Порядок:
1. Offline reactor `test-compile`.
2. Targeted MHL-205 tests.
3. Targeted MHL-206 tests.
4. MHL-207 tests — тільки якщо задача фактично реалізована.
5. ArchUnit / architecture gates.
6. Localization / UI reachability / executor/static gates.
7. Reader/UI/application regression.
8. Infrastructure regression модульними групами, якщо монолітний suite перевищує execution limit.
9. OPDS / bootstrap / MCP / E2E tail.
10. Release-tree cleanliness.
11. Source ZIP integrity + SHA-256.

## Очікуваний результат дня

**Обов'язково:**
- MHL-205 — DONE.
- MHL-206 — DONE або чітко зафіксований технічний blocker без неправдивого статусу DONE.

**Додатково:**
- MHL-207 — тільки якщо MHL-205 і MHL-206 повністю зелені та не залишили acceptance debt.

