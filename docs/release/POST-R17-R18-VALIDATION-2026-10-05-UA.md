# MyHomeLib — V34 External Test R18 hardening audit

**Дата:** 2026-10-05
**База:** R17 commit `f5eb96f3d79960ded69c83440c0ec74924c4e604`
**Мета:** повний post-live-test hardening без відкриття нового product scope: failure transparency, persistence consistency, cover/archive identity, external-integration cleanup, Spring wiring, static/release gates та повний uninterrupted regression.

## 1. Підсумок

R18 не додає новий функціональний scope. Аудит пройшов production-код, persistence/fallback paths, exception handling, concurrency/lifecycle, cover pipeline, external integrations, Spring wiring/proxyability, release scripts, UI action inventory та історичні regression gates.

Знайдено й виправлено шість реальних класів дефектів/ризиків:

1. Reader preferences приховували помилку запису від UI.
2. Kobo lookup маскував repository failure як «book not found».
3. Reader session міг воскресити stale SQLite state після невдалого mirror write/delete.
4. `SqliteKoboSyncAdapter` мав ризиковий Spring contract: `final @Repository` + кілька конструкторів без однозначного production injection contract.
5. POSIX/C locale могла ламати Maven-тести на валідних кириличних шляхах ще до застосування JVM `file.encoding`.
6. Runtime-tool packaging очікував libmobi license лише в source-layout і не підхоплював наданий `tools.zip`, хоча готовий runtime bundle має іншу коректну структуру.

Додатково посилено TTS provider fallback, cover/archive identity і decode recovery, WebDAV secret/config consistency, cleanup logging, empty-catch ratchet, Windows packaged FBC smoke та кілька static gates, що відстали від уже чинної архітектури.

## 2. Reader preferences — помилка запису більше не імітує успіх

`ReaderPreferencesService.savePreferences()` та `resetPreferences()` раніше логували persistence exception, але не передавали його далі. Через це UI-контролер не міг показати користувачу failure, хоча його contract уже передбачав `catch`.

R18:

- після логування кидається `IllegalStateException`;
- security/regression tests перевіряють, що unsafe/corrupt path не перезаписує дані й failure доходить до caller;
- `persistence-error-transparency-check.py` фіксує цей контракт як static release invariant.

## 3. Kobo lookup — repository failure не є «книгу не знайдено»

`KoboSyncService.book(String)` мав широкий `catch (RuntimeException)`, який охоплював і parsing ID, і repository access. У результаті помилка БД могла перетворитися на `Optional.empty()`.

R18:

- `Optional.empty()` повертається лише для некоректного book UUID;
- repository/runtime failure для валідного ID поширюється наверх;
- optional conversion-capability degradation логуються як warning;
- додані regression tests для malformed ID та repository failure.

## 4. Reader session — Preferences як authoritative fallback

`SqliteSessionRepository` вже записував Preferences перед best-effort SQLite mirror, але читав SQLite першим. Якщо SQLite update/delete падав, stale row міг знову стати видимим після успішного Preferences update/clear.

R18:

- Preferences читаються першими;
- successful save прибирає tombstone і best-effort синхронізує SQLite;
- clear ставить `lastOpenedBookCleared_*` tombstone до SQLite delete;
- tombstone прибирається лише після успішного delete;
- при failure stale SQLite row не може «воскресити» стару книгу;
- regression tests покривають failed mirror update і failed clear.

## 5. Spring wiring / proxyability

`SqliteKoboSyncAdapter` був `final @Repository` і мав два конструктори без явного production injection marker.

R18:

- repository більше не `final`;
- production constructor позначений `@Autowired`;
- `SqliteKoboSyncAdapterTest`: **4/4 PASS**;
- реальний `SpringContextStartupSmokeTest`: **1/1 PASS**;
- constructor-wiring та proxyability static gates PASS.

## 6. TTS, covers, WebDAV та cleanup hardening

### TTS

- збій одного provider не валить увесь playback path, якщо доступний інший;
- availability/voice discovery може деградувати до наступного provider;
- cancel/listener/terminal exceptions більше не зникають без діагностики;
- розширено `TtsPlaybackServiceTest`.

### Covers / archive identity

- додано scoped `invalidateCover(BookDto)`;
- `CoverReaderImpl` не підбирає довільний shared-archive member при слабкій identity;
- cached bytes, що не декодуються JavaFX Image або мають нульові dimensions, інвалідуються й не закріплюють stale empty/broken state;
- cover failure telemetry містить явну причину;
- додані archive-identity, cache-isolation та presenter-lifecycle tests.

