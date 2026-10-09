# MyHomeLib 8.0.0 — загальна історія розвитку проєкту

Дата актуалізації: **2026-10-02**.
Статус: канонічний огляд історії українською мовою. Детальні незмінені checkpoint/iteration-матеріали зберігаються у `docs/history/records/`; старий великий англомовний консолідований файл `docs/history/HISTORY.md` лишається архівним джерелом, а не поточною специфікацією.

## 1. Базова платформа та модульна архітектура

MyHomeLib еволюціонував у модульний desktop-monolith на Java 21 / JavaFX / Spring Boot зі SQLite + Flyway, Lucene та окремими OPDS/Web і MCP runtime. Архітектура була послідовно переведена до явних модульних меж: `shared` → `domain` → `application`, технологічні adapters в `infrastructure`, UI/Reader окремо, `bootstrap` як composition root. Згодом додано самостійні модулі EPUB optimizer, Library Organizer, converter toolchain, plugin API/samples, Calibre plugin, Web, OPDS і MCP.

## 2. Дані, імпорт, пошук та цілісність

Ранні цикли закрили безпечні Flyway migrations, багатоколекційний lifecycle, INPX/import pipeline, Lucene indexing/search, transactional metadata editing, backup/restore, user-data portability, archive/path traversal guards, atomic publication та download integrity. Для online collections були введені TLS/proxy/credential policies, resume/validator checks, persistent download queue та semantic payload validation.

## 3. Reader та користувацькі дані

Reader отримав FB2/EPUB/TXT, MOBI/AZW family, PDF, comics та audio paths, bookmarks, position/history, annotations/highlights, content search, text providers, TTS/accessibility, reader settings та bounded caches. Подальші аудити закривали stale async completions, cancellation ownership, UTF-16 surrogate-boundary defects та lifecycle cleanup без введення універсального async framework без підтвердженої потреби.

## 4. Розширення продукту

Послідовно з'явилися smart collections, custom fields, duplicate review/merge, metadata provider SPI, Open Library/Google Books providers, batch metadata editor і undo, folder watcher, library health/integrity, knowledge Markdown export, device/export profiles, OPDS2/Web Reader, cross-device continuation, plugin SPI/SDK/security та AI extension boundary.

## 5. Release/security/supply-chain hardening

Release pipeline був переведений на fail-closed checks: static/build gates, architecture checks, source-package cleanliness, SBOM/Dependency-Check/CodeQL contracts, exact-candidate external acceptance evidence, Windows host/session binding та immutable reviewer evidence. Історичні release snapshots не є поточною специфікацією; актуальні правила лежать у `docs/current/` і `docs/release/`.

## 6. V7–V10: correctness, memory та lifecycle

V7–V10 зосередилися на correctness, async ownership, UI/Reader lifecycle, UTF-16 boundaries, structural deduplication та вимірюваному performance/memory. У V9 проведено контрольовані A/B для G1/Serial і heap matrix; результатом було **залишити G1 + `-Xmx1024m`**. Подальші версії не змінювали ці defaults без нових same-environment A/B.

V10 також закрив stale search/reader/download completion paths і виніс окремі responsibility boundaries (`SearchBookPagingState`, `ReaderAnnotationSidebarFilter`) замість широкого переписування.

## 7. V11–V14: hidden defects та Organizer

V11–V14 продовжили regression-first audit Unicode/offset/archive/HTTP/publication/concurrency paths. Серед підтверджених виправлень — HTTP resume short-body integrity, user-visible surrogate-safe truncation та низка fail-closed publication/security guards.

Library Organizer поступово отримав deterministic Analyze → Plan → Apply workflow, fingerprint/duplicate logic, safe path generation, collision handling, journal/recovery semantics і dry-run. До V25 цей напрям завершено для локального acceptance: recursive mixed-library tree, explicit metadata-coverage classification усіх recognized extensions, structured extraction для FB2/EPUB/DOCX/ODT/MOBI-family/PDF/CBZ/CBC, multi-book ZIP, actual filesystem apply та rollback після штучної publication/catalog failure.

## 8. V15–V22: conversion/process lifecycle

Цикл V15–V22 закрив cancellation semantics, operation compatibility та converter lifecycle. Реальний Windows log для великого FB2 показав історичний 300-секундний Calibre timeout і затримку звільнення file handle після kill. V22 змінив default/effective legacy timeout на **1800 с**, termination grace на **10 с**, додав checkbox-driven batch conversion, common-format intersection, bounded concurrency **2** та queued-work cancellation.

Ці production semantics після V22 не переписувалися без нового observable regression. Реальна Windows x64 acceptance для Calibre залишається зовнішньою release-задачею.

## 9. V23: release/tooling hardening

V23 не змінював product behavior. Було виправлено stale release/CI gates, standalone smoke closure, architecture-regression marker, expiring CodeQL fixture, external-readiness contract, документаційний drift та source packager, який міг повторно включити Python cache після запуску власних checks.

## 10. V24: повний user-facing inventory

