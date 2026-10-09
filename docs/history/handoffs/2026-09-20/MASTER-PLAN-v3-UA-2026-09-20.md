# MyHomeLib Conversion Toolchain + Library Organizer — MASTER PLAN

**Версія:** 3.0  
**Дата:** 2026-09-20  
**База розробки:** `MyHomeLib-8.0.0-DEVELOPER-SOURCE-R4-CORRECTED-2026-09-19.zip`  
**Статус:** реалізаційний master-plan; включає Conversion Toolchain, Library Organizer та EPUB Optimizer / Maximum Compression  
**Замінює:** `MyHomeLib-Converter-Toolchain-Revised-Plan-UA-2026-09-20.md` та `MyHomeLib-Native-Converter-Implementation-Plan-UA-2026-09-19.md`

---

## 0. Коротке рішення

Ми **не переписуємо всі книжкові конвертери з нуля**. Складні форматні рушії використовуються як ізольовані зовнішні інструменти (`fb2cng`, `Pandoc`, `libmobi`, а Calibre — як останній fallback). Самі пишемо в MyHomeLib маршрутизацію, security/process layer, capabilities, validation, normalization, Library Organizer, duplicate logic, crash-safe filesystem operations, journal/rollback і UI.

Library Organizer, Conversion Toolchain і EPUB Optimizer будуються на **спільному фундаменті**. Format detection, metadata extraction, fingerprints, safe paths, staging, operation journal, validation і resource graph не дублюються. Analyzer/preview Organizer можна розробляти паралельно з інтеграцією converters; EPUB Optimizer можна починати паралельно одразу після появи першого стабільного маршруту `FB2 -> EPUB3`; destructive Apply вмикається лише після готовності journal/rollback.

Після кожної конвертації в canonical EPUB3 Toolchain може виконати post-processing: перевірку ресурсів, deduplication, image/font optimization, безпечне очищення XHTML/CSS та фінальне OCF ZIP-пакування. Базовий production target — EPUB 3.3. Максимальне стиснення в межах EPUB 3.3 використовує лише ZIP Store/Deflate; LZMA/LZMA2/Zstd як ZIP-method усередині EPUB не допускаються. JPEG XL (`image/jxl`) та AVIF (`image/avif`) закладаються як **опційний modern profile для EPUB 3.4**, а не як default, тому що EPUB 3.4 станом на 2026-09-20 є Candidate Recommendation Draft, а не W3C Recommendation.

### Цільова структура бібліотеки за замовчуванням

```text
Автор/Серія/01 - Назва.epub
Автор/Назва.epub
```

EPUB3 — canonical формат для reflowable книг. PDF за замовчуванням лишається PDF. KEPUB/KFX — похідні device-формати, а не canonical storage. Оригінали не видаляються автоматично. Fuzzy duplicates ніколи не видаляються без review.

---

# Частина I. Conversion Toolchain — перевірене рішення на основі аналізу fb2cng / Pandoc / libmobi

## 1. Остаточне архітектурне рішення

### 1.1. Не переписувати всю конвертацію з нуля

Після аналізу реального вихідного коду `fb2cng`, `pandoc` та `libmobi` повний rewrite усіх форматів у Java **не є оптимальним першим кроком**.

Причини:

1. `fb2cng` уже має дуже глибоку FB2-модель, footnotes, tables, CSS, images, fonts, metadata, EPUB2/3, KEPUB, KFX/AZW8, PDF і TXT.
2. `fb2cng` містить **спеціальний Windows connector для MyHomeLib** (`cmd/mhl`). Тобто сумісність з концепцією MyHomeLib закладена самим проєктом.
3. Pandoc 3.11 уже має production readers/writers для EPUB, DOCX, HTML, RTF, TXT/Markdown та FB2.
4. `libmobi` 0.12 уже розбирає MOBI/PRC/AZW/AZW3/KF8 і має `mobitool -e`, який відновлює EPUB.
5. Повторна самостійна реалізація MOBI/KF8, EPUB packaging, FB2 quirks, footnotes, image normalization, KFX і PDF layout означала б десятки тисяч рядків складного коду та великий ризик регресій.

### 1.2. Не копіювати GPL-код у Java-код MyHomeLib

`fb2cng` має GPL-3.0, Pandoc — GPL-2.0-or-later. Їхній вихідний код не переносимо в модулі MyHomeLib.

Використовуємо їх як **окремі зовнішні процеси** з чітким process boundary:

```text
MyHomeLib (Java)
      |
      +--> fbc.exe
      +--> pandoc.exe
      +--> mobitool.exe
      +--> ebook-convert.exe       [fallback]
```

Власними залишаються:

- capability matrix;
- route selection;
- provider priority;
- staging/temp workspace;
- cancellation;
- timeout;
- output-size limit;
- process-tree termination;
- validation;
- normalization of errors;
- UI/settings;
- tool discovery/version checking;
- multi-step conversion;
- fallback policy;
- security policy;
- tests.

**Примітка щодо ліцензій:** при розповсюдженні сторонніх binaries треба окремо виконати умови відповідних GPL/LGPL ліцензій (notices, license texts, corresponding source/source offer тощо). Це слід фінально перевірити юридично перед публічним/комерційним релізом.

---

## 2. Що реально перевірено в наданих архівах

### 2.1. Архіви

| Архів | SHA-256 | Стан ZIP |
|---|---|---|
| `libmobi-public.zip` | `b35618a979ca99cb0655a7d4028e7daebc08d5ea4e6135f888e8af8380879a03` | CRC OK |
| `fb2cng-master.zip` | `92d8cd15fa054b42f63d837cbdbf8b8cff22fa6d3a8368755734bce84b0eabe2` | CRC OK |
| `pandoc-main.zip` | `bbd44c6aa4eceb4926a2b3d05e9c42b9f384cd436ccfdf30bacfefc00aeadc49` | CRC OK |

### 2.2. `fb2cng`

Факти з коду:

- мова: Go;
- `go.mod`: Go `1.27.1`;
- ліцензія: GPL-3.0;
- приблизно 325 власних `.go` файлів без `vendor`;
- приблизно 116 тис. рядків Go-коду;
- 126 test-файлів, приблизно 49 тис. рядків тестів;
- release workflow крос-компілює Windows/Linux/macOS;
- release збирається з `CGO_ENABLED=0`;
- є `cmd/mhl` — connector, спеціально написаний під MyHomeLib;
- є точний CLI параметр `--output-file`, що ідеально відповідає нашому staging-path contract.

Підтримка FB2 ->:

- EPUB2;
- EPUB3;
- KEPUB;
- KFX;
- AZW8;
- PDF;
- TXT;
- Markdown.

Сильні сторони:

- structured FB2 parser;
- footnotes і backlinks;
- EPUB2/EPUB3 navigation;
- tables;
- CSS;
- embedded fonts;
- images/SVG;
- metadata mapping;
- series;
- ISBN;
- genres;
- cover;
- page maps;
- native PDF renderer;
- KFX generator;
- багато regression tests.

Особливо важливо: `cmd/mhl/internal/target` показує, що старий target `fb2mobi` фактично за замовчуванням означає **KFX**, а не старий MOBI.

### Висновок

`fb2cng` — **основний FB2 engine**. Не переписуємо FB2->EPUB/PDF/KEPUB/TXT з нуля.

Але **не використовуємо legacy `mhl-connector.exe` напряму** у новій Java-версії MyHomeLib, тому що:

1. наш SPI уже кращий за старий filesystem contract;
2. `fb2mobi` -> KFX не відповідає поточному enum `MOBI`;
3. нам потрібна точна capability matrix.

MyHomeLib повинен запускати **`fbc.exe` напряму**:

```text
fbc.exe convert --to epub3 --output-file <DST> <SRC>
fbc.exe convert --to kepub --output-file <DST> <SRC>
fbc.exe convert --to pdf   --output-file <DST> <SRC>
fbc.exe convert --to txt   --output-file <DST> <SRC>
```

### Важливий build prerequisite

Наданий source ZIP `fb2cng` не є повністю самодостатнім release-source для offline build:

- release task завантажує hyphenation dictionaries;
- sentence data;
- 13 Noto PDF fonts;
- наш поточний Linux env має Go 1.23.2, тоді як source вимагає Go 1.27.1.

Отже для першої інтеграції треба використовувати upstream Windows release binary `fbc.exe`, або окремо підготувати повний reproducible build environment.

---

## 3. Pandoc 3.11 — де використовуємо, а де ні

Факти з archive:

- версія: 3.11;
- мова: Haskell;
- ліцензія: GPL-2.0-or-later;
- readers: EPUB, FB2, DOCX, HTML, RTF, ODT, Markdown та багато інших;
- writers: EPUB2/3, FB2, DOCX, HTML, RTF, plain text та багато інших.

### Не використовувати Pandoc як головний FB2 engine

У `Text.Pandoc.Readers.FB2` прямо є TODO:

- Tables;
- Named styles;
- ID attributes.

Reader також ігнорує/не переносить частину FB2 metadata:

- `src-title-info`;
- `document-info`;
- `publish-info`;
- `custom-info`;
- `sequence`;
- genre не переноситься як повноцінна FB2 semantic модель.

Тому:

**FB2 -> EPUB/PDF/KEPUB/TXT: fb2cng, не Pandoc.**

### Де Pandoc дуже корисний

Pandoc стає general document bridge для:

- EPUB -> FB2;
- EPUB -> DOCX;
- EPUB -> RTF;
- EPUB -> TXT;
- DOCX -> EPUB;
- DOCX -> FB2;
- RTF -> EPUB;
- HTML -> EPUB;
- TXT/Markdown -> EPUB;
- ODT -> EPUB/DOCX/FB2.

### Security

Pandoc 3.11 має штатний параметр:

```text
--sandbox
```

Manual прямо рекомендує його для untrusted input.

Для MyHomeLib Pandoc запускаємо тільки так:

- `--sandbox`;
- без Lua filters;
- без custom writers;
- без `--pdf-engine` у першій ітерації;
- з process timeout;
- з heap limit, наприклад `+RTS -M512M -RTS`;
- у окремому temp workspace;
- тільки з explicit argv, без shell.

### Висновок

Pandoc — **другий engine**, general-purpose bridge. Його не переписуємо і не вбудовуємо як Haskell library у JVM.

Використовуємо окремий `pandoc.exe`.

---

## 4. libmobi 0.12 — роль у системі

Факти:

- C99;
- версія 0.12;
- LGPL-3.0-or-later;
- MOBI/PRC/PDB;
- AZW/AZW3/KF8;
- AZW4;
- PalmDOC;
- metadata;
- reconstruction of HTML/CSS/fonts/media;
- `mobitool -e` створює EPUB.

У наданому source:

- CMake build пройшов успішно;
- `mobitool`, `mobimeta`, `mobidrm` успішно зібралися;
- фактично виконано smoke:

```text
mobitool -e sample-textread.mobi
```

Результат:

- exit code 0;
- створений EPUB;
- ZIP CRC EPUB — OK;
- OPF/HTML/resources присутні.

### Обмеження

README самого проєкту містить TODO:

- improve writing;
- serialize rawml into raw records;
- process RESC records.

Тому libmobi **не використовуємо як наш MOBI/AZW3 writer**.

### Роль

Використовуємо тільки як input bridge:

```text
MOBI/PRC/AZW/AZW3/AZW4
          |
          v
     mobitool -e
          |
          v
         EPUB
```

Після цього EPUB може бути:

- фінальним output;
- input для Pandoc -> FB2/DOCX/RTF/TXT;
- input для інших майбутніх tools.

### Integration method

На першому етапі запускаємо `mobitool.exe` як зовнішній процес.

