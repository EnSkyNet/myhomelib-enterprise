# Статус checkpoint — 22.09.2026

## Середовище

- Java: 21.0.11
- Maven: 3.9.6
- Режим перевірки: offline, локальний `.mvn/repository`

## Перевірки перед архівуванням

- `myhomelib-application`: 411 тестів, 0 failures, 0 errors, 1 skipped — PASS.
- MyHomeLib EPUB Optimizer: 16 тестів, 0 failures, 0 errors — PASS.
- MyHomeLib Library Organizer: 25 тестів, 0 failures, 0 errors — PASS.
- MyHomeLib Converter Toolchain: 36 тестів, 0 failures, 0 errors, 1 skipped — PASS.
- `myhomelib-reader`: 94 тести, 0 failures, 0 errors, 1 skipped — PASS.
- `myhomelib-ui` під `xvfb-run`: 167 тестів, 0 failures, 0 errors — PASS.
- MyHomeLib MCP Server: 9 тестів, 0 failures, 0 errors — PASS.
- Targeted high-risk `myhomelib-infrastructure`: 112 тестів, 0 failures, 0 errors — PASS.
- Фінальний повний reactor `package -DskipTests`: усі 20 модулів — BUILD SUCCESS.

## Важливе обмеження перевірки

Повний `myhomelib-infrastructure` test suite не був завершений як єдиний суцільний прогін: попередній паралельний запуск перевищив 5-хвилинне вікно під час `SqliteOperationHistoryAcceptanceTest`. Тому повний infrastructure suite не позначається як PASS. Водночас усі змінені та високоризикові infrastructure-сценарії, включені до targeted-набору (112 тестів), завершилися без failures/errors.

## Основні напрями змін після попереднього checkpoint

- collection lifecycle / `QUERY`, `EDIT`, `READER_STATE` та інші barrier-и на application boundary;
- усунення queue-gap між enqueue і перемиканням активної колекції;
- collection-scoped cache isolation і збереження порядку `findByIds`;
- hardening symlink/reparse/TOCTOU для локальних файлів, імпорту, backup/recovery, reader та зовнішніх процесів;
- channel/handle-based безпечне відкриття ZIP/INPX/7z/RAR через `NOFOLLOW_LINKS`;
- виправлення full-text indexing finalization/discard race;
- виправлення SQLite legacy read-only attach для sqlite-jdbc 3.53.4;
- виправлення EPUB fallback validation на staged source;
- регресійні тести для нових security/lifecycle boundary.

## Вміст архіву

До checkpoint не включаються build/cache-артефакти: `target/`, `dist/`, `.mvn/repository/`. Maven Wrapper і Maven 3.9.6 у `.mvn/maven` залишаються в архіві.