V24 провів системну інвентаризацію UI: усі FXML resources/handlers, CoreActions, workspace help routes і programmatic JavaFX actions. Було виправлено F1 context-help routing для новіших workspaces. Сформовано повний каталог функцій користувача та functional audit. При цьому live Windows/external acceptance навмисно не видавався за автоматичний PASS.

## 11. V25: повне закриття локальних backlog-контрактів і архітектурний аудит

V25 повторно перевірив усі історичні `NEXT-TASKS`, включно з розширеним V10 task contract, наданим користувачем окремо. Основні результати:

- **UI interaction coverage:** machine-checkable registry охоплює **447/447** актуальних user-triggerable actions: 262 FXML + 185 programmatic; кожна entry має automated behavioral/delegate evidence та fingerprint, а drift тепер входить у `build-check-v7.py`.
- **Organizer:** додано explicit metadata coverage для кожного recognized format, format-specific ODT/PDF/MOBI/comic extraction, recursive mixed-tree acceptance, actual Apply-result та rollback acceptance.
- **Архітектурний guard:** offline checker і ArchUnit поширено на всі **16 runtime/product modules**; verification/benchmark modules відокремлені від production graph. Виявлені неявні transitive compile dependencies у plugin samples та Calibre plugin замінено явними direct dependencies.
- **UI boundary:** Backup/Restore більше не створюють native `DirectoryChooser` напряму, а використовують `FileChooserService`, що повертає dialog ownership у єдину presentation boundary.
- **Документація:** корінь очищено до `README.md` + `ARCHITECTURE.md`; активні описи перенесено в `docs/current/`, release evidence лишено в `docs/release/`, історичні snapshots/artifacts — у `docs/history/`.
- **Історія:** створено цей український канонічний огляд та окремий аудит закриття попередніх завдань.

## 12. Що залишається відкритим

Після V25 локально незакритими не вважаються «потенційні рефакторинги без defect/evidence». Реально відкриті лише зовнішні acceptance gates, які не можна достовірно закрити у Linux/Xvfb:

1. Windows x64 real Calibre large-FB2 conversion >5 хвилин, Cancel/process-tree teardown, handle release та app-close teardown.
2. Real Windows batch conversion з фактично спостережуваною concurrency ≤2 та partial-failure/cancel behavior.
3. Windows installer/portable/DPI та host/session-bound acceptance.
4. Реальні OS/device/external-reader/TTS/FFmpeg scenarios, де потрібен platform runtime.
5. Live WebDAV/metadata/translation/AI endpoints і external OPDS/MCP clients, де потрібні реальні credentials/services.
6. Connected GitHub release gates/evidence MHL-010/011/012/017/018/019.

Ці пункти мають статус `OPEN_EXTERNAL`/`OPEN_WINDOWS`, а не «неперевірено, але готово».

## 13. Незмінний інженерний контракт

- defect: regression evidence/test → мінімальний fix → targeted/downstream verification;
- refactor: один responsibility boundary за раз;
- performance/memory: лише same-environment A/B;
- stable code не переписується заради стилю;
- third-party runtime binaries не потрапляють у clean source ZIP без окремої release-policy;
- актуальна поведінка визначається `README.md`, `ARCHITECTURE.md`, `docs/current/` та `docs/release/`; `docs/history/` — evidence, а не нормативний contract.

## 14. Фіналізація V25: реальні дані, documentation closure і exact-current gate

Після первинного V25 audit було виконано ще три кроки, які прибрали останні невизначеності:

1. **Реальний великий INPX.** Наданий `flibusta_online_fb2.inpx` пройшов повний probe: **707154/707154 imported, 0 errors**; фінальна БД — 707154 books, 166231 authors, 856591 book-author links, 1102219 book-genre links, 135207 deleted; `quick_check=ok`. Core import у цьому середовищі був ~77.771 s. Час зафіксований як observational evidence, а не як новий SLA чи A/B-підстава для tuning.
2. **Документаційний fail-closed contract.** Розширений V10 вимагав не тільки UI inventory, а й deterministic synchronization із user-facing описом. Спочатку новий checker відтворив RED — у coverage registry не було formal documentation links. Після мінімального tooling/doc fix **447/447** JavaFX actions отримали `user_function_ref` на 28 documented JavaFX flows; MCP і non-desktop protocol sections залишені явно поза JavaFX inventory. Gate включено в `build-check-v7.py`.
3. **Exact-current reactor.** Після production/architecture/Organizer/UI changes фінальний Maven 3.9.16 `clean verify` завершився **1842 tests / 0 failures / 0 errors / 17 skipped; 20/20 reactor projects SUCCESS**. UI — 248 tests; E2E — 15.

V25 тому є не «ще одним списком планів», а консолідованою локально перевіреною source baseline. Те, що лишається після нього, має зовнішній характер і перелічене в `docs/release/WHAT-REMAINS-UA.md`.


## 15. V26: action-specific UI proof, chooser boundary та hidden-defect closure

V26 був повторним глибоким аудитом після V25, спрямованим на перевірку того, чи попередні завдання **дійсно** закриті, а не лише позначені як закриті. Ключова корекція: V25 мав повний inventory/fingerprint mapping 447/447, але цього було недостатньо, щоб автоматично вважати кожну конкретну дію поведінково доведеною.