Не робимо JNI/JNA, тому що process boundary:

- захищає JVM від native crash;
- простіший deployment;
- простіша cancellation/timeout логіка;
- не прив'язує Java ABI до C ABI.

---

## 5. Нова цільова архітектура

Не створюємо зараз великий власний `NormalizedPublication` engine.

Його переносимо на майбутній етап, якщо будемо поступово замінювати конкретні engines власною реалізацією.

Перший production-крок:

```text
                         +-------------------+
                         | Existing direct    |
                         | FB2/EPUB/TXT copy  |
                         +---------+---------+
                                   |
                                   v
SOURCE -> Route Planner -> Conversion Route -> Validator -> staged output
               |                |
               |                +--> fbc.exe
               |                +--> pandoc.exe
               |                +--> mobitool.exe
               |                +--> ebook-convert.exe (fallback)
               |
               +--> capability / availability / priority
```

---

## 6. Новий Java-модуль

Створити:

```text
myhomelib-converter-toolchain
```

Залежності:

- `myhomelib-application`;
- `myhomelib-domain`;
- `myhomelib-shared`;
- JDK 21;
- без JavaFX;
- без native libraries;
- без source code Pandoc/fb2cng/libmobi.

Пакети:

```text
com.myhomelibcorp.converter.toolchain

  ToolchainBookConverter
  ConversionRoutePlanner
  ConversionRoute
  ConversionStep
  ConversionStepResult
  ConversionEngine
  ConversionEngineId
  ConversionFailureKind

  process/
    ExternalToolRunner
    ExternalToolRequest
    ExternalToolResult
    ProcessTreeTerminator
    BoundedOutputCapture

  discovery/
    ConverterToolLocator
    ConverterToolProbe
    ToolVersion
    ToolInstallation

  engine/fbc/
    FbcEngine
    FbcCommandBuilder
    FbcCapabilities

  engine/pandoc/
    PandocEngine
    PandocCommandBuilder
    PandocCapabilities

  engine/libmobi/
    LibMobiEngine
    MobiToolCommandBuilder
    LibMobiCapabilities

  engine/calibre/
    CalibreFallbackEngine

  validation/
    ConversionOutputValidator
    EpubOutputValidator
    Fb2OutputValidator
    PdfOutputValidator
    TextOutputValidator

  workspace/
    ConversionWorkspace
    WorkspaceCleaner
```

---

## 7. Зміни в існуючому SPI

Поточний `ConvertBookUseCase.selectJob()` вибирає **перший** compatible `BookConverter`.

Це небезпечно, бо Calibre core bean може бути вибраний раніше нового toolchain.

### Додати provider priority

У `BookConverter`:

```java
default int priority() { return 0; }
```

Рекомендований порядок:

| Provider | Priority |
|---|---:|
| exact same-format copy | 1000 |
| MyHomeLib Toolchain | 500 |
| explicit user external converter | 300 |
| Calibre | 100 |
| generic plugin fallback | 0 |

`ConvertBookUseCase` та `ExportToDeviceUseCase` повинні сортувати providers за priority, потім стабільно за `id()`.

Це дає deterministic selection і не залежить від Spring bean order.

---

## 8. Tool discovery

Стандартна структура bundled tools:

```text
MyHomeLib/
  MyHomeLib.cmd
  app/
  runtime/
  tools/
    converters/
      fbc/
        fbc.exe
        LICENSE.txt
      pandoc/
        pandoc.exe
        LICENSE.txt
      libmobi/
        mobitool.exe
        [libmobi.dll if dynamic build]
        LICENSE.txt
      calibre/
        ebook-convert.exe   [optional fallback]
```

Discovery priority:

1. explicit setting;
2. bundled tool path;
3. PATH;
4. standard installation paths where applicable.

Кожний executable при startup/Settings перевіряється:

- exists;
- expected basename/path;
- version probe;
- timeout;
- expected version syntax.

Tool version зберігати в diagnostics.

---

## 9. Capability matrix першої production-версії

### 9.1. FB2 source

| Source | Target | Engine |
|---|---|---|
| FB2 | EPUB | fb2cng EPUB3 |
| FB2 | KEPUB | fb2cng |
| FB2 | PDF | fb2cng |
| FB2 | TXT | fb2cng |
| FB2 | FB2 | existing direct copy |
| FB2 | FB2_ZIP | existing ZIP wrapper |
| FB2 | DOCX | fb2cng EPUB3 -> Pandoc DOCX |
| FB2 | RTF | fb2cng EPUB3 -> Pandoc RTF |
| FB2 | HTMLZ | later / Calibre initially |
| FB2 | MOBI | Calibre fallback |
| FB2 | AZW3 | Calibre fallback |

### 9.2. EPUB source

| Source | Target | Engine |
|---|---|---|
| EPUB | EPUB | direct copy |
| EPUB | FB2 | Pandoc |
| EPUB | DOCX | Pandoc |
| EPUB | RTF | Pandoc |
| EPUB | TXT | Pandoc plain |
| EPUB | KEPUB | Calibre initially; later `kepubify` |
| EPUB | PDF | Calibre initially; own renderer later |
| EPUB | MOBI/AZW3 | Calibre fallback |

### 9.3. DOCX / RTF / HTML / TXT / ODT source

Pandoc routes:

- -> EPUB;
- -> FB2;
- -> DOCX where meaningful;
- -> RTF;
- -> TXT/plain.

PDF/MOBI/AZW3/KEPUB remain fallback where no dedicated route exists.

### 9.4. MOBI / PRC / AZW / AZW3 / AZW4 source

Step 1:

```text
mobitool -e -> EPUB
```

Then:

| Target | Route |
|---|---|
| EPUB | libmobi only |
| FB2 | libmobi -> EPUB -> Pandoc FB2 |
| DOCX | libmobi -> EPUB -> Pandoc DOCX |
| RTF | libmobi -> EPUB -> Pandoc RTF |
| TXT | libmobi -> EPUB -> Pandoc plain |
| PDF | Calibre fallback initially |
| KEPUB | Calibre initially; kepubify later |
| MOBI/AZW3 | source copy if same format, otherwise Calibre |

### 9.5. Calibre fallback remains for

- PDF input;
- CHM;
- DJVU;
- CBZ/CBR/CB7/CBC;
- LIT;
- LRF;
- PDB edge cases outside libmobi;
- PML/PMLZ;
- RB;
- SNB;
- TCR;
- TXTZ;
- legacy Kindle output MOBI/AZW3;
- unsupported/rare edge cases.

---

## 10. Не плутати MOBI, AZW3, KFX і AZW8

Це окремий mandatory fix у capability model.

`fb2cng`:

- не генерує старий MOBI;
- не генерує AZW3/KF8 як current MyHomeLib `AZW3` target;
- генерує KFX та AZW8.

Тому **заборонено** прив'язувати `fb2cng` target до існуючого MyHomeLib `MOBI` або `AZW3` лише через legacy назву `fb2mobi`.

Якщо хочемо використовувати Kindle output fb2cng, додати нові формати:

```java
KFX,
AZW8
```

окремою майбутньою зміною UI/domain/export profiles.

---

## 11. Multi-step route planner

Route planner повинен працювати як graph, але без довільного нескінченного пошуку.

Наприклад:

```text
FB2 -> EPUB3 -> DOCX
MOBI -> EPUB -> FB2
AZW3 -> EPUB -> TXT
```

Кожний edge має:

- engine;
- source format;
- target format;
- quality rank;
- lossy flag;
- cost rank;
- availability.

Перший алгоритм:

1. direct same-format;
2. direct specialized engine;
3. direct general engine;
4. max 2-step chain;
5. Calibre fallback.

Не дозволяти chains > 2 intermediate conversions у v1, щоб уникнути накопичення втрат.

---

## 12. Quality ranking

Приклад:

```text
FB2 -> EPUB
1. fb2cng       quality 100
2. Pandoc       quality 60
3. Calibre      fallback
```

```text
EPUB -> DOCX
1. Pandoc       quality 100
2. Calibre      fallback
```

```text
MOBI -> EPUB
1. libmobi      quality 100
2. Calibre      fallback
```

Quality ranking має бути hard-coded policy/tested configuration, а не випадковим порядком beans.

---

## 13. Failure/fallback policy

Не всі failures повинні автоматично запускати інший engine.

Класифікація:

```text
UNAVAILABLE
UNSUPPORTED
INVALID_INPUT
SECURITY_REJECTED
TIMEOUT
CANCELLED
ENGINE_CRASH
OUTPUT_INVALID
IO_FAILURE
```

Rules:

- `UNAVAILABLE` -> можна перейти до наступного route;
- `UNSUPPORTED` -> можна перейти;
- `INVALID_INPUT` -> STOP;
- `SECURITY_REJECTED` -> STOP;
- `CANCELLED` -> STOP;
- `TIMEOUT` -> STOP by default, optional user fallback later;
- `ENGINE_CRASH` -> STOP + diagnostics;
- `OUTPUT_INVALID` -> STOP + diagnostics;
- `IO_FAILURE` -> STOP.

Так ми не маскуємо наші або upstream bugs Calibre-ом.

---

## 14. Security requirements

### Загальні

- тільки `ProcessBuilder(List<String>)`;
- ніяких shell command strings;
- окремий temp workspace на job;
- source temp path normalized;
- target temp path owned by MyHomeLib;
- max input already enforced application layer;
- monitor output size while process працює;
- timeout;
- cancellation;
- kill descendants;
- bounded stdout/stderr capture;
- logs без secrets;
- cleanup even on crash.

### Pandoc

Mandatory:

```text
--sandbox
+RTS -M512M -RTS
```

Заборонено в v1:

- `--filter`;
- `--lua-filter`;
- custom writer/reader;
- `--pdf-engine`;
- user-provided arbitrary CLI args.

### fb2cng

- source даємо як один temp FB2;
- не передаємо arbitrary config paths від книги;
- bundled controlled config only;
- timeout/output size monitor;
- external CSS file references не повинні мати доступ поза workspace.

### libmobi

- запускати окремим process;
- output тільки в temp workspace;
- не підтримувати DRM cracking workflow у MyHomeLib;
- encrypted inputs -> explicit unsupported/error unless user legally provides supported data through separate future feature.

---

## 15. Output validation

Після engine exit code 0 результат ще НЕ вважається success.

### EPUB

Перевірити:

- ZIP signature;
- no traversal entries;
- `mimetype`;
- `META-INF/container.xml`;
- OPF path exists;
- manifest/spine parse;
- package size;
- reopen existing MyHomeLib EPUB reader;
- для canonical/optimized EPUB — запуск EPUBCheck як release/acceptance validator;
- для optimized EPUB — semantic before/after comparison: metadata, spine order, TOC, chapter text hashes, resource references.

EPUBCheck входить у цільову архітектуру Optimizer і не вважається «необов'язковим покращенням»: мінімальний структурний validator працює завжди, а повний EPUBCheck є обов'язковим для release fixtures та режиму `STRICT`.

### FB2

- secure XML parse;
- FictionBook root;
- no external entities;
- title/body consistency;
- existing MyHomeLib FB2 reader reopen.

### PDF

- `%PDF-`;
- EOF / minimal PDFBox validation later;
- non-zero pages when parser available.

### TXT

- valid UTF-8;
- non-empty unless source truly empty.

### DOCX

- ZIP integrity;
- `[Content_Types].xml`;
- `word/document.xml`.

---

## 16. Metadata policy

Через multi-engine routing metadata треба контролювати MyHomeLib-ом.

Перед conversion створити canonical metadata snapshot з `Book`:

