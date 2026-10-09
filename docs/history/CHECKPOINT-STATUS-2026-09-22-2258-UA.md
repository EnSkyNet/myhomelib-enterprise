# MyHomeLib 8.0.0 — Deep Audit Checkpoint

Дата: 2026-09-22

## Стан перевірок перед checkpoint

- Reactor package gate: **20 модулів — BUILD SUCCESS** (`-DskipTests -Pproduction`).
- `myhomelib-application`: **426 тестів**, 0 failures, 0 errors, 1 skipped.
- `myhomelib-ui`: **167 тестів**, 0 failures, 0 errors.
- Infrastructure high-risk regression set: **27 тестів**, 0 failures, 0 errors.
- E2E rollback journey: **2 тести**, 0 failures, 0 errors.
- Windows-target bootstrap JAR зібраний з `javafx.platform=win`; всередині присутні JavaFX 21.0.12 Windows x64 classifier JAR-и та відсутні Linux JavaFX classifier JAR-и у перевіреному наборі.

## Основні зміни після checkpoint 20:35

- Завершено crash-recoverable протокол видалення колекції: `prepare/detach -> registry commit -> cleanup`, із durable recovery marker/tombstone та rollback після помилки registry commit.
- Зовнішні/невідомі attached SQLite БД більше не видаляються під час видалення запису колекції; очищаються лише похідні індекси/стан.
- Додано ownership-політику БД колекції (`OWNED / EXTERNAL / UNKNOWN`) та використання на create/legacy attach/delete.
- Посилено application-level lifecycle/query isolation для collection-scoped читань і background maintenance.
- Виправлено nested shared lifecycle coordination для post-import maintenance.
- Виправлено stale UI callbacks у Collection Workspace та Database Tools.
- Посилено full-text auto-scheduling при зміні active collection.
- Продовжено no-follow/TOCTOU hardening файлових і archive boundary.

## Windows portable

Оскільки canonical `jpackage` Windows `.exe/app-image` має будуватися на Windows, Linux checkpoint не видає Linux-бінарник за Windows `.exe`.

Підготовлений portable ZIP використовує Windows-target Spring Boot JAR із JavaFX 21.0.12. Launcher:
1. використовує локальний `runtime\\bin\\javaw.exe`, якщо він уже є;
2. інакше запускає `Setup-Runtime.ps1`, який завантажує офіційний Microsoft OpenJDK 21.0.12.1 x64 ZIP та його SHA-256, перевіряє хеш і розпаковує runtime локально;
3. запускає застосунок із `-Dmyhomelib.portable=true` і `-Dmyhomelib.launchDir=<portable root>`, тому дані зберігаються у `data/` поруч із програмою.

EPUBCheck включений. Windows `mobitool.exe` не включений, бо його canonical build потребує Windows C++ toolchain; це обмежує MOBI conversion, але не основну роботу застосунку.

## Відомі межі перевірки

- Windows portable не запускався нативно в цьому Linux build-середовищі; перевірено структуру ZIP, Windows JavaFX dependencies, launcher/config contract та integrity архіву.
- Повний infrastructure suite у цьому checkpoint не проганявся суцільно; прогнано 27 high-risk infrastructure regression tests, пов'язаних із поточними змінами.