У V26 додано strict action-specific coverage: тест має містити конкретний action/control/delegate token у контексті owning UI class і observable assertion/Mockito verification. Після закриття gap-ів результат — **446 automated action-specific + 1 justified exclusion (`Platform.exit()`) = 447/447**. Це виявило реальний дефект: кнопка **Edit** у Book Details була no-op і лише логувала подію. Після red regression вона делегує до canonical `ClassicLibraryActionsService.editBook`.

Другий підтверджений defect знайдено mutation-аудитом MOBI: unknown-size truncated payload міг завершити декодування коротшим за declared `textLength`. `MobiParser` тепер fail-closed з `IOException`; FB2/EPUB/ZIP/INPX mutation corpus також green.

Архітектурно завершено один responsibility boundary: **всі** JavaFX native file/directory chooser constructions централізовані в `FileChooserService`. Initial-directory validation/fallback більше не дублюється по UI; static regression забороняє нові прямі `FileChooser`/`DirectoryChooser` owners. Масового redesign великих класів не виконано: architecture graph acyclic, 16/16 production/runtime modules guarded, а відомий UI debt контролюється ratchet-правилами.

Повний V26 `clean verify`: **1960 tests / 0 failures / 0 errors / 18 skipped; 20/20 SUCCESS**. Повторно green ранні iteration/stage regression-gates. Додатково clean-stage аудит знайшов self-mutation у `build-check-v7.py`: дочірній Python gate створював `__pycache__`, після чого cleanliness check падав на власному артефакті. Після RED regression дочірні Python gates запускаються з `PYTHONDONTWRITEBYTECODE=1`, тому offline build-check не змінює pristine source tree.

Фінальний release-tooling recheck виявив ще одну метричну помилку: `static_release_check.py` включав `target/generated-sources/**/*.java` у source counts, тому звіт залежав від попереднього Maven run. Regression fixture довів проблему; check тепер рахує лише source tree поза `target/.git`, fixture прибирає створений ним `target` на pristine checkout, а regression включений у Linux CI та formal source-packager gate. Exact-current static count: **1989 Java sources / 595 test sources**.
External-readiness recheck також зловив stale V25 contract (`1842 / 17 skipped`) у `external-acceptance-readiness.py`; regression був RED на V26 `CURRENT-VALIDATION.md`. Мінімальний fix синхронізував structural tokens з **V26 / 1960 / 18 skipped** та явними MHL-010/017/018/019 IDs; readiness regressions знову є обов’язковим локальним доказом, але не підміняють connected acceptance.

Реально відкритий залишок не змінив природу: Windows Calibre/process/installer/DPI, candidate-bound GitHub/SBOM/SCA/CodeQL/signing і live external integrations.

## 16. V27: Reading Lists + Next in Series / On Deck

У V27 додано окрему user-owned модель **Reading Lists**: domain types, application use cases, SQLite repository та Flyway `V64`. Порядок книг зберігається як contiguous integer positions і змінюється транзакційно; duplicate membership блокується у схемі. UI отримав workspace «Списки читання», контекстну дію додавання книги, move up/down, drag-and-drop та «Почати/продовжити список».

Portable user-data contract піднято до schema v5: reading lists і membership входять у export/restore, а книги резолвляться чинною stable identity policy, без title/fuzzy matching.

Dashboard отримав derived **«Далі у серіях»** через окремий bounded SQL query path без нової persistence. Серія приховується, якщо має активну книгу `0 < progress < 100`; candidate визначається за валідним sequence після останньої completed книги, а ambiguous/null sequence fail-safe не показується.

Фінальні test/gate числа мають бути зафіксовані в `docs/release/CURRENT-VALIDATION.md` після exact-current verification цього checkpoint.

## 17. V28: scoped Reader profiles

V28 розширює Reader preferences від global + per-book до чотирирівневої моделі з точним precedence **Book > Series > Format > Global**. Додані application contracts `ReaderPreferenceScopeType`, `ReaderPreferenceScope`, `ReaderScopedPreferencesPort`; `ReaderSettingsStateService` централізовано повертає effective preferences разом із source scope.

Flyway `V65__reader_scoped_preferences.sql` додає generic collection-local storage та переносить чинні V42 book overrides у scope `BOOK`, не видаляючи released таблицю. BOOK projection синхронізовано з copy-between-collections і merge/undo; series/format profiles залишаються ізольованими в межах колекції. Пошкоджений JSON окремого scope fail-safe пропускається, дозволяючи fallback до нижчого рівня.

Reader Settings отримав окремі дії збереження global/format/series/book, очищення активного override та індикацію джерела effective profile. Portable user-data contract піднято до schema v6: BOOK відновлюється через stable book identity, series/format — лише за canonical scope keys без fuzzy matching. Фінальні test/gate числа фіксуються у `docs/release/CURRENT-VALIDATION.md` після exact-current verification.

### V28 closure evidence — 2026-10-01

