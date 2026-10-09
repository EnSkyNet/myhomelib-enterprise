# MyHomeLib 8.0.0 — що реально залишилося після V34 real-data acceptance

**Дата стану:** 2026-10-05
**Локальний статус:** R6–R17 guarantees збережені. R18 завершив post-live-test hardening failure transparency/persistence/integration paths: Reader settings, Kobo repository errors, stale Reader session state, Spring wiring/proxyability, TTS fallback, cover/archive decode recovery, WebDAV secret/config consistency та cleanup diagnostics. Exact R18 root `clean verify`: **2123/0/0/23, 635 Surefire reports, 21/21 SUCCESS, BUILD SUCCESS, 02:43**. UI coverage **486/486**. Нижче залишилися саме Windows/device/provider/connected acceptance gates, а не відомі локальні P0 code regressions.

## 0. Post-R1 R2–R5 — локально закрито

- conversion: secondary artifact не промотується без explicit action **включно з SQLite write→read hydration path**; V68 ремонтує historical missing-preference drift; повторна conversion в той самий format не створює duplicate artifact rows;
- повторний клік по тому самому автору після Operations/Reader знову відкриває author workspace;
- remote-only Book Details показує `Книгу не завантажено`, не запускає parser і дає `Завантажити`, якщо remote collection підтримує materialization;
- synchronous `LibraryOperationConflictException` у cancellable UI flows локалізовано й не випускається як uncaught JavaFX handler exception;
- старий download completion status більше не відновлюється після пізнішої background operation;
- Mockito test modules використовують explicit `-javaagent`; static release check контролює цей контракт;
- root offline `clean verify` R5 завершився `BUILD SUCCESS` (**2087/0/0**, 23 skipped);
- R14 full root regression завершився `BUILD SUCCESS` (**2097/0/0**, 23 skipped); full-text INDEX не блокує same-collection DOWNLOAD на coordinator/application/UI boundaries; derived-index repair відкладений із caller thread;
- на тій самій 707 154-book DB виконано Hikari/SQLite A/B: production Hikari interactive pool зменшено **4 → 2** після виміряного ~**17.31%** зниження warm-probe RSS без суттєвої latency/import regression;
- SQLite cache/mmap reduction відхилено через material author-query regression; BookCache/CoverCache/`-Xmx1024m` лишено без змін.

Деталі: `docs/release/POST-R1-R2-VALIDATION-2026-10-03-UA.md`, `docs/release/POST-R2-R3-VALIDATION-2026-10-03-UA.md`, `docs/release/POST-R3-R4-VALIDATION-2026-10-03-UA.md`, `docs/release/POST-R4-R5-VALIDATION-2026-10-03-UA.md`.

R6–R11 live-evidence tooling/readiness та runtime-binary binding: `docs/release/POST-R5-R6-VALIDATION-2026-10-04-UA.md`, `docs/release/POST-R6-R7-VALIDATION-2026-10-04-UA.md`, `docs/release/POST-R7-R8-VALIDATION-2026-10-04-UA.md`, `docs/release/POST-R8-R9-VALIDATION-2026-10-04-UA.md`, `docs/release/POST-R9-R10-VALIDATION-2026-10-04-UA.md`, `docs/release/POST-R10-R11-VALIDATION-2026-10-04-UA.md` та `docs/release/POST-R5-LIVE-ACCEPTANCE-RUNBOOK-UA.md`. R12 performance evidence: `docs/release/POST-R11-R12-VALIDATION-2026-10-04-UA.md`. R13 regression evidence: `docs/release/POST-R12-R13-VALIDATION-2026-10-04-UA.md`. R14 index/download + release-audit evidence: `docs/release/POST-R13-R14-VALIDATION-2026-10-04-UA.md`. R15 real-Windows-log/18-book-corpus evidence: `docs/release/POST-R14-R15-VALIDATION-2026-10-04-UA.md`. R16 second-Windows-pass evidence: `docs/release/POST-R15-R16-VALIDATION-2026-10-04-UA.md`. R18 full hardening evidence: `docs/release/POST-R17-R18-VALIDATION-2026-10-05-UA.md`; exact next live tasks: `docs/release/NEXT-TASKS-AFTER-R18-2026-10-05-UA.md`. Harness/readiness готові, але **не змінюють OPEN_EXTERNAL на PASS** без реального evidence.

Окремо ще треба повторити R1/R2 Windows live scenario: repeated selection/background Book Details не блокує Operations/Statistics/Reader/Conversion/navigation/download.

## 1. Реальна бібліотека / V33–V34 — основний large-data pass закрито