### WebDAV / secrets

- clear WebDAV не видаляє endpoint/state, якщо secret delete не відбувся;
- endpoint rotation/secret-store failures логуються;
- додано user-visible localized `ui.integration.webdav.clear_failed` у UK/EN/BG;
- додано `DesktopIntegrationBackendWebDavSafetyTest`.

### Update/cleanup

- cleanup exceptions у network-update/recovery path більше не губляться без diagnostics.

## 7. Empty catch / blocking wait audit

Виконано повний перегляд exception-swallowing pattern, а не механічну заміну кожного best-effort catch.

- exact empty-catch count R18: **196**;
- baseline: **202**;
- removed/reworked: **6**;
- CI має `empty-catch-growth-check.py`, який не дозволяє непомітно збільшити count;
- thread-sleep audit PASS; production sleeps, що лишилися, review-allowed; JavaFX/UI blocking sleep — **0**.

## 8. Windows packaged converter smoke

`tools/smoke-runtime-tools.ps1` тепер перевіряє не лише наявність bundled FBC:

- запускає реальний small FB2 → EPUB conversion;
- перевіряє створення output;
- запускає EPUBCheck для результату.

Це static/packaged-harness improvement. Реальний Windows candidate все одно має пройти target-host acceptance.

## 9. Build locale і runtime-tool packaging hardening

### UTF-8 build launcher

Linux/POSIX build під `C`/POSIX locale двічі відтворив реальний false failure: Java `UnixPath` не міг створити тестовий шлях із кирилицею, хоча `-Dfile.encoding=UTF-8` уже був заданий. Причина — native/JNU charset вибирається до старту JVM.

R18 final:

- `tools/invoke-maven.sh` перед запуском Java перевіряє `locale charmap`;
- якщо поточна locale не UTF-8, launcher обирає доступну UTF-8 locale (`C.UTF-8`/еквівалент);
- якщо UTF-8 locale на host взагалі немає, build fail-fast із чіткою помилкою замість випадкових path failures;
- final root `build.sh` навмисно запущено з очищеними locale variables — **21/21 modules SUCCESS, 2123 tests, 0 failures/errors**.

### libmobi license layout / supplied tools.zip

У Git відстежується `tools/converters/libmobi/mobitool.exe`, але packager раніше приймав license лише як `libmobi/build-src/COPYING`. Наданий runtime bundle має коректний `libmobi/COPYING`.

R18 final:

- `package-desktop.ps1`, `package-desktop.sh` і `prepare-runtime-tools.ps1` приймають runtime-license поруч із binary та source-license як fallback;
- перевірений `tools/converters/libmobi/COPYING` доданий до source tree; його SHA-256 `da7eabb7...b4464768` збігається з уже зафіксованим expected libmobi COPYING digest;
- `prepare-runtime-tools.ps1` автоматично знаходить `tools.zip` або `tools.7z` у root/parent; ZIP розпаковується штатним `Expand-Archive`;
- supplied `tools.zip` SHA-256: `46340ce33280b2768896c2790e4f9a2a8253ab87202eb965c18a37ad7f320cce`; bundle містить FBC, Pandoc, Calibre, mobitool та COPYING.

Strict portable Windows contract **не послаблено**: bundled FBC і далі обов'язковий. Clean GitHub-hosted release checkout сам по собі не містить зовнішній FBC bundle, тому release CI має окремо provision `tools.zip`/еквівалент до Windows packaging. Без authoritative artifact URL/secret цей зовнішній provisioning навмисно не вигадується в коді.

## 10. Static gate drift, виправлений під фактичну архітектуру

Кілька історичних Python gates шукали старі inline implementation details, хоча код уже був централізований/перенесений. Перевірки оновлено так, щоб вони контролювали canonical current path, а не застарілий текст:

- large-library paging → `SearchBookPagingState`;
- lazy UI action boundary → `DeferredMainLibraryActions`;
- online upsert → centralized `BookUpsertSql.UPSERT`;
- OPDS architecture → current 20 child modules / 21 reactor projects;
- search-index state synchronization → shared `BookBatchEditSupport`;
- SQLite `busy_timeout` → centralized `SqliteConnectionPragmas`.

Ці зміни не послаблюють runtime contract; вони прибирають false negatives старих string-based gates.