- Full offline Linux/Xvfb `clean verify`: 20/20 reactor modules, BUILD SUCCESS.
- Surefire: 1996 tests, 0 failures, 0 errors, 18 skipped.
- UI action inventory/behavior/user-functions: 464/464.
- Architecture 19/19; E2E 15/15; static-release, supply-chain, localization and portable-user-data consistency PASS.
- V28 next checkpoint is V29 PWA/offline Web Library; external Windows/live/connected release acceptance remains a separate gate.

## 18. V29: installable PWA + offline Web Library

- Додано manifest, versioned Service Worker/client/CSS assets, 192/512 PNG icons і offline fallback до чинного Web/OPDS sidecar.
- Web Library/Reader сторінки отримали PWA metadata та explicit save/remove/clear offline actions.
- Offline book зберігається тільки після user action через санітизований versioned Web Reader manifest; whole-library auto-cache відсутній, completed offline-book caches bounded до 20.
- Service Worker записує в Cache Storage credential-sanitized responses, staging нового покоління завершується до видалення старого; clear-all не видаляє app shell.
- Offline reading progress coalesce-иться в browser IndexedDB queue і replay-иться після reconnect; Application contract відхиляє stale update та робить exact replay idempotent.

### V29 closure evidence — 2026-10-01

- Full offline Linux/Xvfb `clean verify`: 20/20 reactor modules, BUILD SUCCESS, 2:47.
- Surefire: 2006 tests, 0 failures, 0 errors, 18 skipped; Web 18/18, OPDS 24/24.
- UI action inventory: 470/470 (271 FXML + 193 programmatic + 6 Web); behavior: 469 automated + 1 justified exclusion.
- Architecture 19/19; E2E 15/15; static-release, supply-chain, Web/UI/help localization and portable-user-data consistency PASS.
- Production JAR SHA-256: `c1123b850bc27679e8aecdf901d9a747aaffa9f4050ba0797e17c64f50f8b9dd`.
- Real-browser PWA install/offline acceptance remains external; V26 Windows/live/connected release acceptance also remains separate.
- Next product checkpoint: V30 Kobo native sync, only as a separate checkpoint after protocol research/probe; do not merge it with V29.
## 19. V30: Kobo native sync

- Додано окремі `KoboSyncService` / `KoboSyncPort`, stable book/artifact revision та pure Kobo ↔ MyHomeLib progress translator.
- Додано `V66__kobo_sync_state.sql` для device/cursor/projection/progress-version state без зберігання credential у SQLite.
- Додано Kobo protocol adapter у чинному OPDS sidecar: initialization, incremental sync, metadata/content/state, cursor headers та secret-safe request logging.
- Інші формати проектуються в EPUB/KEPUB без eager conversion; фактична conversion використовує чинний `ConvertBookUseCase` лише під час download.
- Integration Center отримав Kobo status, enable/rotate, одноразовий credential, prepare sync і revoke; UK/EN/BG localization/help оновлено.
- Local deterministic targeted tests та UI action gates закриті; real Kobo acceptance залишається окремим LIVE gate до фізичного тестування.



## 20. V31: Secure application auto-update

- Додано signed `ReleaseManifest`, `UpdateVerificationService`, check/stage ports/use cases та bounded HTTPS adapter.
- Перевіряються product/platform/arch/min-updater/downgrade policy, Ed25519 signature, exact length і SHA-256.
- Додано окремий `myhomelib-updater`: parent-exit wait, повторна verification, backup, replacement, launch, health-marker і rollback.
- Settings отримав STABLE/BETA/MANUAL, check/download/restart actions, release notes, auto-check/install policy та package paths; updater JAR включено в desktop packaging.
- Local deterministic security/staging/rollback tests green; Windows packaged update acceptance залишається зовнішнім gate.

## 21. V32: Readwise annotation connector

- Додано вузькі `AnnotationRemoteExportPort` / state checkpoint та bounded `ExportAnnotationsToReadwiseUseCase`.
- `V67__annotation_remote_export_state.sql` зберігає тільки remote/id/fingerprint/time; token — лише `SecretStore`.
- Readwise adapter використовує stable `highlight_url`, bounded batches, redacted auth failures та retry тільки для idempotent-safe 429/5xx cases.
- Integration Center має configure/test/export/clear; Annotation Manager — export за selected/filtered book scope.
- Local delete каскадно прибирає checkpoint, але V1 не видаляє remote highlight.

## 22. V33: Local Recommendations

- Додано metadata-only `BookSimilarityPolicy` і bounded `FindSimilarBooksUseCase`.
- Score deterministic/explainable; self/deleted/empty-metadata candidates не повертаються; cloud/AI/embeddings/vector DB не використовуються.
- Book Details показує до 8 схожих книг з причинами й навігацією за stable book id.

## 23. V34: exact duplicate comic pages

- Додано page-level exact scanner поверх `BookResourcePort`/safe comic page ordering, окремо від whole-book duplicate detector.
- SHA-256 + length, 64 MiB compressed page bound, cancellation, corrupt-entry continuation та deterministic grouping покриті tests.
- Book Details додає report-only scan для CBZ/CBR/CB7 з page number, archive entry та exact kind; auto-delete відсутній.

### V30–V34 local closure evidence — 2026-10-02