- title;
- authors;
- genres;
- series;
- sequence;
- annotation;
- language;
- ISBN;
- year;
- publisher;
- translators;
- cover where available.

Після conversion:

- перевірити, що критичні fields не зникли;
- для Pandoc FB2 output додати окремий lightweight `Fb2MetadataPatcher`, тому що Pandoc writer не зберігає всю MyHomeLib metadata модель;
- не переписувати metadata, якщо source має richer value і Book domain його не знає — правило merge, а не blind overwrite.

---

## 17. Settings UI

Новий розділ:

```text
Конвертація
  Режим: Автоматично / Toolchain / Calibre / Вручну

  fb2cng: [знайдено / версія / шлях]
  Pandoc: [знайдено / версія / шлях]
  libmobi: [знайдено / версія / шлях]
  Calibre: [знайдено / версія / шлях]

  [Перевірити конвертери]
```

Не показувати enum names `AUTO`, `TOOLCHAIN`, `CALIBRE` напряму — тільки language keys.

Diagnostics:

- executable path;
- version;
- supported capabilities;
- last probe time;
- last error.

---

## 18. Packaging strategy

### Варіант A — bundled tools

Перевага: працює з коробки.

Недолік: розмір + GPL/LGPL distribution compliance.

### Варіант B — optional Converter Pack

Рекомендований перший release:

```text
MyHomeLib-Converter-Pack-Windows-x64.zip
```

Містить:

- `fbc.exe`;
- `pandoc.exe`;
- `mobitool.exe` (+ runtime DLL if required);
- licenses/notices;
- version manifest;
- SHA-256 manifest;
- corresponding-source references/archive policy.

MyHomeLib працює без pack, але capabilities стають доступними після його встановлення.

Це краще, ніж знову вкладати 190+ MiB Calibre runtime в основний ZIP.

---

## 19. Що робимо завтра — точний порядок

### Gate 0 — baseline

1. Розпакувати corrected R4 developer source.
2. Clean build із окремого Maven repo.
3. Зафіксувати baseline tests.
4. Не змінювати UI/reader до зеленої baseline.

### Gate 1 — SPI priority

1. Додати `BookConverter.priority()`.
2. Додати deterministic sorting.
3. Unit tests:
   - same format > toolchain;
   - toolchain > Calibre;
   - stable tie by id.

### Gate 2 — toolchain skeleton

Створити `myhomelib-converter-toolchain`.

Реалізувати:

- discovery;
- version probes;
- shared external process runner;
- workspace;
- failures;
- validation SPI;
- route planner.

### Gate 3 — fb2cng

Перший реально корисний engine.

Реалізувати:

```text
FB2 -> EPUB3
FB2 -> KEPUB
FB2 -> PDF
FB2 -> TXT
```

Прогнати на двох реальних FB2 архівах користувача.

Acceptance:

- title;
- authors;
- series;
- genres;
- annotation;
- cover;
- chapter count;
- TOC;
- images;
- Unicode;
- footnotes.

### Gate 4 — Pandoc

Реалізувати direct routes:

```text
EPUB -> FB2
EPUB -> DOCX
EPUB -> RTF
EPUB -> TXT
DOCX -> EPUB
DOCX -> FB2
RTF -> EPUB
TXT -> EPUB
HTML -> EPUB
```

Mandatory `--sandbox`.

### Gate 5 — libmobi

Реалізувати:

```text
MOBI/PRC/AZW/AZW3/AZW4 -> EPUB
```

Потім 2-step routes:

```text
MOBI -> EPUB -> FB2
MOBI -> EPUB -> DOCX
MOBI -> EPUB -> TXT
AZW3 -> EPUB -> FB2
```

### Gate 6 — fallback

Calibre лишити тільки після toolchain routes.

Перевірити, що unsupported legacy pair досі працює через Calibre.

### Gate 7 — UI

1. Converter diagnostics.
2. Provider mode.
3. Capability matrix.
4. Localized names only.

### Gate 8 — regression

- full Maven tests;
- real JavaFX gate;
- conversion matrix tests;
- process timeout/cancel tests;
- corrupted input tests;
- giant output tests;
- path traversal tests;
- Unicode Windows paths.

---

## 20. Реальні test fixtures

Обов'язково використати:

- `Возвращение_Великого_Я_великий_друид_которому_400_лет!_1_15.zip`;
- `Хранитель Древа Мира 1-8.fb2.zip`;
- libmobi sample MOBI files;
- Pandoc EPUB fixtures;
- malformed FB2/EPUB fixtures;
- Cyrillic filenames;
- `#Uhhhh` archive entry edge case.

---

## 21. Acceptance criteria v1

Toolchain v1 готовий, якщо:

1. MyHomeLib стартує без зовнішніх converters.
2. Capability matrix змінюється залежно від installed tools.
3. FB2 -> EPUB3 через `fbc` проходить на реальних книгах.
4. FB2 -> PDF через `fbc` проходить.
5. EPUB -> FB2/DOCX/TXT через Pandoc проходить.
6. MOBI/AZW3 -> EPUB через libmobi проходить.
7. Multi-step MOBI -> EPUB -> FB2 проходить.
8. Calibre fallback не зламаний.
9. Invalid input не маскується fallback-ом.
10. Cancel kill-ить process tree.
11. Timeout працює.
12. Output limit працює.
13. Unicode Windows paths працюють.
14. No raw enum / technical process error leak у UI.
15. Full MyHomeLib regression green.

---

## 22. Що НЕ робимо завтра

Не робимо:

- власний EPUB parser/writer з нуля;
- власний FB2 parser/writer з нуля;
- власний MOBI/KF8 writer;
- JNI/JNA libmobi integration;
- власний PDF renderer;
- KFX implementation;
- DRM circumvention;
- довільні Pandoc filters;
- arbitrary user CLI args;
- 20-format full replacement Calibre за один день.

---

## 23. Наступна фаза після v1

### Phase 2

Додати:

- `kepubify` для EPUB -> KEPUB;
- EPUBCheck;
- optional OpenHTMLtoPDF/PDFBox для general PDF output;
- HTMLZ/TXTZ packaging;
- KFX/AZW8 як окремі export formats.

### Phase 3

Проаналізувати реальну статистику usage.

Якщо 95% conversion jobs уже проходять без Calibre — перестати bundled-ити Calibre і зробити його optional compatibility pack.

### Phase 4

Лише якщо з'явиться конкретна причина прибирати сторонній engine:

- реалізувати власний normalized publication model;
- поступово замінювати конкретний provider;
- differential tests проти старого engine.

Тобто власний rewrite стає **еволюційним**, а не big-bang.

---

## 24. Підсумкове рішення

### Переписати все з нуля?

**Ні.**

### Копіювати вихідний код fb2cng/Pandoc в MyHomeLib?

**Ні.**

### Використати готові engines як окремі executables?

**Так.**

### Що саме беремо

- `fb2cng` — primary FB2 conversion engine;
- `Pandoc` — general EPUB/DOCX/HTML/RTF/TXT bridge;
- `libmobi` — MOBI/AZW/KF8 input -> EPUB;
- `Calibre` — compatibility fallback.

### Що пишемо самі

**MyHomeLib Conversion Toolchain:** маршрутизацію, capabilities, безпеку, процеси, multi-step conversion, валідацію, metadata policy, provider priority, diagnostics, UI і тести.

Це дає найбільше покриття за мінімальний час і залишає нам контроль над продуктом без повторного написання вже добре вирішених форматних задач.


---

# Частина II. Library Organizer — детальний план

## 17. Мета Organizer

Library Organizer має перетворювати довільну папку або керовану колекцію книг на передбачувану структуру без втрати даних.

Основні задачі:

1. рекурсивно знайти книжкові файли;
2. визначити реальний формат за сигнатурою, а не лише розширенням;
3. витягнути локальні метадані;
4. нормалізувати автора, назву, серію, номер серії та мову;
5. знайти точні й потенційні дублікати;
6. визначити канонічний формат;
7. за потреби викликати Conversion Toolchain;
8. сформувати майбутній каталог і назви файлів;
9. показати повний dry-run `було -> стане`;
10. виконати зміни crash-safe;
11. перевірити хеші/результати;
12. оновити каталог MyHomeLib;
13. записати журнал і забезпечити rollback.

Organizer **не повинен** виконувати необоротні дії без попереднього плану операції.

---

## 18. Спільний фундамент Conversion Toolchain + Organizer

Ці компоненти розробляються один раз і використовуються обома підсистемами.

### 18.1. Format Detection

Використати й розширити наявний `BookFormatDetector` та реєстри Reader.

Потрібна модель:

```java
record DetectedBookFormat(
    String format,
    String mediaType,
    DetectionConfidence confidence,
    String extension,
    boolean archive,
    String reason
) {}
```

Пріоритет визначення:

1. magic/signature;
2. структура контейнера;
3. MIME/OPF/FB2 XML root;
4. extension;
5. UNKNOWN.

Не дозволяти мовчазно трактувати `book.pdf` як PDF лише через ім'я, якщо magic не збігається.

### 18.2. Metadata Extraction

Створити provider-neutral SPI поверх уже наявного `LocalMetadataExtractor`:

```java
interface BookMetadataExtractor {
    boolean supports(DetectedBookFormat format);
    ExtractedBookMetadata extract(PathOrBookSource source, MetadataExtractionContext context);
}
```

`ExtractedBookMetadata`:

```text
rawTitle
rawAuthors[]
rawSeries
rawSeriesNumber
language
isbn
publisher
year
annotation
genres[]
coverPresent
sourceFormat
sourceConfidence
warnings[]
```

Джерела метаданих за пріоритетом:

1. внутрішні метадані FB2/EPUB/MOBI/DOCX;
2. каталог MyHomeLib, якщо файл уже відомий;
3. ім'я файла/шлях;
4. зовнішні metadata providers — лише за явною опцією користувача.

### 18.3. Metadata Normalization

Створити `MetadataNormalizationService`.

Він не змінює оригінальні метадані без збереження raw-значень.

Результат:

```java
record NormalizedBookIdentity(
    String title,
    List<NormalizedAuthor> authors,
    String series,
    BigDecimal seriesNumber,
    String language,
    String isbn,
    int confidence,
    List<NormalizationIssue> issues
) {}
```

Спільна модель потрібна і Conversion Toolchain, і Organizer, і duplicate engine.

### 18.4. Hashing / fingerprinting

Спільний `BookFingerprintService`:

- SHA-256 фізичного файла;
- SHA-256 розпакованого payload там, де це доречно;
- normalized-text fingerprint для reflowable formats;
- optional image/cover fingerprint;
- metadata identity key.

Не читати один і той самий великий файл багато разів: detector, hash та extractor мають використовувати один bounded scan, якщо формат дозволяє.

### 18.5. Safe staging

Один `ConversionWorkspace` / `LibraryOperationWorkspace`:

```text
<temp>/myhomelib/<operation-id>/
    input/
    output/
    backup/
    logs/
    manifest.json
```

Всі зовнішні converters працюють лише з staging paths.

### 18.6. Safe filename/path

Розширити вже наявний `SafeFileNameSupport`.

Потрібні окремі API:

```java
sanitizeFileStem(...)
sanitizeDirectoryName(...)
truncatePreservingSuffix(...)
resolveCollision(...)
validateRelativePath(...)
```

Обов'язково:

- Windows reserved names;
- Unicode;
- trailing dots/spaces;
- control characters;
- path traversal;
- максимальна довжина компонента;
- загальна довжина Windows path;
- case-insensitive collisions;
- Unicode-normalization collisions.

### 18.7. Operation journal

Conversion та Organizer мають писати єдиний журнал технічних операцій.

