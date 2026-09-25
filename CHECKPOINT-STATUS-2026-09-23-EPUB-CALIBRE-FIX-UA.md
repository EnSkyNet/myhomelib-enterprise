# MyHomeLib — EPUB/Calibre Fix Checkpoint
## 2026-09-23

### Що виправлено після Deep Audit checkpoint
- Calibre перенесено до спільного блоку конвертерів у налаштуваннях.
- Для Calibre додано окреме поле шляху до `ebook-convert.exe`, кнопку вибору executable, timeout та участь у спільній перевірці конвертерів.
- Діагностика Calibre тепер виконує фактичний probe та показує доступність, версію і фактичний executable.
- Якщо користувач явно задав некоректний шлях до Calibre, runtime більше не підміняє його іншим Calibre, знайденим через PATH/стандартну інсталяцію.
- Порожній explicit path лишається режимом автопошуку portable/PATH, що відображається через фактичний шлях у діагностиці.
- Покращено повідомлення UI про рушій конвертації та стадію помилки.
- Помилки конвертації класифікуються за стадіями: SOURCE_READ, PROVIDER, OUTPUT_VALIDATION, PUBLICATION, CATALOG_REGISTRATION.
- AUTO fallback тепер може перейти від toolchain до Calibre на provider-local помилках, зокрема недоступному рушії, crash/timeout або некоректному EPUB output; помилки джерела/безпеки/спільного I/O не маскуються fallback-ом.
- `CancellationException` більше не загортається в звичайну provider failure і коректно припиняє операцію як скасовану.
- Тести конвертації оновлено під explicit user-selected output-directory contract та нову classified-error модель.

### Release-gate
Повний reactor test gate запускався тільки після прямої команди користувача на архів.

Команда виконана під Xvfb для JavaFX UI:
- 20 модулів — BUILD SUCCESS;
- 1552 тести;
- 0 failures;
- 0 errors;
- 15 skipped.

Ключові модулі:
- application: 426 тестів;
- infrastructure: 575;
- UI: 167;
- reader: 95;
- converter toolchain: 38;
- Calibre plugin: 5;
- EPUB optimizer: 16;
- organizer: 25;
- E2E: 15;
- architecture: 14.

Фінальний `package -DskipTests -Pproduction`:
- 20/20 модулів — BUILD SUCCESS.

### Windows portable JAR
Створено окремий fat JAR для Windows portable:
- Linux JavaFX native JAR-и вилучені;
- додані JavaFX 21.0.12 Windows x64 JAR-и;
- `BOOT-INF/classpath.idx` і `BOOT-INF/layers.idx` переведені з `-linux.jar` на `-win.jar`;
- ZIP/JAR integrity — OK;
- headless `--release-smoke` — `MYHOMELIB_RELEASE_SMOKE_OK` у UTF-8 середовищі.

Важливо: повний GUI/Windows-native smoke цього JAR у Linux-host виконати неможливо; його потрібно запускати у Windows portable разом із Windows runtime та конвертерами.