- Full offline Linux/Xvfb `clean verify`: **21/21 reactor modules**, BUILD SUCCESS, 2:36.
- Surefire: **2065 tests / 0 failures / 0 errors / 18 skipped**; Architecture 19/19; E2E 15/15.
- UI action inventory: **484/484** (273 FXML + 205 programmatic + 6 Web); behavior: 483 automated + 1 justified exclusion.
- Architecture/static/supply-chain/UI/Web/help localization gates: PASS; SQLite migrations: 67 immutable files.
- Real Kobo/Windows/Readwise/browser and V33/V34 real-data performance/soak remain external gates.

## 24. V34 post-real-data acceptance — 2026-10-02

Після локального V30–V34 checkpoint виконано великий реальний прогін на `flibusta_online_fb2.inpx`: 707 154 records імпортовано без errors, повторний import ідемпотентний, V65→V67 migration на 1.59-GB DB пройдена без втрати counts, `quick_check` та full `integrity_check` — `ok`. Lucene перебудував 571 947 active documents; corrected production-order restart probe підтвердив перевикористання sealed індексу, second startup 214 ms.

Real-data V33 виявив неприйнятний unbounded full-catalog candidate path. Реалізацію звужено до bounded metadata projection через наявні SQLite relations; додано keyword-only candidates, normalized series matching, відповідність partial-index predicate `idx_books_active_series_normalized` та менший bounded candidate set для типових 8–10 results. Scoring policy залишився deterministic/explainable. Latest 5-sample real-data run: 329–1387 ms, recommendation UI завантажується asynchronously і не блокує initial Book Details render.

V34 пройшов 1000-page generated CBZ stress: 1000/1000 scanned, 100 exact groups, 0 failures, 6.270 s; real CBR/CB7 corpus у сесії відсутній. Три надані FB2 ZIP archives пройшли real-file reader probe.

Після production-fix виконано exact-current full offline Linux/Xvfb `clean verify`: **2071 tests / 0 failures / 0 errors / 22 skipped; 21/21 SUCCESS**. Чотири додаткові skipped — opt-in real-data probes, які окремо запускалися з явними шляхами до real inputs. UI action inventory лишився 484/484; behavior registry після reviewed evidence refresh — 483 automated + 1 justified exclusion; architecture/static/supply-chain/localization/help gates PASS.

Live external gates не маскуються локальними результатами: physical Kobo, packaged Windows updater/Calibre/DPI/DPAPI/signing, real Readwise account, unrestricted supported-browser PWA та candidate-bound GitHub/distribution залишаються окремими acceptance. Поточний Chromium у Linux environment керується admin policy `URLBlocklist: ["*"]`, тому навіть localhost browser E2E тут заблокований середовищем.

## 25. Deep project audit і external-test cleanup — 2026-10-02

Перед передачею V34 на зовнішнє тестування виконано окремий повний source-tree audit поверх post-real-data checkpoint.

Виявлено й виправлено три concrete quality gaps:

- у `KoboSyncPort` залишався невикористаний `Optional` import;
- standalone `myhomelib-updater` був у Maven reactor, але не входив до Python/ArchUnit architecture registries; coverage розширено з 16 до **17/17 runtime/product modules**, додано JDK-only updater rule;
- знайдено один exact cross-module method clone у JSON escaping; логіку консолідовано в малий `shared.text.JsonStrings` і повторно використано annotation/OPDS/Kobo renderers.

Документаційне дерево очищено: історичні root-level V29/V30/V34 checkpoint status файли перенесено до `docs/history/records/checkpoints/`; корінь знову містить лише канонічні `README.md`, `ARCHITECTURE.md` та build/runtime scripts. Заповнено відсутній окремий record для historical Iteration 54 на основі вже наявного consolidated `HISTORY.md`.

Додано fail-closed `tools/project-hygiene-check.py`, який контролює reactor module paths, duplicate Java FQCN, Flyway version uniqueness у кожному migration location, усі локальні Markdown links, exact duplicates у `docs/current`/`docs/release`, root historical-doc placement і build/cache trash. Source packager тепер також запускає implementation completeness та architecture-module coverage checks.

Фінальні exact-current Maven/test/gate числа для зовнішнього candidate записуються в `docs/release/CURRENT-VALIDATION.md` після останнього clean verify; external Windows/Kobo/Readwise/PWA gates залишаються окремими й не підміняються локальним audit PASS.

### Deep-audit closure evidence

Фінальний audit-candidate після cleanup пройшов повний Maven `clean verify`: **2074 tests / 0 failures / 0 errors / 22 skipped; 21/21 reactor projects SUCCESS**, Architecture **20/20**, E2E **15/15**. Додаткові три тести відносно post-real-data baseline походять із deep-audit hardening (Shared JSON escaping regression та updater architecture coverage).

`implementation-completeness-check.py` підтвердив 0 unused explicit imports, 0 TODO/FIXME/unsupported production markers, 0 empty public/protected methods, 0 unused Spring constructor dependencies, 0 unconsumed use cases/output ports та 0 exact cross-file method clones ≥180 символів. `project-hygiene-check.py` підтвердив 0 duplicate Java FQCN, 0 broken local Markdown links, унікальні Flyway versions у кожній migration location, відсутність exact duplicate current/release docs і чисте source tree.