Для Organizer потрібні item-level записи:

```text
operationId
itemId
sourcePath
sourceSha256
plannedTargetPath
plannedTargetFormat
actualTargetPath
actualSha256
action
status
error
backupPath
startedAt
completedAt
```

Для керованої колекції журнал зберігати в БД; для зовнішньої папки додатково мати crash-recovery manifest у workspace.

---

## 19. Нормалізація авторів

### 19.1. Що виправляємо автоматично

- повторні пробіли;
- пробіли на початку/кінці;
- NBSP -> звичайний пробіл;
- Unicode NFC;
- випадкові подвійні коми/крапки;
- `Прізвище, Ім'я` -> структурована модель `family/given/middle`, якщо розбір однозначний;
- однакові записи з різним whitespace/case;
- порожні author entries.

### 19.2. Що не виправляємо автоматично

- транслітерацію імен;
- переклад імен;
- злиття `J. R. R. Tolkien` і `John Ronald Reuel Tolkien` лише за fuzzy match;
- зміну порядку імені/прізвища, якщо немає достатньої впевненості;
- псевдонім -> справжнє ім'я.

Такі випадки показуються як suggestions.

### 19.3. Canonical display name

За замовчуванням:

```text
Ім'я По батькові Прізвище
```

Але шаблон каталогів може використовувати:

```text
Прізвище Ім'я
Прізвище, Ім'я
```

без зміни canonical metadata.

### 19.4. Кілька авторів

Політика директорії налаштовується:

- перший автор;
- `Автор 1 & Автор 2` до N=2;
- `_Колектив авторів` для >2;
- окрема директорія `Антології` для збірників.

Default: перший автор + metadata всіх авторів у книзі.

---

## 20. Нормалізація назв

Автоматично:

- trim;
- whitespace collapse;
- NFC;
- remove duplicated title prefix from filename;
- normalize typographic quotes optionally;
- replace filesystem-forbidden chars лише у filename, а не в metadata;
- не переводити регістр усієї назви автоматично.

Не робити title-case автоматично для української/російської/англійської: це легко псує абревіатури й власні назви.

---

## 21. Нормалізація серій

### 21.1. Canonical series

Прибираємо:

- зайві пробіли;
- Unicode-equivalent punctuation;
- службові префікси на кшталт `Серия:` лише якщо вони точно є label;
- дубльовані номерні suffix у назві серії.

### 21.2. Номер серії

Внутрішньо зберігати `BigDecimal`, не `int`, щоб підтримати:

```text
1
1.5
2
10
```

Розпізнавати шаблони:

```text
#3
№ 3
[03]
(3)
Book 3
Книга 3
Том 3
3. Назва
```

Але результат із filename має нижчу confidence, ніж metadata FB2/EPUB.

### 21.3. Невідомий номер

Не вигадувати `00`.

Шлях:

```text
Автор/Серія/Назва.epub
```

або окрема група `Без номера` — configurable.

---

## 22. Canonical filesystem layout

### 22.1. Default шаблон

Для книги в серії:

```text
{Author}/{Series}/{SeriesNo:00} - {Title}.{ext}
```

Без серії:

```text
{Author}/{Title}.{ext}
```

Для невідомого автора:

```text
_Невідомий автор/{Title}.{ext}
```

### 22.2. Template engine

Поля:

```text
{Author}
{FirstAuthor}
{Authors}
{Title}
{Series}
{SeriesNo}
{SeriesNo:00}
{Year}
{Language}
{ISBN}
{Format}
{Id}
```

Умовні сегменти:

```text
{Author}/{?Series:{Series}/{SeriesNo:00} - }{Title}.{ext}
```

Template compile/validation виконується до сканування.

### 22.3. Collision policy

Якщо target уже існує:

1. той самий SHA-256 -> exact duplicate;
2. той самий semantic fingerprint -> equivalent duplicate;
3. різний файл -> conflict.

Для conflict **не перезаписувати**.

Варіанти:

```text
Назва (2024).epub
Назва [ISBN].epub
Назва [a1b2c3d4].epub
```

Default suffix — короткий stable hash, якщо metadata не дають природного disambiguator.

---

## 23. Політика форматів

### 23.1. Canonical storage format

Для reflowable books default:

```text
EPUB3
```

### 23.2. Рекомендована матриця

| Source | Canonical action | Engine |
|---|---|---|
| EPUB2/EPUB3 | зберегти EPUB; validate/normalize; за policy optimize/repack | native validator + EPUB Optimizer |
| FB2 | -> EPUB3 | fb2cng |
| FB2.ZIP | extract/stage -> EPUB3 | native archive + fb2cng |
| MOBI/PRC | -> EPUB3 | libmobi; Calibre fallback |
| AZW/AZW3/KF8 | -> EPUB3 | libmobi; Calibre fallback |
| DOCX | -> EPUB3 | Pandoc |
| RTF | -> EPUB3 | Pandoc |
| HTML/HTMLZ | -> EPUB3 | Pandoc/native archive |
| TXT/Markdown | -> EPUB3 | Pandoc |
| PDF text/fixed-layout | **залишити PDF** | no conversion default |
| scanned PDF | **залишити PDF** | OCR не входить у v1 |
| DJVU/CHM/LIT/LRF/PDB/RB/SNB/TCR | зберегти або Calibre fallback за policy | Calibre |
| KEPUB | не використовувати як canonical; derive для Kobo | fb2cng/kepubify later |
| KFX/AZW8 | device derivative, не canonical | fb2cng |

### 23.3. Original retention

Default:

```text
convert -> verify -> keep original
```

Після acceptance користувач може обрати:

- KEEP_ORIGINAL;
- MOVE_ORIGINAL_TO_ARCHIVE;
- DELETE_ORIGINAL_AFTER_VERIFIED_CONVERSION.

Третій режим повинен бути opt-in із додатковим підтвердженням.

### 23.4. PDF policy

PDF не перетворюємо автоматично в EPUB.

Причини:

- fixed layout;
- scans;
- multi-column layout;
- lost reading order;
- footnotes/headers/page numbers.

У preview показувати:

```text
PDF: збережено без конвертації (рекомендовано)
```

### 23.5. EPUB optimization policy

Після отримання canonical EPUB3 Organizer не повинен просто залишати байти, які видав сторонній converter.

Порядок:

```text
source
  -> conversion
  -> structural validation
  -> EPUB resource analysis
  -> safe optimization
  -> OCF repack
  -> EPUBCheck / reader reopen
  -> semantic comparison
  -> accept optimized output only if valid
```

Default policy:

```text
BALANCED_EPUB33
```

У першій production-версії мають бути профілі:

- `NONE` — лише validation, без зміни байтів;
- `COMPATIBLE_EPUB33` — максимально консервативна lossless optimization;
- `BALANCED_EPUB33` — рекомендований режим для бібліотеки;
- `MAXIMUM_EPUB33` — максимально можливе стиснення, але тільки EPUB 3.3-сумісними codec/container rules;
- `MODERN_EPUB34` — opt-in: може використовувати AVIF/JPEG XL і ніколи не є default, доки EPUB 3.4 не стане стабільною Recommendation і поки reading-system profile не підтвердить підтримку.

Organizer зберігає `optimizationProfile`, `sizeBefore`, `sizeAfter`, `bytesSaved`, `percentSaved`, список змінених resource types та validation result у journal.

---

## 24. Duplicate engine для Organizer

### 24.1. Рівень A — exact bytes

```text
SHA-256 однаковий
```

Можна автоматично позначити exact duplicate.

### 24.2. Рівень B — exact content

Для контейнерів, які можуть відрізнятися timestamps/ZIP compression:

```text
normalized content fingerprint однаковий
```

Потрібне підтверджене format-specific fingerprinting.

### 24.3. Рівень C — strong metadata identity

Наприклад:

```text
normalized author + title + ISBN
```

або

```text
normalized author + title + series + number + very high text similarity
```

Не видаляти автоматично; можна auto-group для review.

### 24.4. Рівень D — fuzzy candidate

Levenshtein/Jaro-Winkler/token similarity + existing `FuzzyDuplicateDetector`.

Тільки suggestion.

### 24.5. Вибір preferred artifact

Default для однієї книги:

1. verified EPUB3;
2. original FB2;
3. AZW3/MOBI;
4. PDF — окрема паралельна representation, якщо layout важливий;
5. TXT/RTF/HTML derivatives.

Не вважати PDF автоматично гіршим артефактом: він може бути єдиним оригінальним scan/fixed-layout виданням.

---

## 25. Analyze -> Plan -> Apply pipeline

### 25.1. ANALYZE

Нічого не змінює.

Для кожного файла:

```text
scan
format detect
metadata extract
normalize
hash/fingerprint
match duplicates
conversion capability
canonical path calculate
warnings
```

Результат — immutable `OrganizerAnalysisItem`.

### 25.2. PLAN

`LibraryOrganizationPlanner` формує `OrganizationPlan`.

```java
record OrganizationPlan(
    UUID operationId,
    Instant createdAt,
    OrganizerPolicy policy,
    List<OrganizationAction> actions,
    PlanStatistics statistics,
    List<PlanIssue> issues
) {}
```

Типи action:

```text
NOOP
RENAME
MOVE
MOVE_AND_RENAME
CONVERT
CONVERT_AND_MOVE
KEEP_AS_IS
ARCHIVE_ORIGINAL
DELETE_EXACT_DUPLICATE
REVIEW_DUPLICATE
SKIP_UNSUPPORTED
CONFLICT
ERROR
```

### 25.3. PREVIEW

Жоден destructive action не виконується до preview.

Обов'язкові колонки:

```text
✓
Статус
Поточний шлях
Автор
Серія
№
Назва
Формат зараз
Дія
Формат після
Новий шлях
Дублікат
Впевненість
Попередження
```

Фільтри:

```text
лише помилки
лише конфлікти
лише конвертація
лише дублікати
низька confidence
PDF
невідомий автор
невідома серія
```

### 25.4. APPLY

Фази:

```text
1. preflight
2. create workspace
3. conversions to staging
4. validate staged outputs
5. copy/move to target temporary names
6. fsync/size/hash verify where supported
7. atomic rename to final names
8. update DB/catalog
9. archive/delete originals according to policy
10. finalize journal
11. cleanup workspace
```

Не оновлювати БД так, ніби файл уже переміщений, до успішної filesystem finalization.

---

## 26. Filesystem transaction semantics

### 26.1. Same filesystem

Використовувати atomic move, якщо підтримується.

### 26.2. Cross-volume

```text
copy -> verify SHA-256 -> final rename -> DB commit -> optional source delete
```

Ніколи:

```text
copy -> delete source -> verify
```

### 26.3. Crash recovery

Після restart:

- знайти незавершені operation manifests;
- перевірити source/target hashes;
- запропонувати `Продовжити`, `Відкотити`, `Завершити вручну`;
- не видаляти файли автоматично при неоднозначному стані.

---

## 27. Rollback

Rollback підтримується для:

- rename;
- move;
- move+rename;
- newly created converted file;
- archive-original;
- metadata catalog update.

Для `DELETE_ORIGINAL_AFTER_VERIFIED_CONVERSION` rollback можливий лише якщо перед видаленням створено quarantine/backup. Тому фізичне permanent delete у v1 краще не робити; переміщати у `.myhomelib-trash/<operation-id>` і чистити окремою командою після retention period.

---

## 28. Managed collection vs external folder

### 28.1. External folder mode

Користувач вибирає довільну папку, ще не імпортовану в MyHomeLib.

Після apply:

- організована структура створюється у вибраному destination root;
- можна автоматично створити/оновити collection;
- source root не змінюється до успішного завершення output.

