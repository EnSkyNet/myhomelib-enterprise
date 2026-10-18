# MyHomeLib R18 — аудит V18 → V19

Дата: 17 жовтня 2026 року. Основа: `MyHomeLib-Enterprise-8.0.0-R18-PRODUCTION-READINESS-SOURCE-V18-CONSOLIDATED.zip`.

## READER-WEB-CAS-01 — збереження без ревізії

`WebReaderService.saveProgress(...)` у V18 формував `ReadingProgressDto` без `revision`. SQLite-репозиторій у разі `null` застосовував compatibility UPSERT, який міг перезаписати новішу позицію іншого пристрою. Для методу з `WebProgressUpdate` ревізія читалася не під час початкової перевірки віку запиту, а взагалі не передавалася в `save`.

Виправлення: прямий Web Reader запис зберігає ревізію, отриману під час актуального читання; синхронізований браузерний запит зберігає **саме початкову** спостережену ревізію через витратне отримання вмісту. Відсутність рядка позначається токеном `revisionWhenAbsent`, включно з tombstone generation. Відповідний SQLite UPDATE/INSERT атомарно відхиляє застарілий CAS. Типізований виняток `ReadingProgressConflictException` належить application (не пакету портів) і є батьком наявного infrastructure-винятку. Для браузерного запиту з конфліктом повертається `accepted=false` із актуальною канонічною позицією. За конкурентного видалення повертається IOException (немає канонічної позиції для `WebProgressSaveResult`).

Регресії: `WebReaderServiceTest.directWebSaveUsesCompareAndSwapRevisionInsteadOfBlindLegacyUpsert`, `browserSaveUsesObservedRevisionEvenIfDesktopUpdatesDuringExtraction`. Перша відтворена як FAIL на V18; обидві PASS на виправленому коді. Не вирішує усіх legacy/sync writer-шляхів, усіх конфліктів часових позначок і відновлення після падіння на Windows.

## ORGANIZER-PREFLIGHT-DIAGNOSTICS-01

Раніше початкова перевірка SHA-256 могла довго працювати до першого `ORGANIZER_STAGE=...` і до завершення перевірки, ускладнюючи встановлення фактичної фази. `ApplyLibraryOrganizationUseCase.preflight()` тепер пише `ORGANIZER_PREFLIGHT_SOURCE_START/VERIFIED` перед/після перевірки джерела для першого й кожного 1000-го запису, з ID операції, порядковим номером, дією й розміром. Коди не змінюють файлові дані, SHA-256 або SQLite-транзакції. Потенційне OS-level блокування під час `read`/`write` не має встановленого таймауту — проблему першої реальної книги **не визнано усуненою**.

## Межі й критерії

- Linux/Xvfb — перевірка Java й offline Maven; це **не Windows/NTFS/DPAPI sign-off**.
- `Flib.db`, наявні книги, каталоги користувача не відкривалися для запису.
- SQLite/Flyway V1–V73 та схеми незмінні.
- Потрібні окремо: Windows I/O hangs, kill-point recovery, антивірус і UNC, CAS аудит інших writer-ів, повний імпорт INPX та тривалі JFR/JavaFX навантаження.

Підсумкові фактичні журнали, контрольні суми, статистика JUnit/Surefire та окремий український звіт додаються до пакета результатів V19.
