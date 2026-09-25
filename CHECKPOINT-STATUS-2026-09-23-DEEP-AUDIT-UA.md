# MyHomeLib — Deep Audit Checkpoint
## 2026-09-23

### Статус
Глибокий статичний аудит і виправлення виконувались поверх checkpoint `MyHomeLib-8.0.0-R4-CONVERSION-FIX-CHECKPOINT-2026-09-23`.
Тести під час звичайного аудиту не запускались. Повний release-gate запущено лише після прямої команди користувача «давай архів з проектом і портативну збірку».

### Основні виправлення production-коду
- Посилено filesystem/no-follow boundary у maintenance, deletion/recovery, organizer та ZIP/JAR читанні.
- Закрито cross-collection та stale-context race-и в Updates, Reader annotations/bookmarks, Followed Authors, Duplicate Review, Book Details та download pipeline.
- Закрито queue-gap у cancellable executor: скасування тепер завершує і worker, і completion future.
- Виправлено lifecycle/error outcome для асинхронної Lucene rebuild: `Error` більше не перетворюється на успіх.
- WebDAV conflict resolution перенесено з JavaFX UI thread у background SYNC flow.
- У download pipeline authoritative state повторно перевіряється fail-closed під `DOWNLOAD` lease; другорядний bookkeeping після успішного storage commit більше не створює хибний `FAILED`.
- Посилено безпечний запуск зовнішніх executable для command test, Linux secret-tool і power probe.
- `ProcessExecutionSupport` більше не може безкінечно чекати stdin-writer після завершення дочірнього процесу.
- Винесено shared bounded+cancellable conversion input; захист застосовано до Convert, Export і Organizer.
- `TxtBookConverter` переписано на streaming StAX без втрати пробілів між XML chunks та без накопичення всієї книги в RAM.
- Виправлено TXT import fallback: malformed UTF-8 тепер детектується decoder-ом з `CodingErrorAction.REPORT`, тому Windows-1251 fallback реально спрацьовує.
- Organizer metadata parsing обмежений за розміром і завершується до body; fingerprint та physical SHA будуються зі стабільного staged byte stream.
- Прибрано stale fingerprint cache, який можна було обійти збереженням size/mtime/fileKey.
- Plugin scan читає manifest + SHA-256 з одного вже відкритого safe ZIP handle.
- Прибрано dead legacy converter lookup, який обходив fail-closed probe path.

### Release-gate після команди на архів
Фінальний production reactor:
- 20 модулів — `BUILD SUCCESS` (`package -DskipTests -Pproduction`).

Поточні surefire reports:
- application: 426 тестів, 0 failures, 0 errors, 1 skipped;
- infrastructure: 575 тестів, 0 failures, 0 errors, 7 skipped;
- UI: 167 тестів, 0 failures, 0 errors;
- reader: 94 тести, 0 failures, 0 errors, 1 skipped;
- architecture: 14 тестів, 0 failures, 0 errors;
- E2E: 15 тестів, 0 failures, 0 errors;
- MCP: 9 тестів, 0 failures, 0 errors;
- OPDS: 24 тести, 0 failures, 0 errors;
- web: 10 тестів, 0 failures, 0 errors;
- bootstrap: 32 тести, 0 failures, 0 errors, 2 skipped;
- converter toolchain: 38 тестів, 0 failures, 0 errors, 1 skipped;
- EPUB optimizer: 16 тестів, 0 failures, 0 errors;
- organizer: 25 тестів, 0 failures, 0 errors;
- усі наявні release-gate surefire reports разом: 1551 тест, 0 failures, 0 errors, 15 skipped.

### Регресії, знайдені саме release-gate
- Оновлено застарілі MetadataBatchService contract tests після переходу на collection-stable lookup без довгого QUERY lease.
- Виправлено production-дефект TXT importer: UTF-8 replacement behavior блокував Windows-1251 fallback.
- Оновлено path/symlink contract tests під сильнішу no-follow policy.
- Оновлено Lucene lifecycle fixture під current active-state invariant.
- Оновлено UI fixtures/contracts після додавання ApplicationState та collection-scoped stale-context guard-ів.

### Windows portable
Поточне середовище — Linux. Стандартний `package-portable.ps1` створює Windows app-image через Windows `jpackage`, тому Linux `jpackage` не можна використовувати як заміну.
У наданих файлах є Windows JavaFX 21.0.12 Maven artifacts і Windows converter pack, але немає Windows x64 JDK 21 з `jpackage`.
Для завершення та Windows smoke/acceptance потрібен Windows x64 JDK 21 ZIP (рекомендовано Temurin 21.0.12.1+1 або сумісний Java 21 JDK) і Windows host/runner.
