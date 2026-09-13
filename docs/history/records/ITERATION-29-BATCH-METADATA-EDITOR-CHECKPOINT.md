# Iteration 29 — аудит, виправлення та пакетні онлайн-метадані

Дата: 08.09.2026. База: `MyHomeLib-7.2-WIP-Iteration28-2026-09-07-source.zip`.

**Стан: WIP. MHL-111 виконано частково; release acceptance не завершено.** Maven coordinates залишаються `7.1.0`; позначка `7.2 WIP` описує поточний roadmap checkpoint.

## Реалізований сценарій

У головному меню доступна команда пакетного пошуку онлайн-метаданих для книг, вибраних прапорцями. `MetadataBatchService` обмежує пакет 100 унікальними книгами та підтримує до 4 одночасних book lookup. Провайдери мають власні throttling та deadline. Progress і скасування доступні під час пошуку та збереження.

Для однієї книги збережено вибір кандидата. Для пакета найкращий кандидат кожної книги потрапляє лише до preview; поля за замовчуванням не вибрані. Apply працює тільки для явно вибраних полів конкретної книги: title, authors, ISBN, year, publisher, language, annotation.

Збереження перечитує актуальні записи всередині однієї транзакції, перевіряє collection identity та cancellation, виконує один batch write і повідомляє індекс після commit. Помилка запису або cancellation до commit відкочує весь пакет. Скасування після commit не є undo; загальний журнал/undo — MHL-112. Rating/progress/review/keywords, artifacts і preferred artifact збережені.

Detached operation lease утримує колекцію до фактичного завершення lookup workers навіть при cancellation зовнішнього future. Класичне асинхронне редагування також отримало lease і guard token.

## Додаткові виправлення

- `Book.toBuilder()` замінив повторювані копії полів у domain/edit/import/metadata/sync та усунув втрату artifacts у повернених об’єктах.
- OpenLibrary і GoogleBooks використовують спільну HTTP cancellation, ISBN, нормалізацію та backoff mechanics. Deadline й reservation коректно працюють із від’ємним `nanoTime` та signed wraparound.
- Lucene отримує новий `IndexWriterConfig` на кожну спробу відкриття; помилка commit/observer не перешкоджає cleanup. Collection lifecycle і commit callbacks використовують один monitor. Standalone commit interval має значення 10000 до Spring injection.
- INPX закриває ZIP при помилці початкової валідації; stream cleanup посилено. Standalone INP пропускає порожні рядки так само, як INPX.
- Додано відсутній ключ import stage та ключі batch UI до uk/en/bg, синхронізовано bundled каталоги. Застарілі checks переведено на поточні registry, FXML handlers та localization keys.
- Прибрано повторювані provider helpers і невикористовувані private methods; `FolderSyncCounters` виділено з великого сервісу.
- Один виклик `List.getLast()` у `TextStorageImpl` замінено еквівалентним індексованим доступом для standalone parser diagnostics. Це не зміна цільової Java 21 у POM.

## Фактична перевірка

| Набір | Результат | Межа |
|---|---:|---|
| Domain/application/infrastructure JUnit | 38 PASS | Java 17, вибрана сумісна підмножина реального source |
| UI source contracts | 4 PASS | Без запуску вікна JavaFX |
| INPX compatibility/resource + INPX/FB2 corpus probes | 13 PASS | Parser layer; `-Xmx512m` |
| Статичні scripts | 70 PASS | Із 76 вибраних scripts |
| Статичні scripts із `--release 21` | 6 BLOCKED | Java 17 не підтримує release 21 |
| Maven offline `test-compile` | BLOCKED | `invalid target release: 21` |
| UI compilation diagnostic | BLOCKED | 25 відсутніх методів Java 21 List API; не BUILD SUCCESS |
| Windows DPI/portable/installer; live GitHub security gates | NOT RUN | Потрібні відповідні середовища |

Надані дані: 707154 INPX records; SHA-256 потоку всіх назв збігся з незалежним strict UTF-8 reader. 3 порожні назви та 4 назви з U+FFFD присутні вже у джерелі. Обидві великі FB2 пройшли перевірку тексту, TOC і меж chapter offsets.

Журнали та перелік перевірок: `verification/iteration29/`. Корпус книг та Maven cache не включені до source ZIP.

## Розбіжність scope MHL-111

`TASKS-2026-09-08-ITERATION-29.md` описує online metadata batch. Початковий Excel вимагає також 10000+ selected books, bulk set/clear, regex replace, trim/case, genre/series/tags і audit/undo. Ця ітерація реалізує online batch, але не ці ширші можливості. Excel O34 встановлено у **«В роботі»**, а первісні acceptance criteria збережені.

Перш ніж закривати MHL-111, необхідні повний JDK 21 reactor, JavaFX acceptance та реалізація ширшого контракту Excel з MHL-112. Історичний test-order hang всього infrastructure reactor не позначено усунутим: цільові Lucene тести пройшли разом, але весь reactor тут не запускався.