### 28.2. Managed collection mode

Для існуючої колекції:

- блокувати конфліктуючі library operations через наявний `LibraryOperationCoordinator`;
- оновлювати `BookFile` / artifacts у тій самій operation boundary;
- після apply запускати index/update jobs лише для змінених книг;
- content/Lucene index не перебудовувати повністю без потреби.

---

## 29. Archive handling

### 29.1. ZIP з однією книгою

Можна трактувати як container artifact.

Для FB2.ZIP:

- safe extract entry у staging;
- convert;
- original ZIP за retention policy.

### 29.2. ZIP з багатьма книгами

Не робити вигляд, що це одна книга.

Analyzer має створити кілька logical candidates і показати:

```text
Архів містить 15 книг
```

У v1 multi-book archive можна:

- розпакувати у staging;
- організувати кожну книгу окремо;
- original archive залишити/архівувати за policy.

### 29.3. RAR/7z

Поки лише через наявні/зовнішні archive capabilities; не додавати новий небезпечний unpacker без security limits.

---

## 30. UI — «Упорядкувати бібліотеку»

### 30.1. Wizard

Крок 1 — Джерело:

```text
Поточна колекція
Інша папка
```

Крок 2 — Правила:

```text
Структура каталогів
Шаблон імені файла
Основний формат
Що робити з PDF
Що робити з оригіналами
Дублікати
Мова/metadata normalization
```

Крок 3 — Аналіз:

```text
progress
files scanned
metadata extracted
hashes calculated
warnings/errors
```

Крок 4 — Preview:

таблиця плану + фільтри + ручне виключення item.

Крок 5 — Виконання:

```text
processed/total
converted
moved
skipped
conflicts
errors
current file
cancel
```

Крок 6 — Result:

```text
summary
open destination
show errors
export report
undo operation
```

### 30.2. Ніяких raw enum

Усі `OrganizationAction`, `DuplicateKind`, `ConversionProvider`, statuses — тільки через i18n converter/renderer.

Це повинно бути покрито тим самим UI guard, який уже додано в R4.

---

## 31. Налаштування Organizer

`OrganizerProfile`:

```java
record OrganizerProfile(
    String id,
    String name,
    String directoryTemplate,
    String filenameTemplate,
    String canonicalReflowableFormat,
    PdfPolicy pdfPolicy,
    OriginalRetentionPolicy originalRetention,
    DuplicatePolicy duplicatePolicy,
    boolean normalizeMetadata,
    boolean validateConvertedFiles,
    boolean useExternalMetadataProviders,
    int maxParallelAnalysis,
    int maxParallelConversions
) {}
```

Профілі:

```text
Безпечний
EPUB-бібліотека
Зберігати оригінали
Тільки перейменування
Тільки аналіз
```

---

## 32. Parallelism і ресурси

### Аналіз

Можна паралельно:

- signature detection;
- metadata extraction;
- hashing.

Але bounded executor.

Default:

```text
analysis threads = min(4, availableProcessors)
conversion threads = 1 або 2
```

Зовнішні converters не запускати десятками одночасно.

PDF/Pandoc/fb2cng можуть споживати багато RAM.

### Backpressure

Черга conversion tasks bounded.

При low disk space операцію зупинити **до** створення output.

---

## 33. Performance strategy для великих бібліотек

Ціль v1:

```text
100 000+ filesystem entries
```

Не тримати в пам'яті full file contents.

Потрібні:

- streaming directory walk;
- batch DB writes;
- bounded result pages;
- incremental UI rows;
- file-stat cache;
- hash cache `(path, size, mtime, fileKey)`;
- повторний запуск не повинен перехешовувати незмінені 100 тис. файлів.

Але cache не використовується як доказ при destructive duplicate deletion без actual/strong verification.

---

## 34. Security requirements Organizer

1. source root canonicalization;
2. destination root canonicalization;
3. target path must remain under destination root;
4. no symlink traversal by default;
5. junction/reparse-point awareness on Windows;
6. archive traversal protection;
7. zip bomb limits;
8. external converters only in isolated workspace;
9. no shell command string concatenation;
10. ProcessBuilder argument arrays;
11. process timeout;
12. process-tree kill;
13. max input/output size;
14. low-disk-space preflight;
15. never overwrite unexpected existing path;
16. SHA-256 verification before source removal.

---

## 35. Database / catalog changes

Не створювати окрему другу бібліотечну БД.

Використати існуючу модель books/artifacts/history.

Потрібно додати через наступну вільну Flyway migration:

```text
library_organization_operations
library_organization_items
```

або розширити існуючий operation-history механізм, якщо він покриває item-level payload без порушення його контракту.

Перед migration implementation спочатку перевірити актуальний latest migration number у corrected source; номер не hard-code у плані.

### operation table

```text
id
collection_id
profile_id
source_root
destination_root
state
started_at
completed_at
undo_deadline
summary_json
```

### item table

```text
operation_id
item_id
book_id
source_path
target_path
source_sha256
target_sha256
action
state
backup_path
error_code
error_message
```

---

## 36. Domain boundaries

Не класти Organizer filesystem logic у UI.

Пропоновані класи application layer:

```text
AnalyzeLibraryOrganizationUseCase
PlanLibraryOrganizationUseCase
ApplyLibraryOrganizationUseCase
UndoLibraryOrganizationUseCase
GetLibraryOrganizationStatusUseCase
```

Domain/application services:

```text
MetadataNormalizationService
CanonicalBookPathService
DuplicateDecisionService
CanonicalFormatPolicy
LibraryOrganizationPlanner
```

Ports:

```text
BookFilesystemPort
BookFingerprintPort
LocalBookMetadataPort
BookConversionPort / existing BookConverter integration
OrganizationJournalPort
DiskSpacePort
```

Infrastructure:

```text
NioBookFilesystemAdapter
Sha256BookFingerprintAdapter
SqliteOrganizationJournalAdapter
ToolchainBookConverter
```

---

## 37. Що робимо одночасно з Conversion Toolchain

### Потік A — спільна основа

Це треба реалізувати **першим**, і воно одночасно працює для Converter та Organizer:

1. converter provider priority;
2. format/capability registry;
3. external process runner;
4. tool discovery/version checks;
5. staging workspace;
6. timeout/cancellation/process-tree kill;
7. output validation;
8. common error model;
9. SHA-256/fingerprints;
10. safe filename/path helpers.

### Потік B — Conversion Toolchain

Може розроблятись незалежно після API з Потоку A:

1. fb2cng provider;
2. Pandoc provider;
3. libmobi provider;
4. multi-step route graph;
5. Calibre fallback;
6. validation;
7. capability UI.

### Потік C — Organizer analysis/planning

Може йти **паралельно з B**, не чекаючи завершення всіх converters:

1. scanner;
2. metadata extraction;
3. normalization;
4. naming templates;
5. canonical path planner;
6. duplicate classification;
7. dry-run model;
8. preview UI.

На цьому етапі conversion action може бути лише `PLANNED`, без виконання.

### Потік D — Organizer apply

Починається після стабілізації A + базового B:

1. conversion execution;
2. transactional moves;
3. DB update;
4. journal;
5. rollback;
6. recovery.

### Потік E — UI

UI можна почати паралельно після стабілізації DTO/use-case contracts, але не раніше.

### Потік F — EPUB Optimizer / Maximum Compression

Може стартувати **паралельно з Потоками B і C**, щойно є один стабільний EPUB fixture і перший маршрут `FB2 -> EPUB3`.

Першими йдуть компоненти, що не залежать від image codec executables:

1. `EpubResourceGraphAnalyzer`;
2. duplicate/orphan resource detection;
3. deterministic OCF repack;
4. per-entry Store/Deflate policy;
5. `mimetype` compliance;
6. XHTML/CSS safe cleanup;
7. size accounting;
8. before/after semantic verifier.

Після цього паралельно можна додати:

- JPEG/PNG optimization;
- WebP policy;
- font subsetting;
- 7-Zip Deflate `-mx=9` backend;
- Zopfli-compatible maximum Deflate backend;
- modern EPUB 3.4 codec pack: AVIF / JPEG XL.

Optimizer не блокує розробку Organizer Analyze/Preview. Він потрібен до production `Apply`, якщо policy користувача вимагає оптимізацію canonical EPUB.

---

## 38. Інтегрований порядок реалізації

### Етап 0 — Baseline

- corrected R4 source;
- clean build із чистим local Maven repo;
- зафіксувати test baseline;
- перевірити latest Flyway migration;
- створити feature branch/worktree.

**Gate:** clean reactor build зелений.

### Етап 1 — Conversion/Organizer shared foundation

- priority у `BookConverter` selection;
- ToolRegistry;
- SafeExternalProcessRunner;
- workspace;
- error taxonomy;
- validators;
- format detector consolidation;
- fingerprint API;
- filename/path policy.

**Цей етап одночасно закриває фундамент двох підсистем.**

### Етап 2A — fb2cng

- discovery;
- version;
- capability matrix;
- FB2 -> EPUB3/PDF/TXT/KEPUB;
- cancellation;
- errors;
- real FB2 fixtures.

### Етап 2B — Organizer analyzer

Паралельно з 2A:

- recursive scanner;
- metadata extraction;
- normalization;
- hash cache;
- identity model;
- path templates;
- dry-run DTO.

### Етап 3A — Pandoc + libmobi

- Pandoc routes;
- sandbox;
- DOCX/RTF/HTML/TXT;
- libmobi MOBI/AZW3 -> EPUB;
- multi-step route graph.

### Етап 3B — Organizer duplicate/plan engine

Паралельно з 3A:

- exact duplicate;
- semantic duplicate;
- fuzzy candidate;
- canonical format decision;
- conflict handling;
- plan statistics.

### Етап 3C — EPUB Optimizer core

Паралельно з 3A/3B:

- EPUB resource graph;
- orphan resource detection;
- exact resource deduplication;
- safe XHTML/CSS normalization;
- deterministic OCF writer;
- `mimetype` first + Store;
- per-entry Store/Deflate selection;
- baseline Deflate-9 backend;
- optimization statistics;
- semantic before/after verifier.

Після core:

- image optimizer provider API;
- JPEG/PNG/WebP;
- font optimizer provider API;
- maximum compression backend;
- EPUB 3.4 modern codec profile.

### Етап 4 — Organizer preview UI

- wizard;
- analysis progress;
- preview table;
- filters;
- rule profiles;
- localization.

Може стартувати ще наприкінці Етапу 3B.

### Етап 5 — Apply engine

- preflight;
- conversions;
- structural validation;
- EPUB optimization/repack відповідно до profile;
- EPUBCheck / reader reopen / semantic verification;
- transactional filesystem operations;
- catalog update;
- operation journal;
- undo/recovery.

### Етап 6 — Calibre fallback / compatibility

- Calibre має найнижчий priority;
- legacy formats;
- comparison tests;
- provider diagnostics.

### Етап 7 — End-to-end hardening

- 10k/100k synthetic files;
- real FB2/EPUB/MOBI/AZW3/DOCX/PDF;
- corrupted files;
- conflicts;
- low disk;
- cancellation;
- crash recovery;
- Unicode/Windows paths;
- long paths;
- archives;
- duplicate sets;
- EPUB із великими JPEG/PNG/font resources;
- вже добре стиснені EPUB, де optimizer не повинен збільшувати файл;
- corrupted resource references;
- WebP compatibility fixtures;
- JPEG XL / AVIF fixtures лише для `MODERN_EPUB34`.

### Етап 8 — Windows acceptance/release

- реальні Windows binaries;
- converters bundled/discovered;
- DPI/UI;
- filesystem semantics;
- antivirus/process behavior;
- portable install;
- offline mode.

