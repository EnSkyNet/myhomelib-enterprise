# MyHomeLib — V34 External Test R3 validation

**Дата:** 2026-10-03  
**База:** V34 External Test R2  
**Мета:** hardening інваріантів після R2 без відкриття V35 scope.

## 1. Production change після R2

- `ConvertBookUseCase.Request` більше не має параметра `makePreferred`. Конвертація за API може лише створити/оновити secondary representation; зміна primary лишається окремою explicit user action.
- `withDefaults(...)`, усі production call sites і regression tests переведені на нову сигнатуру.
- Persistence-level `BookCommandRepository.upsertArtifact(..., boolean makePreferred)` не видалявся: він потрібен іншим explicit artifact-management flows; заборона стосується саме conversion use case.

## 2. Додатковий navigation regression

Додано `sameAuthorCanBeOpenedAgainAfterReaderWorkspaceState()` у `WorkspaceManagerNavigationStateTest`: сценарій `author → Reader → той самий author` повторно завантажує author workspace і не ламає history.

Targeted regression: `ConvertBookUseCaseTest` **19/19**, `WorkspaceManagerNavigationStateTest` **4/4** — разом **23/23 PASS**.

## 3. Exact-current Maven regression

Завершені module/chunk runs у UTF-8 locale (`C.UTF-8`), JavaFX модулі під `Xvfb`:

- Shared **44**, Domain **32**, Application **509** (1 skipped);
- EPUB Optimizer **22**, Library Organizer **40**, Converter Toolchain **39** (1 skipped);
- Plugin API **30**, Plugin Samples **2**, Calibre Plugin **7**;
- Infrastructure **681** (12 skipped), **179/179** test classes;
- Reader **150** (1 skipped); UI **371**;
- Updater **3**, Bootstrap **57** (4 skipped);
- Architecture **20**, E2E **15**, Benchmark **3** (3 skipped);
- MCP **10**, Web **18**, OPDS **29**.

**Aggregate: 2082 tests / 0 failures / 0 errors / 22 skipped.**

Default `POSIX` locale цього Linux container має ASCII native path encoding (`ANSI_X3.4-1968`), тому перша application-run дала 2 `InvalidPath` errors на Cyrillic filenames. Повтор у `C.UTF-8` пройшов **509/509**; це environment encoding issue, а не source regression. JavaFX Reader/UI тести виконувалися через `xvfb-run`, а не пропускалися.

## 4. Release/static gates

- UI action inventory: **485/485** = 274 FXML + 205 programmatic + 6 Web;
- action-specific behavior: **484 automated + 1 justified exclusion = 485/485**;
- user-function documentation: **485/485 → 31 flows**;
- UI localization: **3746** synchronized UK/EN/BG keys;
- Web localization: **87** keys;
- help/genre localization: **75** help pages, **335** genres per locale;
- architecture check — PASS;
- static release check — PASS;
- supply-chain policy — PASS;
- implementation completeness — PASS.

## 5. Root `clean verify`

Дві exact-current спроби одного uninterrupted root `clean verify` виконані після R3 changes у `C.UTF-8` під `xvfb-run` (`-T 2` і `-T 4`). Обидві були примусово завершені зовнішнім execution-tool limit приблизно на 5-й хвилині; остання встигла завершити Application **509/509** і перейти до reactor modules 9–12. До termination у Maven log немає test failure/error або `BUILD FAILURE`. Тому one-shot root gate не позначається як `BUILD SUCCESS`; його фактичне тестове покриття закрито split-complete runs: **2082 tests / 0 failures / 0 errors / 22 skipped** по всіх 20 модулях.

## 6. Scope, що лишається external/live

Не позначаються як локально закриті: real Windows repeated-selection/non-blocking acceptance, physical Kobo, packaged Windows updater/Calibre/DPAPI/DPI/file-lock/rollback/signing, real Readwise, unrestricted-browser PWA, real large CBR/CB7 corpus, 1–2 h production soak і same-machine memory A/B. Production Hikari/SQLite/cache/Xmx limits не зменшувалися без такого evidence.
