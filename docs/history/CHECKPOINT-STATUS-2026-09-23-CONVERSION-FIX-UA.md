# MyHomeLib 8.0.0 — checkpoint виправлення конвертації
## Дата: 2026-09-23

### Мета checkpoint
Виправити недоступність/неправильне виявлення зовнішніх конвертерів для перетворення книг, насамперед FB2 → EPUB/PDF/KEPUB, та перевірити регресії перед створенням source-архіву.

### Виправлено
- `ConverterToolLocator` тепер знаходить Windows bundle layout:
  - `tools/converters/fbc-windows-amd64/fbc.exe`;
  - `tools/converters/pandoc-3.11/pandoc.exe`;
  - `tools/converters/Calibre/ebook-convert.exe`.
- `CalibreCliBookConverter` вирівняно з фактичним layout portable Calibre.
- `package-desktop.ps1` копіює fb2cng, Pandoc і Calibre у Windows desktop/portable bundle, якщо вони підготовлені.
- `tools/prepare-runtime-tools.ps1` розгортає/перевіряє Windows runtime tools із підготовленого `tools.7z`.
- Додано `tools/check-windows-converter-bundle.ps1` для Windows-side перевірки комплекту конвертерів.
- Додано/оновлено regression-тести discovery та Calibre CLI.
- Виправлено застарілий `MetadataDatabaseMigrationTest`: production metadata schema вже має навмисну V11 `collection_database_ownership`; тест тепер очікує V11 і окремо перевіряє колонку `owns_database`.

### Перевірка тестових книг
Дві надані FB2-книги перевірені як ZIP-контейнери: CRC коректний; усередині кожного архіву один FB2; XML повністю парситься як FictionBook. Пошкодження тестових книг не виявлено.

### Pre-archive regression gates
- Application: **426 tests**, 0 failures, 0 errors, 1 skipped.
- EPUB Optimizer: **16 tests**, 0 failures, 0 errors.
- Library Organizer: **25 tests**, 0 failures, 0 errors.
- Converter Toolchain: **38 tests**, 0 failures, 0 errors, 1 skipped.
- Reader: **95 tests**, 0 failures, 0 errors, 1 skipped.
- UI (через `xvfb-run`): **167 tests**, 0 failures, 0 errors.
- MCP: **9 tests**, 0 failures, 0 errors.
- Infrastructure high-risk targeted gate: **62 tests**, 0 failures, 0 errors.
- E2E: **15 tests**, 0 failures, 0 errors.
  - у тому числі `CollectionRollbackJourneyE2ETest`: 2/2.
- Окремі migration regressions:
  - `MetadataDatabaseMigrationTest`: PASS (metadata schema V11);
  - `MultiArtifactMigrationV50Test`: PASS.
- Final reactor package gate: **20/20 modules BUILD SUCCESS** (`package -DskipTests -Pproduction`).

### Важливе обмеження
Цей checkpoint створено й перевірено у Linux-середовищі. Windows PE-файли (`fbc.exe`, `ebook-convert.exe`, `mobitool.exe`) тут не виконувались як Windows processes. Тому реальний Windows smoke FB2/MOBI/AZW → EPUB і smoke-start Windows portable потрібно виконати на Windows x64 перед позначенням portable-релізу як остаточно прийнятого.

### Runtime tools
Source checkpoint не вбудовує великий зовнішній `tools.7z`. Для підготовки Windows bundle використовуйте наданий окремо `tools.7z` разом із `tools/prepare-runtime-tools.ps1`.