---

## 39. Перший робочий день — конкретний backlog

Якщо починаємо завтра, порядок такий.

### Блок 1 — 0–2 години

1. clean baseline;
2. перевірити converter SPI;
3. додати provider `priority()` або deterministic ranking service;
4. tests на selection;
5. створити ToolRegistry + ExternalToolDescriptor.

### Блок 2 — 2–5 годин

Паралельно за кодовими напрямами:

**Conversion:**

- `Fb2cngBookConverter`;
- tool discovery;
- `fbc --version`;
- FB2 -> EPUB3;
- FB2 -> PDF/TXT capability.

**Organizer:**

- `OrganizerPolicy`;
- `OrganizerAnalysisItem`;
- `MetadataNormalizationService`;
- `CanonicalBookPathService`;
- unit tests для назв/авторів/серій.

### Блок 3 — 5–8 годин

**Conversion:**

- Pandoc provider;
- libmobi provider skeleton;
- route planner.

**Organizer:**

- scanner;
- detector/extractor integration;
- SHA-256;
- exact duplicates;
- dry-run generation.

**EPUB Optimizer — паралельний skeleton:**

- `EpubOptimizationProfile`;
- `EpubResourceGraphAnalyzer`;
- `EpubOptimizationReport`;
- OCF invariant tests (`mimetype` first, Store, no extra field);
- deterministic repack fixture;
- size-before/size-after accounting.

На першому дні image re-encoding ще не є blocker: спочатку доводимо безпечний resource graph і OCF packaging.

### Блок 4 — до завершення дня

- перший E2E:

```text
хаотична папка FB2/EPUB/TXT
    -> analyze
    -> normalize metadata
    -> plan
    -> FB2->EPUB3 через fb2cng
    -> EPUB validate/repack skeleton
    -> canonical paths
    -> preview/result model
```

Першого дня destructive apply можна не вмикати, якщо journal/rollback ще не готові.

**Краще мати правильний dry-run, ніж небезпечний MOVE.**

---

## 40. Acceptance criteria — Conversion Toolchain

1. FB2 -> EPUB3 через fb2cng;
2. FB2 -> PDF/TXT;
3. DOCX/RTF/HTML/TXT -> EPUB через Pandoc;
4. MOBI/AZW3 -> EPUB через libmobi або fallback;
5. multi-step routes детерміновані;
6. Calibre fallback має найнижчий priority;
7. cancellation знищує process tree;
8. output size limits;
9. conversion output validated;
10. technical stderr не показується напряму в UI;
11. усі provider names локалізовані;
12. реальні fixtures проходять reader/open verification;
13. canonical EPUB може пройти optimizer без semantic loss;
14. `mimetype` завжди перший, Store, exact `application/epub+zip`;
15. optimizer ніколи не приймає більший результат, якщо policy не вимагає явного re-encoding;
16. `MAXIMUM_EPUB33` не використовує ZIP methods, відмінні від Store/Deflate;
17. JPEG XL / AVIF активуються тільки через explicit `MODERN_EPUB34`/device profile.

---

## 41. Acceptance criteria — Library Organizer

### Analyze

- 100% source files або класифіковані, або мають явний `UNSUPPORTED/ERROR`;
- жоден файл не змінено;
- format mismatch extension/signature показано;
- metadata confidence показано.

### Plan

- кожен action має source + target + reason;
- немає двох actions на один final path без conflict;
- target paths safe;
- destructive actions явно позначені;
- fuzzy duplicates не видаляються автоматично.

### Apply

- source не видаляється до verified target;
- cross-volume copy перевіряється SHA-256;
- crash-safe journal;
- DB не вказує на неіснуючі files після failure;
- cancellation залишає recovery state;
- undo працює для move/rename/conversion creation.

### Result quality

Для книги в серії після organizer:

```text
Автор/Серія/01 - Назва.epub
```

При цьому reader MyHomeLib відкриває output, metadata/cover/TOC не втрачені, а якщо optimization увімкнена — journal містить before/after size і підтвердження semantic validation.

---

## 42. Тестова матриця Organizer

### Metadata

- Ukrainian names;
- Cyrillic/Latin mix;
- apostrophes;
- hyphenated surnames;
- multiple authors;
- anonymous;
- series decimal numbers;
- missing title;
- title only in filename.

### Filesystem

- Windows reserved names;
- case-only collisions;
- NFC/NFD collision;
- long names;
- Unicode supplementary characters;
- UNC;
- cross-volume;
- read-only target;
- low disk;
- locked file;
- symlink/junction.

### Formats

- FB2;
- FB2.ZIP;
- EPUB2;
- EPUB3;
- MOBI;
- AZW3;
- DOCX;
- RTF;
- TXT UTF-8;
- TXT legacy encoding;
- HTMLZ;
- PDF text;
- PDF scan;
- unknown binary.

### Duplicates

- exact same bytes;
- same EPUB recompressed;
- same title different edition;
- same author/title different language;
- same ISBN different cover;
- series duplicates;
- fuzzy false-positive cases.

### Recovery

Ін'єкція failure після кожної фази:

```text
conversion done
copy done
hash verified
final rename
DB update
original archive
```

Після restart стан має бути однозначно recoverable.

---

## 43. Що НЕ робимо у першій версії Organizer

1. OCR scanned PDF;
2. AI-вгадування автора/серії без підтвердження;
3. автоматичний переклад назв;
4. автоматична транслітерація canonical metadata;
5. permanent delete без quarantine;
6. масове редагування вмісту книги заради «красивої» назви;
7. конвертація PDF -> EPUB за замовчуванням;
8. автоматичне злиття fuzzy duplicates;
9. редагування DRM-protected content;
10. background organization без явного запуску користувачем.

---

## 44. Що можна буде додати після v1

- metadata rules editor;
- regex rules;
- manual author alias dictionary;
- series alias dictionary;
- online metadata reconciliation;
- ISBN lookup;
- OCR plugin;
- duplicate merge assistant;
- scheduled incoming-folder organizer;
- watch folder;
- device-specific derivative generation;
- hardlink/reflink optimization;
- content-based language detection;
- automatic cover quality selection;
- per-device codec capability database;
- JPEG XL / AVIF auto-selection після стабілізації EPUB 3.4;
- perceptual image quality gate;
- batch re-optimization existing EPUB library;
- optional Zopfli tuning presets.

---

## 45. Архітектурне рішення для основних підсистем

Не робимо два незалежні проєкти.

Фінальна схема:

```text
                         MyHomeLib UI
                              |
          +-------------------+-------------------+
          |                                       |
  Conversion Use Cases                    Organizer Use Cases
          |                                       |
          +-------------------+-------------------+
                              |
                     Shared Book Services
       +----------------------+----------------------+
       |                      |                      |
 Format/Metadata       Hash/Duplicate         Safe FS/Journal
       |                      |                      |
       +----------------------+----------------------+
                              |
                     Conversion Toolchain
          +-----------+----------+---------+----------+
          |           |          |         |          |
       fb2cng       Pandoc     libmobi   Calibre   Native copy
                              |
                              v
                        EPUB Optimizer
          +-------------------+-------------------+
          |                                       |
 Resource/Image/Font pipeline            OCF Repack/Validation
```

**Рішення:**

- складні format engines — запозичуємо як окремі executables;
- orchestration, security, routes, organizer, normalization, duplicate logic, transaction/journal, UI — пишемо самі;
- не копіюємо GPL source у Java modules;
- не переписуємо з нуля те, що fb2cng/Pandoc/libmobi вже роблять краще;
- не прив'язуємо Organizer до одного converter provider;
- Organizer планує задачу на рівні capability, а Toolchain сам обирає правильний engine.

Це дає найкоротший шлях до функціонального результату без накопичення технічного боргу.


---

# Частина III. EPUB Post‑Processing, Image Optimization і Maximum Compression

## 46. Мета EPUB Optimizer

EPUB Optimizer — не окремий сторонній застосунок і не заміна Conversion Toolchain. Це post-processing layer, який отримує вже сформований EPUB і робить його:

- меншим;
- детермінованим;
- структурно чистішим;
- безпечним;
- валідованим;
- сумісним із вибраним reading-system profile.

Основна ідея:

```text
Converter output
      |
      v
Structural validation
      |
      v
Resource graph
      |
      +--> orphan detection
      +--> duplicate detection
      +--> image analysis
      +--> font analysis
      +--> XHTML/CSS analysis
      |
      v
Optimization profile
      |
      v
Resource transformations
      |
      v
OCF ZIP packaging
      |
      v
EPUBCheck + MyHomeLib reader reopen
      |
      v
Semantic before/after comparison
      |
      v
Accept / rollback to unoptimized EPUB
```

Optimizer має працювати як для щойно сконвертованих книг, так і для вже існуючих EPUB у Library Organizer.

---

## 47. Стандарт і жорсткі контейнерні обмеження

### 47.1. Production baseline — EPUB 3.3

Станом на 2026-09-20 W3C EPUB 3.3 є Recommendation від 2026-01-13.

Для OCF ZIP:

- дозволені лише ZIP method `0` — Store;
- або ZIP method `8` — Deflate;
- `mimetype` повинен бути **першим** entry;
- `mimetype` не стискається;
- його точний вміст — `application/epub+zip`;
- без BOM;
- без leading/trailing whitespace;
- без extra field у ZIP header.

Отже, всередині валідного EPUB **не використовуємо** як ZIP method:

- LZMA;
- LZMA2;
- Zstandard;
- Brotli;
- BZip2;
- PPMd.

Вони можуть бути чудовими архівними алгоритмами, але EPUB-контейнер із такими compression methods не відповідає OCF rules.

### 47.2. Deflate backend може бути різним

Обмеження стосується **формату compressed stream**, а не конкретного compressor.

Тому допустимі:

- Java/zlib Deflate;
- 7-Zip Deflate;
- Zopfli Deflate;
- інший compressor, якщо результатом є коректний RFC1951 Deflate stream і OCF ZIP проходить validation.

Це дає можливість мати швидкий і максимальний режими без втрати EPUB-сумісності.

### 47.3. EPUB 3.4 modern profile

Станом на 2026-09-20 EPUB 3.4 має статус W3C Candidate Recommendation Draft від 2026-08-03.

У його core media type table додані:

```text
image/avif
image/jxl
```

Тому AVIF і JPEG XL **закладаємо в архітектуру**, але:

- не використовуємо у default profile;
- не використовуємо в `COMPATIBLE_EPUB33`;
- не використовуємо в `BALANCED_EPUB33`;
- не використовуємо в `MAXIMUM_EPUB33`;
- активуємо лише через `MODERN_EPUB34` або explicit device profile;
- перед масовим застосуванням перевіряємо реальну підтримку конкретним reader/device;
- source image за policy можна зберігати в original archive/quarantine для rollback.

---

## 48. Профілі оптимізації

### 48.1. `NONE`

Призначення:

- ніяких resource transformations;
- тільки validation;
- optional deterministic repack вимкнено.

Використання:

- forensic/debug;
- книги з DRM/особливими підписами;
- користувач не хоче змінювати EPUB.

### 48.2. `COMPATIBLE_EPUB33`

Максимально консервативний production profile.

Дозволено:

- exact duplicate resource removal;
- orphan removal тільки при 100% доведеній недосяжності;
- lossless PNG/JPEG metadata cleanup за safe rules;
- safe CSS minification;
- XML pretty-print removal без зміни text-node semantics;
- font deduplication;
- Deflate-9;
- Store для incompressible resources;
- deterministic OCF repack.

Не робити:

