# MyHomeLib — FB2/Calibre Runtime Fix Checkpoint
## 2026-09-24

### Причина виправлення
Реальний Windows log показав, що після створення похідного PDF наступна конвертація EPUB могла обрати PDF як source artifact. Це переводило маршрут на Calibre замість нативного FB2/toolchain шляху. Одночасно portable Calibre міг пройти `--version`, але бути неповним без `calibre-parallel.exe`, через що реальна конвертація падала з WinError 2.

### Виправлено
- `ConvertBookUseCase` більше не ставить похідний conversion artifact вище оригінального/imported artifact лише тому, що похідний файл є preferred для читання.
- Для повторної конвертації original/imported artifacts мають пріоритет над artifacts з `sourceId=conversion:*` або `conversion.*` metadata.
- Provider-local Calibre failures класифікуються як retryable, щоб AUTO міг перейти до іншого provider/source, не маскуючи hard source/security failures.
- Windows Calibre вважається доступним лише якщо поруч з `ebook-convert.exe` є обов'язковий `calibre-parallel.exe`.
- Та сама completeness-перевірка додана в Calibre plugin і converter-tool probe.
- `prepare-runtime-tools.ps1` виявляє incomplete Calibre bundle.
- `package-desktop.ps1` не пакує incomplete Calibre як працездатний fallback.
- `check-windows-converter-bundle.ps1` перевіряє `calibre-parallel.exe` і виконує реальний functional smoke TXT -> EPUB.

### Нові regression tests
- `ConvertBookUseCaseTest.prefersOriginalArtifactOverDerivedPreferredArtifactForConversion`.
- `ConverterToolProbeTest.rejectsIncompleteWindowsCalibreLayoutEvenWhenVersionProbeSucceeds`.
- `CalibreCliBookConverterTest` оновлено під retryable provider-failure contract.

### Release verification
Поточні зміни перевірено після команди користувача на архів:
- application conversion regression: 15 тестів, 0 failures, 0 errors;
- converter tool probe: 6 тестів, 0 failures, 0 errors;
- Calibre infrastructure targeted: 12 тестів, 0 failures, 0 errors;
- Calibre plugin: 5 тестів, 0 failures, 0 errors;
- full infrastructure: 575 тестів, 0 failures, 0 errors, 7 skipped;
- current application production code також пройшов повний application gate: 426 тестів, 0 failures, 0 errors, 1 skipped;
- current converter-toolchain production code пройшов full module gate: 38 тестів, 0 failures, 0 errors, 1 skipped;
- фінальний reactor `package -DskipTests -Pproduction`: 20/20 модулів — BUILD SUCCESS.

До останнього runtime fix повний 20-модульний Xvfb gate був зелений: 1552 тести, 0 failures, 0 errors, 15 skipped. Після runtime fix повторно повністю прогнані всі змінені production-модулі/шляхи, зазначені вище.

### Windows portable JAR
Створено оновлений `myhomelib-bootstrap-8.0.0.jar`:
- JavaFX 21.0.12 Windows x64 classifier JAR-и;
- Linux JavaFX classifier JAR-и відсутні;
- `BOOT-INF/classpath.idx`/`BOOT-INF/layers.idx` узгоджені з Windows classifier names;
- JAR/ZIP integrity — OK;
- headless `--release-smoke` — `MYHOMELIB_RELEASE_SMOKE_OK` у UTF-8 середовищі та шляху з пробілами/Unicode.

### Важливо
Повний GUI/Windows-native smoke і фактичний запуск `ebook-convert.exe`/`calibre-parallel.exe` виконуються на Windows host. Linux-host не може виконати Windows PE binaries.
