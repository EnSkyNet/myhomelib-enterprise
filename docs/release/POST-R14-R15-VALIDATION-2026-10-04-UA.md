# MyHomeLib — V34 External Test R15 validation

**Дата:** 2026-10-04  
**База:** V34 External Test R14 / R13 Windows portable feedback  
**Мета:** виправити фактичні Windows UX-regressions, підтверджені користувацьким `myhomelib.log` і 18 реальними FB2 ZIP: зависання Book Details через shared background queue, INDEX↔DOWNLOAD contention, непрозору online-download діагностику та повільний FB2→EPUB fallback у Calibre.

## 1. Що показав реальний Windows log R13

У production одночасно працювали багато online downloads; частина HTTP requests висіла до timeout і падала з HTTP 502. R13 записував переважно UUID, тому з логу не було зрозуміло, яку саме книгу він качає. Conversion route/duration також не було видно, бо production package logger був WARN, а conversion milestones — INFO.

Це означало, що R12 lazy-FB2 parsing справді прискорив parser, але **не гарантував швидкий UI**, бо Book Details все ще стояв у тій самій executor-черзі за довгими network tasks.

## 2. Реальний corpus: 18 наданих FB2 ZIP

Використано всі надані томи `Пожиратель душ` плюс 17.7 MiB compilation `729586 ... #01-18.fb2.zip`.

Product `BookInspectionService` без network contention читає звичайні томи за десятки мілісекунд; 17.7 MiB compilation — приблизно 0.25 s. Отже 20–60 s очікування не є властивістю цих FB2.

Stress probe з **24 навмисно заблокованими NETWORK tasks** і реальним `UiBackgroundExecutor`/INTERACTIVE lane:

- усі 18 реальних книг: **751 ms total**;
- звичайні томи: приблизно **15–146 ms** кожний;
- 17.7 MiB compilation: **241 ms**;
- 18/18 parsed successfully.

Raw evidence: `/mnt/data/r15-real-corpus-isolated.log` у validation handoff.

## 3. R15 fix: execution lanes + Book Details watchdog

`UiBackgroundExecutor` тепер має окремі lanes:

- NETWORK — blocking online HTTP/download work;
- INTERACTIVE — Book Details/local interactive analysis;
- existing bounded background lane — решта background work.

`BookDownloadCoordinator` використовує NETWORK lane. `BookDetailsController` використовує INTERACTIVE cancellable lane, скасовує попередню selection task і має 5 s watchdog: якщо deep analysis не завершився за 5 s, spinner не може висіти хвилинами; UI повертається до basic details/warning, task скасовується, а timeout записується в log.

Superseded/timed-out interactive result закривається, якщо він `AutoCloseable`, щоб не лишати Reader/temp resources.

## 4. INDEX більше не блокує download path

Збережено і повторно перевірено R14 fix:

- full-text INDEX використовує shared detached INDEX root;
- same-collection DOWNLOAD може працювати під active INDEX;
- post-download catalogue/full-text repair під active INDEX відкладається off caller/network path;
- concurrent STALE ownership reference-counted;
- low-level Lucene rebuild не публікує false FRESH.

Targeted concurrency suite — green.

## 5. Conversion 16.5 min: фактична причина і fix

R13 portable **містив** `tools/converters/fbc-windows-amd64/fbc.exe`, але runtime locator contract не гарантував цей vendor path. AUTO міг не знайти bundled FBC і перейти на Calibre. Сам факт «FBC лежить у ZIP» тому не означав «FBC використано».

R15:

- `ConverterToolLocator` явно шукає `tools/converters/fbc-windows-amd64/fbc.exe`;
- для AUTO `FB2/FB2_ZIP → EPUB` fast route є пріоритетним;
- slow Calibre fallback для цього AUTO flow **не дозволений за замовчуванням**; його можна відкрити тільки explicit setting `converter.auto.allowSlowFallback=true`;
- `CONVERSION_START`, provider/route/step, `FBC_START/FBC_DONE`, DONE/FAIL/CANCEL і duration логуються на production INFO для конкретних conversion classes.

Тому нова збірка не може непомітно витратити 16 хв у Calibre без явного запису route/engine в log.

Windows FBC wall-clock все одно є live gate: Linux не може виконати `fbc.exe`.

## 6. Лог став діагностичним

Production log тепер містить:

- `ONLINE_DOWNLOAD_START/DONE/FAIL`: title, book id, LibID, file, archiveEntry, duration;
- `BOOK_DETAILS_START/DONE/CANCEL/TIMEOUT`: title, id, file/archiveEntry, duration;
- `CONVERSION_START/DONE/FAIL/CANCEL`: title/id/target/mode/provider/duration;
- `FBC_START/FBC_DONE`: executable/input/output/duration.

Шум JavaFX CSS WARN знижено до ERROR-рівня, щоб operation traces не губилися серед стилізаційних попереджень.

## 7. Conversion primary / EPUB regressions

R13 guarantees збережені:

- conversion artifact лишається secondary;
- source/imported original має пріоритет при missing preference;
- V68 repairs already-affected projection;
- повторний DB round-trip не робить converted EPUB primary;
- EPUB Book Details має OPF metadata fallback.

## 8. Validation exact-current R15

Affected targeted suite після фінальних logging/watchdog змін:

- `ContentIndexingQueueServiceTest`: **9/9**;
- `ContentIndexMaintenanceLifecycleTest`: **2/2**;
- `ConvertBookUseCaseFallbackTest`: **3/3**;
- `DownloadBookUseCaseIndexConcurrencyTest`: **1/1**;
- `ConverterToolLocatorTest`: **7/7**;
- `BookDetailsSelectionNonBlockingContractTest`: **2/2**;
- `UiBackgroundExecutorCollectionScopeTest`: **12/12**;
- `DesktopConfigurationOwnershipTest`: **3/3**;
- affected reactor: **BUILD SUCCESS**.

Windows-classifier fat JAR build (`javafx.platform=win`, tests skipped only for packaging build): **15/15 reactor projects SUCCESS, BUILD SUCCESS, 22.303 s**.

Two exact-current root `clean verify` attempts in this execution environment were forcibly stopped by the external 5-minute command limit before reactor summary; no R15 root PASS is claimed. Last uninterrupted full root baseline is R14: **2097/0/0/23, 21/21 SUCCESS**. R15 changes are covered by affected suites and real-corpus stress above.

## 9. Definition for Windows retest

On fresh R15 portable:

1. copy only `data/` and optionally `runtime/` from old portable; do **not** copy old `app/` or `tools/`;
2. rapidly select all 18 supplied books and watch Book Details; no selection may wait behind network timeout; any single deep analysis >5 s must exit spinner and log `BOOK_DETAILS_TIMEOUT`;
3. start search-index rebuild and download remote book concurrently; download must start immediately;
4. FB2→EPUB AUTO must log FBC route; if FBC fails, AUTO must fail visibly rather than silently spend 10–30 min in Calibre;
5. after conversion original remains primary after restart; EPUB secondary; EPUB Book Details returns full parse or OPF fallback;
6. preserve `logs/myhomelib.log` for any deviation — it now includes book names and route/duration evidence.