- lossy resize;
- WebP conversion без target capability;
- JXL/AVIF;
- aggressive CSS tree shaking;
- font subsetting, якщо coverage не доведено.

### 48.3. `BALANCED_EPUB33` — default

Рекомендований profile для canonical бібліотеки.

Додатково до compatible:

- resize надмірно великих raster images до policy limit;
- JPEG recompression з perceptual quality floor;
- PNG palette/bit-depth optimization, якщо lossless;
- WebP лише коли reading-system profile це дозволяє;
- font subsetting із verified glyph coverage;
- remove unused embedded fonts при 100% доведеній відсутності references;
- smart Store/Deflate per entry;
- output приймається тільки якщо validation зелена й є реальний size benefit.

Default target:

```text
quality first
compatibility second
size third
```

тобто optimizer ніколи не повинен зменшувати книгу ціною видимої деградації за межами встановленого quality floor.

### 48.4. `MAXIMUM_EPUB33`

Мета — найменший файл, який **залишається EPUB 3.3-сумісним**.

Дозволено:

- усі balanced transforms;
- агресивніше resize за заданим max dimension;
- WebP, якщо profile target гарантовано його підтримує;
- font subset + optional WOFF2 normalization, якщо валідатор/reader проходять;
- Zopfli-compatible Deflate backend;
- exhaustive per-entry compression comparison;
- higher CPU/time budget;
- optional recompression search по кількох image quality candidates.

Заборонено:

- JXL;
- AVIF;
- non-Deflate ZIP methods.

### 48.5. `MODERN_EPUB34`

Opt-in / experimental-to-production profile.

Може використовувати:

- AVIF;
- JPEG XL;
- EPUB 3.4 media types;
- сучасні encoding параметри.

Умови:

1. user/device profile explicitly дозволяє;
2. output package version/metadata узгоджено з цільовим стандартом;
3. reader compatibility tests зелені;
4. modern codec result дає meaningful savings;
5. perceptual quality gate пройдено;
6. fallback policy визначена.

До стабілізації EPUB 3.4 цей profile не є canonical default.

---

## 49. Reading System / Device Compatibility Profiles

Створити:

```java
public interface ReadingSystemProfile {
    String id();
    EpubTargetStandard targetStandard();
    Set<ImageCodec> supportedImageCodecs();
    boolean supportsWoff2();
    int preferredMaxImageWidth();
    int preferredMaxImageHeight();
    long preferredMaxResourceBytes();
}
```

Початкові profiles:

```text
GENERIC_EPUB33_COMPAT
MYHOMELIB_DESKTOP
MODERN_EPUB33
KOBO
POCKETBOOK
KINDLE_DERIVED
MODERN_EPUB34_EXPERIMENTAL
```

Важливо: не вгадувати support конкретного device тільки за назвою бренду. Матриця capability повинна мати version/date/source і перевірятися fixture/device tests.

---

## 50. Resource Graph

### 50.1. Навіщо

Неможливо безпечно видаляти або переписувати ресурс, доки не відомі всі references.

Створити:

```java
EpubResourceGraph
EpubResourceNode
EpubResourceReference
EpubReferenceType
```

Джерела references:

- OPF manifest;
- spine;
- nav;
- NCX;
- XHTML `src`;
- XHTML `href`;
- SVG references;
- CSS `url(...)`;
- CSS `@font-face`;
- inline CSS;
- cover metadata;
- guide/landmarks;
- media overlays, якщо присутні;
- fallback chains.

### 50.2. Orphan rule

Resource може бути видалений тільки якщо:

```text
manifest state + graph traversal + fallback analysis
```

однозначно доводять, що він недосяжний.

Ніколи не видаляти автоматично:

- package document;
- navigation document;
- resources із незрозумілими custom references;
- encrypted/obfuscated resources;
- script-associated assets у publication, де scripts дозволені;
- accessibility resources без окремої policy.

---

## 51. Duplicate Resources

### 51.1. Exact bytes

SHA-256 однаковий:

```text
image-a.jpg
image-copy.jpg
```

Можемо:

1. вибрати canonical path;
2. переписати всі references;
3. видалити duplicate.

### 51.2. Decoded image equivalence

Не використовувати автоматично у v1.

Два JPEG можуть декодуватися майже однаково, але бути різними edition assets.

Perceptual dedupe — тільки майбутній assisted mode.

### 51.3. Fonts

Однаковий SHA-256 font binary — dedupe safe після rewiring CSS references.

---

## 52. Image Optimization Pipeline

### 52.1. Аналіз кожного raster resource

Зібрати:

```text
format
MIME
width
height
alpha
animation
bit depth
byte size
ICC/profile presence
EXIF/XMP
orientation
estimated role: cover/content/icon
reference count
```

### 52.2. Не upscale

Optimizer ніколи не збільшує resolution.

### 52.3. Resize policy

Великі зображення можуть бути зменшені, якщо:

```text
image dimension > target profile limit
```

Приклад policy:

```text
COMPATIBLE: no resize unless absurdly large and user opted in
BALANCED: max long edge configurable, e.g. 2400–3200 px
MAXIMUM: tighter target configurable
```

Конкретні default dimensions визначити після benchmark на реальних книгах/пристроях, не hardcode без вимірювання.

### 52.4. JPEG

Safe path:

- auto-orient;
- preserve visible color;
- remove EXIF GPS/camera metadata, якщо воно не потрібне для rendering;
- не видаляти ICC profile без color-management decision;
- optimize Huffman tables;
- lossless transforms де можливі.

Balanced/Maximum:

- recompress only if savings threshold досягнуто;
- quality parameter не є єдиним gate;
- додати perceptual comparison.

### 52.5. PNG

Lossless first:

- оптимальний filter;
- palette conversion, якщо mathematically lossless;
- bit-depth reduction, якщо lossless;
- remove ancillary chunks тільки за whitelist/blacklist policy;
- preserve transparency;
- preserve color-critical information.

Не застосовувати lossy PNG quantization у `COMPATIBLE`.

У `BALANCED/MAXIMUM` lossy palette quantization можлива тільки як explicit policy з perceptual gate.

### 52.6. WebP

EPUB 3.3 core media type.

Але spec support ≠ guaranteed old-device support.

Тому:

```text
if targetProfile.supports(WEBP)
    consider conversion
else
    keep JPEG/PNG/SVG
```

Вибирати WebP лише якщо:

- output менший за source на встановлений threshold;
- quality gate пройдено;
- transparency/animation semantics не втрачені.

### 52.7. JPEG XL

Використовувати тільки в `MODERN_EPUB34`.

Primary use cases:

- photographs;
- covers;
- high-resolution illustrations.

Режими:

- lossless transcode/re-encode, де це має сенс;
- visually lossless/lossy — лише з quality gate.

Потрібен окремий provider:

```java
JpegXlImageOptimizerProvider
```

Його implementation може використовувати audited `libjxl/cjxl` executable як optional Converter/Optimizer Pack. Не копіювати library source у Java-модулі без окремого license review.

### 52.8. AVIF

Так само тільки `MODERN_EPUB34`/explicit device profile.

Порівнювати AVIF і JXL фактичним benchmark:

```text
bytes
encode time
decode compatibility
perceptual score
alpha/HDR requirements
```

Не встановлювати правило «JXL завжди кращий» або «AVIF завжди кращий».

### 52.9. SVG

Не rasterize за замовчуванням.

Optimization:

- remove redundant whitespace/comments;
- canonical numeric formatting;
- remove editor metadata, якщо safe;
- preserve accessibility/title/desc;
- preserve links/IDs referenced externally.

---

## 53. Image Quality Gate

Lossy optimization не можна приймати тільки через менший розмір.

Створити:

```java
ImageQualityGate
ImageQualityMetric
ImageOptimizationCandidate
```

На першій реалізації:

- deterministic pixel dimension checks;
- alpha-preservation checks;
- optional SSIM-like metric або external audited metric;
- manual fixture review для threshold calibration.

Policy example:

```text
candidateSize < sourceSize
AND savings >= minimumSavings
AND perceptualScore >= profileThreshold
AND dimensions >= requiredDimensions
```

Якщо умови не виконані — source image залишається.

---

## 54. Font Optimization

### 54.1. Найбільша потенційна економія після images

Embedded fonts часто займають мегабайти.

Pipeline:

1. зібрати `@font-face`;
2. знайти фактично referenced font files;
3. зібрати Unicode code points із publication;
4. врахувати CSS generated content;
5. врахувати ligatures/shaping requirements;
6. subset;
7. перевірити font validity;
8. переписати resource;
9. reader reopen.

### 54.2. Provider abstraction

```java
FontOptimizerProvider
FontSubsetRequest
FontSubsetResult
```

Кандидати external backend визначити окремим mini-audit:

- HarfBuzz subset tools;
- fonttools `pyftsubset`;
- інший audited backend.

Не фіксувати binary до перевірки:

- Windows portability;
- license;
- Unicode/complex scripts;
- variable fonts;
- WOFF/WOFF2.

### 54.3. Безпечний fallback

Якщо subset verification сумнівна — залишити original font.

Не робити automatic font removal тільки тому, що конкретний тестовий chapter не використав glyph.

---

## 55. XHTML / CSS / XML Optimization

### 55.1. XHTML

Безпечне:

- remove formatting-only indentation між block elements, якщо DOM semantics не змінюється;
- remove comments, крім protected markers;
- canonical attribute quoting;
- remove duplicate namespace declarations;
- normalize line endings.

Небезпечне й заборонене без DOM-aware proof:

- глобально collapse whitespace text nodes;
- видаляти `id`;
- переписувати anchors без reference graph;
- видаляти semantic/accessibility attributes.

### 55.2. CSS

Safe:

- comments;
- redundant whitespace;
- canonical values;
- duplicate identical declarations.

Advanced:

- unused selector elimination.

Advanced mode не входить у v1, доки немає повного selector usage analyzer.

### 55.3. OPF/nav/NCX

Minification можлива лише через parse -> model -> serialize.

Ніяких regex-rewrite XML.

---

## 56. OCF ZIP Packaging

### 56.1. Власний abstraction

```java
EpubPackager
EpubPackagingProfile
ZipCompressionBackend
ZipEntryCompressionDecision
```

### 56.2. Entry policy

`mimetype`:

```text
first
method = STORE
no extra field
exact content
```

Text-like resources:

```text
XHTML
CSS
OPF
NCX
SVG
XML
TXT
```

-> Deflate.

Already compressed resources:

```text
JPEG
WebP
JXL
AVIF
WOFF2
MP3
MP4
```

-> порівняти Store vs Deflate або default Store, якщо Deflate не дає meaningful savings.

PNG — test both, бо внутрішній PNG already compressed, але ZIP іноді все ще дає невеликий виграш залежно від data.

### 56.3. Fast backend

Java Deflate / стандартний deterministic writer.

Для UI preview/test.

### 56.4. High backend

7-Zip:

```text
-tzip
-mm=Deflate
-mx=9
```

але packaging wrapper повинен гарантувати OCF `mimetype` rules і після створення перечитати ZIP headers.

Не покладатися лише на exit code 0.

### 56.5. Maximum backend

Zopfli-compatible Deflate.

Мета:

- отримати той самий standard Deflate stream;
- витратити більше CPU;
- отримати мінімальніший ZIP.

Врахувати: upstream Google Zopfli repository архівований у 2025 році. Тому перед bundling потрібен окремий maintenance/security/license decision. Apache-2.0 license дозволяє інтеграцію, але abandoned/archived status означає, що не можна бездумно зробити його критичною runtime-залежністю.

Варіанти:

1. optional external helper;
2. maintained compatible implementation після audit;
3. 7-Zip Deflate-9 як production fallback.

