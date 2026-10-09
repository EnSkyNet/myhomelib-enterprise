# MyHomeLib — V34 External Test R12 validation

**Дата:** 2026-10-04  
**База:** V34 External Test R11  
**Мета:** усунути виміряну затримку passive Book Details для великого FB2 у ZIP без зміни Reader semantics, memory defaults або external-acceptance scope.

## 1. Реальний performance case

Наданий файл:

- `729586 Демон. Пожиратель душ #01-18.fb2.zip`;
- ZIP size: **6 061 634 bytes**;
- SHA-256: `8cbc2ea9d148df24034aea475d394414a026d5e520c6858f839735db07f302b1`;
- entries: **1**;
- FB2 entry size: **17 736 920 bytes**;
- FB2 SHA-256 після контрольованого extraction: `8cefddef17451f86dacd8d27eff9be664c571c114508a3ca0f37ae9b3d74649e`;
- parsed text: **7 756 433 chars**;
- word count: **1 199 396**;
- chapters: **17**;
- embedded images: **4**;
- combined base64 binary payload: приблизно **2.89 MB**.

Сам ZIP не є bottleneck: product `ZipArchiveReader` на цьому файлі показав перший `listEntries` близько **54 ms**, extraction близько **116 ms**; прогріті extraction runs — **73–77 ms**.

## 2. Root cause

`BookInspectionService` використовував повний FB2 Reader parse з `loadImages=true` навіть для пасивного екрану «Відомості». Це означало, що до натискання «Показати зображення» parser уже:

- збирав base64 payload усіх `<binary>`;
- створював temp resources;
- декодував зображення;
- записував великі image payload у `HybridResourceRepository`.

На реальному файлі A/B одного й того самого `Fb2StreamingParser`:

- `loadImages=true`: **1341 / 1080 ms**;
- `loadImages=false`: **211 / 252 ms**.

Отже image payload робота була приблизно **4–5× дорожча** за metadata-only parse і не потрібна для первинного Book Details UI.

## 3. Production fix R12

Для FB2/FBD passive `BookInspectionService.inspect()` тепер:

- парсить текст/metadata/TOC/image ids з `loadImages=false`;
- не декодує image payload під час звичайного вибору книги;
- зберігає image metadata для UI count;
- при першому явному `openImage(id)` один раз lazy-парсить image payload;
- повторні `openImage()` використовують уже lazy-loaded document;
- `close()` закриває як passive, так і lazy image document.

Інші формати не переведені на цей режим — зміна навмисно вузька й evidence-driven.

## 4. Real-file result після fix

`BookInspectionService` на тому самому 17.7 MB FB2:

- cold passive inspection: **418 ms**;
- warm passive inspection: **259 / 176 / 165 ms**;
- first explicit image open: **1484 / 896 / 827 / 821 ms**.

Тобто робота перенесена з обов'язкового passive path у явну user action. Повторне «Відомості» локально прискорилося приблизно з ~**0.95 s** до **0.17–0.26 s** після прогріву.

Це Linux/JDK 21 same-environment evidence. Остаточний Windows wall-clock треба перевірити на portable build, оскільки Windows Defender/AV і filesystem можуть змінити абсолютні числа.

## 5. Regression tests

Додано regression `fb2InspectionDefersBinaryPayloadUntilImageIsOpened`:

- passive inspect не відкриває source для image decode;
- image id лишається видимим у inspection;
- перший `openImage()` materialize-ить payload;
- другий `openImage()` не репарсить source;
- decoded bytes збігаються з original image.

Reader + Shared targeted suite під Xvfb:

- 195 tests total;
- 0 failures;
- 0 errors;
- 1 skipped opt-in real-file probe;
- `BUILD SUCCESS`.

## 6. Full exact-current regression

Фінальний root offline `clean verify` під `C.UTF-8` + Xvfb, JDK 21, Maven 3.9.16 та supplied offline repository:

- **2088 tests**;
- **0 failures**;
- **0 errors**;
- **23 skipped**;
- **20/20** Surefire test/product modules;
- **21/21** reactor projects SUCCESS;
- `BUILD SUCCESS`;
- total time **02:30 min**.

Різниця проти R11 — один новий regression test у Reader.

## 7. Memory / scope

Memory defaults не змінено:

- Hikari interactive pool: **2**;
- SQLite cache: **16 MiB/physical connection**;
- mmap: **64 MiB**;
- BookCache: **64 MiB**;
- CoverCache: **64 MiB**;
- desktop heap: **`-Xmx1024m`**, G1.

R12 фактично зменшує transient disk/resource work для passive FB2 details, але не заявляє нового Windows RSS baseline. Target Windows 1–2 h soak і решта physical/provider/browser gates залишаються external.
