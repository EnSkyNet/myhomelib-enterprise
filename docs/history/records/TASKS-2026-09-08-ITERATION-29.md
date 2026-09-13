# Завдання на 08.09.2026 — Iteration 29

> Оновлення 08.09.2026: online batch реалізовано частково; див. `ITERATION-29-BATCH-METADATA-EDITOR-CHECKPOINT.md`. Повний scope Excel (10k+/regex/undo) та JDK 21/UI acceptance лишаються відкритими.

## Основна задача: MHL-111 — Batch metadata editor

### Мета
Додати безпечне пакетне оновлення онлайн-метаданих для кількох книг із повторним використанням уже реалізованих MetadataProvider SPI, MetadataLookupService, MetadataReviewService та MHL-110 preview/review UI.

### Що реалізувати
1. Додати вибір кількох книг для пакетної обробки метаданих.
2. Виконувати metadata lookup асинхронно, не блокуючи JavaFX UI.
3. Додати обмежену конкурентність, batch limit і backpressure для великих бібліотек.
4. Повторно використовувати provider-neutral lookup/review контракти; не створювати окрему vendor-specific логіку в UI.
5. Агрегувати пропозиції по кожній книзі та передавати їх у вже наявний MHL-110 batch review UI.
6. Додати явний progress для всієї пакетної операції та cooperative cancellation.
7. Provider errors обробляти fail-soft: збій одного провайдера/книги не повинен зупиняти весь batch; джерело і причина мають залишатися зрозумілими.
8. Застосовувати зміни лише для полів, явно вибраних користувачем для конкретної книги.
9. Реалізувати application-level transaction/batch mutation boundary для застосування підтверджених змін.
10. Не змінювати локальні user state, artifacts, preferred artifact, файли, progress/rating/review/keywords та інші локальні дані, якщо вони не є явно вибраним metadata field.

### Обов'язкові тести
- batch із 1, кількома та максимально дозволеною кількістю книг;
- partial provider failures;
- timeout / cancel під час batch;
- жодного auto-apply без explicit field selection;
- різні selected fields для різних книг;
- cancel до apply = 0 mutations;
- cancel під час lookup не залишає частково застосованих змін;
- перевірка збереження local user state / artifacts / files;
- UI responsiveness contract;
- deterministic ordering/progress reporting;
- backpressure / bounded concurrency test.

### Definition of Done
- MHL-111 цільові unit/contract/UI тести — PASS;
- affected-module reactor — BUILD SUCCESS;
- усі architecture/static/security gates — PASS;
- 0 нових TODO/FIXME, unused imports/dependencies та великих exact cross-file clones;
- checkpoint `ITERATION-29-BATCH-METADATA-EDITOR-CHECKPOINT.md` створено;
- continuation-файл для наступної backlog-задачі створено;
- Excel backlog оновлено фактичним статусом;
- сформовано новий source ZIP + SHA-256.

## Окремий regression-harness пункт

Не позначати монолітний infrastructure reactor як PASS без фактичного завершення. Раніше спостерігався pre-existing test-order hang у `LuceneClassicSearchCompatibilityTest`: сам клас окремо проходить, але інколи зависає після попередніх Lucene suite-тестів у тому самому fork.

Якщо є час після MHL-111:
- локалізувати мінімальну послідовність тестів, що відтворює hang;
- перевірити leaked executor/thread/static Lucene state/temp directory/file lock;
- виправити причину або додати надійний test isolation без приховування дефекту;
- повторити повний infrastructure regression.

## Вхідна точка

Починати з:
- `ITERATION-28-METADATA-MERGE-PREVIEW-CHECKPOINT.md`;
- `CONTINUATION-ITERATION-28-BATCH-METADATA-EDITOR.md`;
- `ITERATION-28-CHANGED-FILES.txt`.

Стан перед стартом:
- MHL-108 — DONE;
- MHL-109 — DONE;
- MHL-110 — DONE;
- наступна задача — MHL-111.