## 11. Повний uninterrupted root regression

Середовище:

- JDK 21.0.11;
- Apache Maven 3.9.16;
- supplied offline Maven repository;
- JavaFX/UI під Xvfb;
- final launcher locale test: environment locale variables intentionally cleared; `tools/invoke-maven.sh` normalized the Java process to an available UTF-8 locale before JVM startup.

Фінальний regression запускався через штатний `build.sh`/Maven launcher під Xvfb з supplied offline Maven repository; окремий попередній uninterrupted root run дав той самий результат.

Результат одного безперервного root run:

- **2123 tests**;
- **0 failures**;
- **0 errors**;
- **23 skipped**;
- **635 Surefire XML reports**;
- **20 test/product modules**;
- **21/21 reactor projects SUCCESS**;
- **BUILD SUCCESS**;
- total time **02:43 min**.

Найбільші модулі:

- Application: **521 / 0 / 0 / 1 skipped**;
- Infrastructure: **693 / 0 / 0 / 13 skipped**;
- UI: **379 / 0 / 0 / 0 skipped**;
- Reader: **152 / 0 / 0 / 1 skipped**;
- Bootstrap: **60 / 0 / 0 / 4 skipped**;
- Architecture: **20/20**;
- E2E: **15/15**;
- Converter toolchain: **40 / 0 / 0 / 1 skipped**;
- EPUB optimizer: **23/23**.

Benchmark module має 3 intentionally skipped opt-in performance tests; вони входять у загальні 23 skipped.

## 12. UI/user-function coverage

Final static inventory:

- **486/486 user-facing actions** mapped;
- FXML actions: **275**;
- programmatic `setOnAction`: **205**;
- Web actions: **6**;
- action-specific evidence: **485 automated + 1 justified exclusion = 486/486**;
- user-function documentation: **486/486**, linked to **31 flows**;
- UK/EN/BG localization: **3748 synchronized keys**;
- Web localization: **87 keys**;
- Help: **76 Markdown pages**;
- unknown FXML handler references: **0**;
- unmapped workspace help routes: **0**.

## 13. Final static/release gates

PASS:

- architecture;
- implementation completeness;
- static release;
- supply chain;
- dependency upgrade matrix;
- Spring constructor wiring;
- Spring proxyability;
- persistence error transparency;
- user-data consistency;
- secret-store policy;
- privacy/temp lifecycle;
- XML/archive security;
- managed executor policy;
- collection search lifecycle;
- user-state search index;
- online concurrency/download/update rollback;
- startup nonblocking/orchestration/transaction;
- functional regression;
- UI action coverage/behavior/reachability/documentation/localization;
- historical stage/iteration gates після приведення stale gates до canonical implementation;
- cross-platform release artifact checksum smoke на зібраному JAR.

Implementation completeness additionally reports:

- production Java classes/files checked: **1283**;
- `TODO/FIXME/unsupported`: **0**;
- empty public/protected implementation methods: **0**;
- sentinel placeholder defaults: **0**;
- unused imports: **0**;
- unconsumed use cases/unimplemented ports: **0**.

Є один non-blocking maintainability warning: приблизно 401-character duplicate classification logic між Reader source resolution та Domain conversion classification. Після green full-root build навмисно не робився ризиковий cosmetic refactor; це не функціональний defect.

## 14. Що R18 НЕ доводить

Локальний Linux/Xvfb/offline audit не підмінює зовнішню acceptance. OPEN_EXTERNAL лишаються:

- fresh Windows R18 live retest: `729586` FBC conversion, rapid covers/back-forward, Lucene lock/rebuild, local-first no-repeat HTTP, INDEX+DOWNLOAD, shutdown after rapid navigation;
- native Windows installer/portable/update/rollback/DPAPI/DPI 100/125/150/200%;
- 1–2 h target-machine memory/handles/temp soak;
- physical Kobo;
- real Readwise;
- real browser PWA;
- real large CBR/CB7 corpus;
- configured external reader/system TTS/FFmpeg/WebDAV/provider credentials where release scope requires them;
- candidate-bound GitHub required checks, SBOM/SCA/CodeQL/signing and immutable release evidence;
- GitHub Windows release job потребує authoritative provisioning зовнішнього converter bundle (FBC); локальний supplied `tools.zip` перевірений, але не є автоматично доступним чистому hosted checkout.

Ці пункти не позначаються PASS без реального platform/device/provider/release-infrastructure evidence.