Project documentation додатково консолідовано: benchmark instructions перенесено в `docs/current/SMART-COLLECTIONS-PERFORMANCE.md`, language-pack specification — в `docs/current/LANGUAGE-PACKS.md`, plugin-sample guide — в `docs/plugin-sdk/SAMPLES.md`. Поза `docs/` лишаються тільки два канонічні root entry points та runtime F1-help assets.

### External-test finalization — exact ZIP verification

Фінальний source candidate додатково перевірено не з робочого дерева, а після чистого розпакування ZIP. Під `xvfb-run`, UTF-8 locale та offline Maven repository exact archive пройшов повний `mvn -o clean verify`: **21/21 reactor projects SUCCESS; 2074 tests / 0 failures / 0 errors / 22 skipped; BUILD SUCCESS; 2:36**. Це закриває попередній формальний gap, де exact-candidate прогін переривався лімітом інструмента.

Під час фіналізації `external-acceptance-readiness.py` виявився застарілим: structural gate ще вимагав V26 markers (`2026-09-29`, `1960`, `18 skipped`, старі current-validation IDs). Gate синхронізовано з V34 evidence (`2026-10-02`, V30–V34, 21/21, 2074 tests, 22 skipped, OPEN_WINDOWS/OPEN_EXTERNAL), додано regression, що fail-closed ловить stale test count. Повний readiness regression bundle після fix: **READY_FOR_LIVE_EVIDENCE**, при цьому MHL-010/011/012/017/018/019 залишаються OPEN_EXTERNAL і не вважаються закритими локальними тестами.

## 26. V34 external-test revision: non-blocking Book Details selection — 2026-10-02

Під час перевірки завантаженої з онлайн-бібліотеки книги відтворено UX/runtime дефект: звичайне виділення книги запускало rich Book Details analysis як collection-scoped `QUERY`. Для archive-backed FB2/EPUB `ResolveBookContentUseCase` міг локально матеріалізувати entry у temp-файл для inspection. HTTP download повторно не виконувався, але global library lease блокував несумісні операції; status bar показував фонову операцію без запису в Operation Center, а після неї відновлював попередній текст про завершення завантаження.

Виправлено без зміни V34 product scope: basic metadata тепер показуються одразу; passive details analysis, cover extraction і similar-books enrichment виконуються managed background tasks без `LibraryOperationCoordinator` lease. Stale selection/collection guards збережено. Додано `BookDetailsSelectionNonBlockingContractTest` та regression для plain presentation submit, що доводить можливість паралельної library operation.

Targeted regression: **12/12 PASS**. Повний offline Linux/Xvfb `clean verify` після fix: **2076 tests / 0 failures / 0 errors / 22 skipped; 21/21 reactor projects SUCCESS; Architecture 20/20; E2E 15/15; BUILD SUCCESS; 2:44**.


## 27. External Windows findings після V34 R1 — 2026-10-02

Після передачі R1 на ручне Windows-тестування зафіксовано відкриті дефекти/покращення для наступного candidate: conversion не повинен автоматично промотувати новий EPUB у primary artifact; повторна навігація до вже вибраного автора має працювати після переходів в Operations/Reader; remote-only книга має показувати штатний стан `Книгу не завантажено` замість технічної inspection-помилки. Окремо заплановано same-environment memory A/B та consistency audit operation/status model. Ці пункти **не позначені як виправлені** у цьому handoff і мають RED→fix→regression цикл перед наступним release candidate.

## 28. V34 External Test R2 — post-R1 P0/P1 regression — 2026-10-03

Після Windows/live findings R1 виконано наступний regression pass без відкриття V35 scope.

- Conversion artifact identity зроблено стабільним для book + target format: повторна конвертація не створює duplicate artifact rows, а preferred/primary representation не змінюється без explicit user action. Чинні `upsertArtifact(..., false)` та V63 repair semantics збережені.
- `NavigationPanelController` повторно dispatch-ить клік по already-selected author row, тому `author → Operations/Reader → same author` більше не залежить від зміни selected-item state. Missing author має локалізований fail-safe state.
- Remote-only Book Details тепер є штатним станом `Книгу не завантажено`: parser/inspection не запускається, а для remote collection доступний `Завантажити`; після download details автоматично переходять у local state. `MISSING`/`CORRUPT` local artifacts не маскуються як remote-only.
- Cancellable collection-scoped UI flows (Book Workspace, Reader, Content Search) перехоплюють synchronous library-operation conflict / executor rejection; user-facing conflict локалізований через `UiExceptionSupport`.
- UI action inventory після нової Download action: **485/485**, action-specific behavior **484 automated + 1 justified exclusion**, documentation mapping **485/485**; UK/EN/BG desktop catalog — **3746** synchronized keys.
- Full exact-current UI suite: **370/370 PASS**. Application **509/509**, Infrastructure **681/681** (179/179 test classes), Reader **150/150**, Bootstrap **57/57**, E2E **15/15**, Architecture **20/20** у bounded regression runs; failures/errors 0 у завершених runs.
- Synthetic 100k memory baseline зафіксовано; Hikari/SQLite/cache/Xmx production limits не зменшувалися без same-environment evidence.
- Один uninterrupted root `clean verify` у цьому execution environment не завершився через зовнішній 5-minute tool timeout; до termination Maven test failure не було. Це обмеження зафіксовано, а не видано за green gate.