Завершено на `flibusta_online_fb2.inpx` (707 154 records): fresh/re-import, V65→V67 upgrade, navigation/search/Lucene, V33 performance tuning, restart/reopen та DB integrity. Деталі: `docs/release/REAL-DATA-ACCEPTANCE-V34-2026-10-02.md`.

З real-data блоку залишаються лише:

- великий **реальний CBR/CB7** corpus для V34 (у сесії був доступний лише generated 1000-page CBZ stress);
- 1–2 h production soak на цільовій робочій машині.

## 2. V30 physical Kobo — P0 для Kobo scope

First sync, library appearance, open/read, progress Kobo → MyHomeLib і MyHomeLib → Kobo, cover/metadata, reconnect, credential revoke/rotation. Локальні protocol fixtures не замінюють цей gate.

## 3. V31 packaged updater — P0 для auto-update scope

На Windows installer/portable/upgrade candidate: download/stage/restart, file locks, helper replacement, health-marker success, forced failed-start rollback, downgrade/platform/signature rejection, signing/distribution.

## 4. V32 real Readwise — P1

Real account auth, create/update dedup, rate-limit response, timeout/partial failure та повторний idempotent export. Remote delete не входить у V32.

## 5. V29 real browser — P1

Supported browser PWA install, app shell offline, explicitly saved book offline, reconnect progress replay та auth/token revocation behavior.

## 6. Windows x64 Calibre — inherited P0

На supplied Windows Calibre bundle і точному великому FB2 необхідно:

- записати фактичні версії Calibre/libmobi;
- підтвердити effective timeout 1800 s для default/legacy-300;
- виконати FB2 → EPUB понад 5 хвилин без старого 300 s timeout;
- відкрити/валідувати опублікований EPUB;
- перевірити видалення `mhl-calibre-*` workspace після success;
- Cancel під час активного Calibre: `CANCELLED`, повний process-tree exit, без locked `input.fb2`, workspace одразу removable;
- закрити MyHomeLib під час conversion і підтвердити відсутність orphan process/lock;
- batch ≥3 books: максимум 2 external conversions, queued work не стартує після Cancel, partial failure не видаляє успішні outputs;
- під час conversion відкрити unrelated local/remote book і навігувати UI.

Це головний незакритий функціональний Windows gate.

## 7. Windows installer / portable / DPI — MHL-011/012

На bound Windows 10/11 x64 host/user/session:

- clean install/start/uninstall/reinstall;
- real previous-version upgrade;
- MSI/EXE/portable paths, ASCII та non-ASCII location;
- packaged `--release-smoke` і runtime tools;
- DPI **100/125/150/200%** з screenshot-backed acceptance;
- real Windows DPAPI integration;
- plugin install/enable/disable/restart lifecycle у packaged app;
- EPUB/FB2/PDF Reader smoke;
- backup/restore і user-data migration на реальній Windows filesystem/Defender boundary.

## 8. Candidate-bound GitHub / supply-chain — MHL-010/017/018/019

Для exact frozen candidate:

- required checks / connected acceptance;
- CycloneDX SBOM artifact + digest;
- Dependency-Check/SCA under documented blocking policy;
- CodeQL release analysis;
- immutable reviewer evidence bundle.

Локальні regression/readiness tools перевірені, але вони навмисно не можуть самі закрити connected gate.

## 9. Інші live platform/provider integrations

Перевіряти тільки якщо входять у релізний сценарій:

- physical device export + OS safe eject;
- configured external reader executable;
- system TTS voices та FFmpeg runtime;
- WebDAV server з реальним conflict flow;
- metadata/translation/AI providers з реальними credentials/consent;
- сторонній plugin через Plugin SPI;
- external OPDS/MCP clients.

Секрети не зберігати у source/release archive або evidence.

## 10. Підписування і публічна доставка

Для публічного Windows-дистрибутива: Authenticode signing, SHA256SUMS binary artifacts, SmartScreen/Defender acceptance. Для macOS — лише якщо платформа входить у release scope: окремий build/codesign/notarization.

## 11. Production soak / target-machine memory confirmation

1–2 години на реальній Windows робочій машині з великою бібліотекою: пошук, navigation, Reader, downloads/conversions, themes/languages, crash/restart, RAM/handles/temp-files. R7 harness фіксує working set, heap used/committed, handles/threads, cumulative GC count/time та для MEM-10 — peak і first→last drift цих показників. R5 уже має same-environment Linux/707k A/B для Hikari **4 → 2**, але target Windows idle/working-set/GC behavior все ще треба підтвердити цим soak. Це не замінює конкретні P0/P1 acceptance gates вище.

## 12. Низькопріоритетне maintenance

Mockito/Byte Buddy dynamic self-attach maintenance **закрито в R4**: усі модулі з Mockito-тестами мають explicit test `-javaagent`, а `static_release_check.py` контролює цей контракт.