### 56.6. Determinism

Для reproducible output:

- stable entry order;
- normalized timestamps policy;
- stable metadata serialization;
- no random temp names in archive paths;
- stable compression settings.

---

## 57. «Кращий архіватор» — остаточне рішення

Не шукати compressor з LZMA2/Zstd для самого EPUB.

Правильна стратегія:

```text
1. optimize content
2. remove redundant bytes
3. choose better image/font representation
4. only then optimize Deflate
```

Очікуваний порядок впливу на size:

```text
images              >>>>
embedded fonts      >>>
duplicate/orphan    >>
XHTML/CSS cleanup   >
Deflate tuning      >
```

Тобто 7-Zip/Zopfli — останній етап, а не головний механізм економії.

---

## 58. Optimization Acceptance / Rollback

### 58.1. До оптимізації

Зафіксувати:

- SHA-256 source EPUB;
- total bytes;
- manifest;
- spine order;
- TOC logical model;
- metadata snapshot;
- chapter normalized text hashes;
- image dimensions/count;
- font count;
- link/reference graph.

### 58.2. Після оптимізації

Перевірити:

- ZIP CRC;
- OCF rules;
- no traversal;
- manifest targets exist;
- all refs resolve;
- EPUBCheck;
- MyHomeLib reader reopen;
- metadata critical fields preserved;
- spine order identical;
- normalized chapter text hashes identical, крім явно дозволених transformations;
- cover still resolves;
- image dimensions/quality policy;
- fonts load/required glyphs covered.

### 58.3. Accept rule

Lossless profile:

```text
valid
AND semantic-equivalent
AND outputSize <= inputSize
```

Balanced/Maximum:

```text
valid
AND semantic-equivalent
AND perceptual quality gate passed
AND outputSize < inputSize by configured threshold
```

Якщо fail:

```text
discard optimized candidate
keep validated unoptimized EPUB
record reason
```

---

## 59. Statistics і UI

### 59.1. Optimization report

```java
EpubOptimizationReport
```

Поля:

```text
sourceSize
resultSize
savedBytes
savedPercent

removedOrphans
deduplicatedResources

imagesBeforeBytes
imagesAfterBytes
imagesConverted
imagesResized

fontsBeforeBytes
fontsAfterBytes
fontsSubset

markupBeforeBytes
markupAfterBytes

zipBeforeBytes
zipAfterBytes
compressionBackend

profile
targetStandard
readingSystemProfile

epubCheckStatus
readerReopenStatus
semanticVerificationStatus
warnings
```

### 59.2. UI

У Conversion settings:

```text
Оптимізація EPUB
[ ] Не оптимізувати
[ ] Сумісна EPUB 3.3
[x] Збалансована EPUB 3.3
[ ] Максимальна EPUB 3.3
[ ] Сучасна EPUB 3.4 (експериментальна)
```

Окремо:

```text
Оптимізувати зображення
Оптимізувати шрифти
Використовувати сучасні кодеки, якщо підтримує пристрій
Максимальний розмір зображення
Зберігати оригінал
```

Ніяких internal enum names у UI.

### 59.3. Organizer preview

Додати columns:

```text
Розмір зараз
Очікувана дія
Профіль оптимізації
Результат
Економія
Попередження сумісності
```

До фактичного encode «Економія» може бути `—` або estimate, але estimate ніколи не показується як guaranteed result.

---

## 60. Journal / DB additions

Для кожної optimization operation:

```text
operation_id
source_path
target_path
source_sha256
result_sha256
source_size
result_size
saved_bytes
saved_percent
optimization_profile
target_standard
device_profile
image_codec_changes_json
font_changes_json
compression_backend
validation_status
created_at
```

Це потрібно для:

- audit;
- rollback;
- статистики;
- повторної оптимізації;
- відтворення багів.

---

## 61. Performance і parallelism

### CPU classes

Image encode, font subset і maximum Deflate — CPU-heavy.

Не запускати їх у тому самому uncontrolled pool, що scanner/hash.

Pools:

```text
SCAN_IO_POOL
HASH_IO_POOL
CONVERSION_PROCESS_POOL
IMAGE_CPU_POOL
FONT_CPU_POOL
MAX_COMPRESSION_POOL
```

`MAX_COMPRESSION_POOL` default concurrency:

```text
1
```

або невелике значення, бо Zopfli/max Deflate може бути дуже CPU-intensive.

Memory limit:

- не декодувати одночасно десятки 100+ MP images;
- large images process one/few at a time;
- temp disk quota.

---

## 62. Нові security requirements

Image decoders/encoders є attack surface.

Правила:

- dimensions limit до decode;
- pixel-count bomb limit;
- input byte limit;
- timeout;
- external codec process isolation;
- no network;
- temp workspace only;
- output extension/MIME/signature recheck;
- process tree kill;
- CVE/version tracking для bundled image codec executables;
- malformed images -> keep original or fail according to profile, але не «лікувати» мовчки.

Для SVG:

- no external network fetch;
- no filesystem URI escape;
- scripts policy;
- entity restrictions.

---

## 63. Тестова матриця Optimizer

### Container

- valid EPUB3;
- `mimetype` not first;
- compressed `mimetype`;
- extra field on `mimetype`;
- ZIP64;
- Unicode paths;
- duplicate ZIP names;
- malformed central directory.

### Images

- JPEG cover;
- CMYK JPEG;
- JPEG ICC;
- EXIF orientation;
- huge JPEG;
- progressive JPEG;
- PNG alpha;
- palette PNG;
- 16-bit PNG;
- animated GIF;
- SVG;
- WebP;
- AVIF fixture;
- JPEG XL fixture;
- corrupt image;
- decompression bomb image.

### Fonts

- TTF;
- OTF;
- WOFF;
- WOFF2;
- Cyrillic;
- Latin;
- mixed scripts;
- ligatures;
- combining marks;
- emoji/fallback;
- variable fonts.

### Markup

- whitespace-sensitive inline spans;
- pre/code;
- ruby;
- MathML;
- SVG inline;
- footnotes;
- anchors;
- CSS url;
- nested relative paths.

### Quality

- source already smaller than candidate;
- 1% saving;
- large saving;
- perceptual threshold pass/fail;
- cover dimensions;
- transparency preserved.

### Compatibility

- Generic EPUB 3.3;
- MyHomeLib reader;
- Kobo profile fixture;
- PocketBook profile fixture;
- Modern EPUB 3.4 fixture.

---

## 64. Паралельний план реалізації Optimizer

### Паралельно з Conversion Gate 3 / fb2cng

Можна реалізувати:

- resource graph;
- OCF reader/writer;
- deterministic repack;
- size report;
- duplicate resource detection.

Для цього достатньо будь-якого стабільного EPUB fixture.

### Паралельно з Pandoc/libmobi

Реалізувати:

- JPEG/PNG analyzer;
- image provider abstraction;
- CSS/XHTML safe cleanup;
- font analyzer;
- reader/EPUBCheck verification.

### Паралельно з Organizer Preview

Реалізувати:

- optimization settings DTO;
- profile UI;
- preview columns;
- journal schema.

### До Organizer Apply

Обов'язково готові:

- validated OCF packager;
- rollback;
- `COMPATIBLE_EPUB33`;
- `BALANCED_EPUB33`;
- output semantic verification.

### Після v1 Apply

Можна доводити:

- `MAXIMUM_EPUB33`;
- Zopfli backend;
- font subsetting;
- WebP auto-choice;
- `MODERN_EPUB34`;
- JXL/AVIF benchmark/device matrix.

---

## 65. Пріоритети реалізації

### P0 — correctness

- resource graph;
- OCF compliance;
- validation;
- rollback;
- semantic comparison.

### P1 — великий size benefit

- image resize/recompression;
- duplicate images/resources;
- unused resources;
- fonts.

### P2 — середній size benefit

- CSS/XHTML normalization;
- PNG optimization;
- WebP selection.

### P3 — останні відсотки

- Deflate-9 tuning;
- Zopfli;
- exhaustive candidate search.

### P4 — modern experimental

- JPEG XL;
- AVIF;
- EPUB 3.4 device profiles.

---

## 66. Acceptance criteria — EPUB Optimizer

Optimizer v1 вважається готовим, коли:

1. валідний EPUB після `COMPATIBLE_EPUB33` залишається валідним;
2. `mimetype` first/Store/exact;
3. жодного broken manifest/reference;
4. MyHomeLib reader відкриває output;
5. critical metadata збережені;
6. spine/TOC не змінені без policy;
7. chapter semantic text не втрачено;
8. duplicate resource removal перевірена;
9. orphan removal не має false-positive у fixture suite;
10. optimized file не більший за source;
11. rollback повертає source;
12. journal містить before/after hashes;
13. `BALANCED_EPUB33` image changes проходять quality gate;
14. `MAXIMUM_EPUB33` використовує тільки Store/Deflate;
15. `MODERN_EPUB34` ніколи не активується мовчки;
16. JPEG XL/AVIF outputs проходять target reader/device fixtures;
17. batch optimization cancellation/crash recovery коректні;
18. 1000-book batch не має resource leaks/temp leaks.

---

## 67. Оновлена кінцева архітектура

```text
                             MyHomeLib UI
                                  |
             +--------------------+--------------------+
             |                                         |
     Conversion Use Cases                      Organizer Use Cases
             |                                         |
             +--------------------+--------------------+
                                  |
                         Shared Book Services
        +-------------------------+-------------------------+
        |                         |                         |
 Format/Metadata            Hash/Duplicate            Safe FS/Journal
        |                         |                         |
        +-------------------------+-------------------------+
                                  |
                         Conversion Toolchain
             +----------+----------+----------+----------+
             |          |          |          |          |
          fb2cng      Pandoc     libmobi    Calibre   Native copy
                                  |
                                  v
                          Structural Validator
                                  |
                                  v
                            EPUB Optimizer
      +---------------+---------------+----------------+----------------+
      |               |               |                |                |
 Resource Graph    Image Pipeline   Font Pipeline   Markup Cleanup   OCF Packager
      |               |               |                |                |
      +---------------+---------------+----------------+----------------+
                                  |
                          Validation / Quality Gate
                         EPUBCheck + Reader Reopen
                                  |
                                  v
                     Canonical EPUB / Device Derivative
```

Остаточна стратегія:

- не переписувати format engines, які вже якісно існують;
- власними робити orchestration і post-processing;
- зменшення EPUB починати з images/fonts/resources, а не з «сильнішого ZIP»;
- production canonical target — EPUB 3.3;
- maximum EPUB 3.3 — Deflate-compatible;
- JPEG XL/AVIF — закладені, але тільки modern EPUB 3.4 opt-in;
- Library Organizer використовує той самий Optimizer автоматично після conversion;
- будь-яка optimization приймається лише після validation і semantic/quality gate.

---

## 68. Перевірені зовнішні стандарти/джерела для цієї редакції

Стан перевірки: **2026-09-20**.

1. W3C EPUB 3.3 — Recommendation, 2026-01-13.
   - OCF ZIP допускає тільки Store (`0`) / Deflate (`8`);
   - `mimetype` — перший, не стиснений, exact `application/epub+zip`, без extra field.

2. W3C EPUB 3.4 — Candidate Recommendation Draft, 2026-08-03.
   - `image/avif` додано як core image media type 3.4;
   - `image/jxl` додано як core image media type 3.4.

3. Google Zopfli.
   - генерує стандартний Deflate stream;
   - орієнтований на кращий compression ratio ціною значно більшого CPU time;
   - upstream repository archived у 2025 році, тому для production bundling потрібне окреме рішення щодо maintenance.

Ці факти є constraints плану, а не припущеннями.