External Windows/Kobo/Readwise/PWA/real CBR-CB7/production-soak та same-machine memory A/B gates залишаються відкритими. Деталі: `docs/release/POST-R1-R2-VALIDATION-2026-10-03-UA.md`.


## 29. V34 External Test R3 — post-R2 invariant hardening — 2026-10-03

Після R2 додатково закріплено primary-artifact policy на рівні API: з `ConvertBookUseCase.Request` видалено obsolete `makePreferred`, тому conversion use case не може навіть декларативно запитати автоматичне promotion derived artifact. Додано окремий regression `author → Reader → same author`.

Exact-current split-complete regression: **2082 tests / 0 failures / 0 errors / 22 skipped**; UI **371/371**, Infrastructure **681/681**, Reader **150/150**, Application **509/509**, Bootstrap **57/57**, E2E **15/15**, Architecture **20/20**. JavaFX suites запускалися під Xvfb; Unicode-path tests — у `C.UTF-8`. UI action/localization/static-release/supply-chain/implementation-completeness gates PASS.

External Windows/Kobo/Readwise/PWA/real CBR-CB7/soak і same-machine memory A/B залишаються окремими live gates. Деталі: `docs/release/POST-R2-R3-VALIDATION-2026-10-03-UA.md`.


## 30. V34 External Test R4 — status/test-runtime hardening — 2026-10-03

- Download completion status переведено на transient semantics: старе `Завантажено: ...` не відновлюється після пізнішої background operation, але новий foreground status не губиться.
- Bootstrap та E2E отримали explicit Mockito `-javaagent`; static release gate контролює цей контракт для всіх Mockito test modules.
- Один uninterrupted root offline `clean verify`: **2084 tests / 0 failures / 0 errors / 22 skipped; BUILD SUCCESS; 02:27**.
- Production memory limits у R4 не змінювалися без A/B. Деталі: `docs/release/POST-R3-R4-VALIDATION-2026-10-03-UA.md`.

## 31. V34 External Test R5 — evidence-first memory A/B — 2026-10-03

На наданому `flibusta_online_fb2.inpx` побудовано 707 154-book SQLite DB і виконано same-environment A/B окремими JVM. Hikari interactive pool **4 → 2** дав у warm interactive probe середнє зниження RSS приблизно **17.31%**; page/author/statistics median latency змінилася лише приблизно на **+0.56% / +2.44% / +1.56%**, parallel-4 — **−6.43%**. Fresh 707 154-record import з pool=2 завершився без errors за **54.683 s** проти **57.238 s** baseline; counts і `quick_check` збережені.

Окремий SQLite profile `cache 16 → 8 MiB`, `mmap 64 → 32 MiB` відхилено через material author-query regression, тому ці production values, BookCache/CoverCache та `-Xmx1024m` не змінювалися. Production `DataSourceConfig` прийняв тільки Hikari max pool **2**.

Додано opt-in real-DB tuning probe і raw JSON evidence. UI behavior gate посилено: observational `*ProbeTest.java` більше не використовується як action-specific UI evidence, щоб generic test words не створювали false evidence. Reviewed registry: **484 automated + 1 justified exclusion = 485/485**.

Exact-current root offline `clean verify`: **2087 tests / 0 failures / 0 errors / 23 skipped; BUILD SUCCESS**. Details: `docs/release/POST-R4-R5-VALIDATION-2026-10-03-UA.md`.


## 32. V34 External Test R6 — post-R5 live evidence harness — 2026-10-04

Без зміни production Java behavior додано candidate-bound/fail-closed acceptance tooling для незакритих після R5 live gates: Windows repeated-selection/Book Details, Calibre >5 min/cancel/process-tree/locks/batch, packaged updater/installer/portable/DPAPI/DPI/rollback/signing, physical Kobo, real Readwise, unrestricted-browser PWA, real CBR/CB7 та target Windows memory/soak. Final aggregator вимагає exact candidate SHA, повний набір check IDs і реальне evidence для кожного PASS; memory soak має бути не коротшим за 3600 s.

Локально checker/harness/static-release/hygiene/build/architecture/supply-chain gates PASS; targeted Calibre **7/7** і batch conversion **3/3** PASS. Exact-current root offline `clean verify`: **2087 tests / 0 failures / 0 errors / 23 skipped; BUILD SUCCESS**. Усі live/platform/provider gates залишаються OPEN до фактичного запуску. Деталі: `docs/release/POST-R5-R6-VALIDATION-2026-10-04-UA.md`.

## 33. V34 External Test R7 — memory evidence hardening — 2026-10-04

Production Java і memory defaults не змінювалися. Focused lifecycle/resource-release suite **16/16 PASS** підтвердив закриття Hikari/collection-cache/FB2 temp-resource/details lifecycle; controlled 100k catalogue + 8 MiB Reader probe показав final **224 B** retained delta після 20 open/close cycles у цій JVM-вибірці.

Windows target-memory harness посилено: `jstat` GC count/time, handles/threads, MEM-10 peak та first→last drift стали обов’язковими; final checker fail-closed відхиляє неповний memory evidence. Exact-current root offline `clean verify`: **2087 tests / 0 failures / 0 errors / 23 skipped; 21/21 reactor SUCCESS; BUILD SUCCESS**. Target Windows 1–2 h soak лишився external. Деталі: `docs/release/POST-R6-R7-VALIDATION-2026-10-04-UA.md`.

## 34. V34 External Test R8 — release-evidence consistency hardening — 2026-10-04

Після freeze R7 знайдено не runtime defect, а evidence inconsistency: `CURRENT-VALIDATION.md` містив старе observational **744 bytes**, тоді як final R7 memory JSON і validation мали **224 B**. Final JSON promoted у `docs/release/PERFORMANCE-R7-100K-MEMORY-2026-10-04.json`, current summary виправлено.

`external-acceptance-readiness.py` більше не вважає старі historical tokens у довгому CURRENT документі доказом current state: gate зв’язує поточний R-revision з `POST-R<n-1>-R<n>-VALIDATION-*` і звіряє current Maven baseline aggregate. Negative regressions закривають stale aggregate та mismatched evidence binding. Production Java/memory limits не змінені; R7 one-shot Maven baseline лишається чинним, а R8 affected Python/release gates перевіряються окремо; external gates залишаються відкритими. Деталі: `docs/release/POST-R7-R8-VALIDATION-2026-10-04-UA.md`.

## 35. V34 External Test R9 — live-acceptance evidence cohesion hardening — 2026-10-04

Без зміни production Java/POM/runtime behavior закрито два fail-open ризики post-R5 final aggregation. Windows functional і memory reports тепер повинні мати один `acceptanceSessionId`, однакові host/user fingerprints та OS identity; змішане evidence різних Windows sessions/hosts відхиляється. `--external-final` переведено на повну перевірку реального `myhomelib-7.1-final-external-acceptance` schema/backlog/evidence/candidate-binding contract, тому shallow `overall=PASS` JSON більше не може закрити gate.

RED→GREEN regressions, readiness, static-release, architecture, implementation-completeness, UI/localization/help, supply-chain, hygiene/build-check — PASS. R9 не змінює memory defaults. Новий one-shot Maven PASS не заявляється: локальні exact-R9 запуски були перервані зовнішнім ~5-minute execution limit до reactor summary; frozen R7 **2087/0/0/23, BUILD SUCCESS** залишається незмінним Java baseline до exact-R9 CI/Windows run. Деталі: `docs/release/POST-R8-R9-VALIDATION-2026-10-04-UA.md`.

## 36. V34 External Test R10 — live-evidence integrity hardening — 2026-10-04

Без зміни production Java/POM/runtime behavior кожний PASS evidence file у Windows functional/memory та Kobo/Readwise/PWA/comics reports прив’язано власним SHA-256. MEM-10 final aggregation читає raw samples і звіряє фактичну wall-clock тривалість, кількість/час samples, GC/elapsed monotonicity, peaks та first→last deltas із summary. Negative regressions відхиляють скорочений soak, tampered summary та замінений evidence file. Деталі: `docs/release/POST-R9-R10-VALIDATION-2026-10-04-UA.md`.

## 37. V34 External Test R11 — Windows runtime-binary binding hardening — 2026-10-04

Windows desktop acceptance тепер фіксує SHA-256 встановленого launcher; functional acceptance звіряє launcher path/bytes із candidate-bound desktop report; memory soak звіряє PID executable path/SHA-256 із accepted functional launcher. Final aggregation перевіряє повний candidate package → installed launcher → functional report → memory process chain. Exact-current root offline `clean verify`: **2087 tests / 0 failures / 0 errors / 23 skipped; 21/21 reactor SUCCESS; BUILD SUCCESS**. Деталі: `docs/release/POST-R10-R11-VALIDATION-2026-10-04-UA.md`.

## 38. V34 External Test R12 — FB2 Book Details lazy-image performance — 2026-10-04

На реальному `729586 Демон. Пожиратель душ #01-18.fb2.zip` підтверджено, що ZIP I/O не є bottleneck; основна зайва робота була в eager decode/temp-resource materialization усіх FB2 `<binary>` image payload під час пасивного Book Details. Для FB2/FBD inspection image payload тепер відкладається до explicit `openImage`, а повторні image opens використовують один lazy-loaded document. На 17 736 920-byte FB2 warm passive inspection скоротився приблизно з **~0.95 s** до **0.17–0.26 s** у same-environment Linux/JDK 21 probe. Exact-current root offline `clean verify`: **2088 tests / 0 failures / 0 errors / 23 skipped; 21/21 reactor SUCCESS; BUILD SUCCESS; 02:30**. Memory defaults не змінені. Деталі: `docs/release/POST-R11-R12-VALIDATION-2026-10-04-UA.md`.
