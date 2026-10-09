# MyHomeLib — актуальний user-facing functional audit V34

Дата: **2026-10-02**  
Product version: **MyHomeLib Enterprise 8.0.0**  
Scope: V30–V34 post-real-data source candidate перед зовнішнім acceptance.

## Висновок

Поточний user-facing surface повністю інвентаризований. Structural wiring, action inventory, action-specific behavior evidence, user-function mapping, localization/help і основні E2E/contract paths перевіряються автоматично. Live Windows, physical Kobo, real Readwise та unrestricted-browser PWA не прирівнюються до локального PASS і залишаються окремими gates.

Deep audit 2026-10-02 додатково перевірив source completeness, imports, duplicate implementation, architecture-module coverage, documentation placement і local Markdown links. Виявлені дефекти якості виправлено до формування external-test archive.

## Поточний UI inventory

`tools/user-facing-functional-audit.py`:

- FXML files: **30**;
- FXML event bindings: **273**;
- unique controller handlers: **255**;
- controllers: **30**;
- missing FXML handler references: **0**;
- CoreActions: **14/14 registered**;
- workspace help routes: **26**, unmapped: **0**;
- known help topics: **25**, missing default resources: **0**;
- programmatic action classes: **35**;
- programmatic `setOnAction` calls: **205**.

Machine-readable action contract:

- total current actions: **484/484 mapped**;
- composition: **273 FXML + 205 programmatic + 6 Web**;
- action-specific behavior: **483 automated + 1 justified exclusion = 484/484**;
- `user_function_ref`: **484/484** actions resolve to **31** canonical flows in `USER-FUNCTIONS-UA.md`.

`tools/functional-regression-check.py` також фіксує 30 FXML files, 255 retained FXML bindings, 475 FXML ids і 32 critical behavior ratchets.

## Source/code completeness

`tools/implementation-completeness-check.py` перевіряє production Java tree і в поточному deep-audit state має такі вимоги:

- 0 TODO/FIXME/unsupported markers у production code;
- 0 empty public/protected methods;
- 0 unused explicit imports;
- 0 unused Spring constructor dependencies;
- 0 unconsumed use cases / unimplemented output ports;
- 0 exact cross-file method clones >=180 chars.

Під час цього audit checker реально знайшов зайвий `Optional` import у `KoboSyncPort`; import прибрано. Також було знайдено один exact JSON-escaping clone між annotation export і Kobo protocol rendering. Його усунуто через `shared.text.JsonStrings`, а інші OPDS JSON renderers переведено на той самий primitive.

## Localization та help

- UK/EN/BG JavaFX catalogues синхронізовані;
- visible FXML attributes використовують stable localization keys;
- hardcoded alphabetic JavaFX UI literals у контрольованих sinks: 0;
- Web/OPDS localization catalogues синхронізовані;
- workspace F1 help routes не мають missing current topic;
- current action/help/localization gates не замінюють platform live acceptance.

Runtime help resources (`.md`/`.txt`) є product assets, а не project documentation. Деякі пари навмисно дублюють текст для backward-compatible help lookup; project-level duplicate-doc hygiene застосовується до `docs/current/` і `docs/release/`, а не до runtime compatibility assets.

## V30–V34 user-facing scope

- **V30 Kobo Sync:** Integration Center enable/status/credential rotate/revoke/sync; deterministic protocol/progress contracts; physical device = LIVE.
- **V31 auto-update:** Settings channel/check/download/restart-and-update; signed fail-closed verification + standalone updater; packaged Windows = WIN/LIVE.
- **V32 Readwise:** configure/test/export all/selected/filtered; token only in `SecretStore`; real account/rate-limit/network = LIVE.
- **V33 recommendations:** Book Details local deterministic/explainable similar-books query; real 707,154-record profiling виконано, target-machine long soak окремо.
- **V34 comic duplicate pages:** report-only exact page duplicate scan для CBZ/CBR/CB7; synthetic 1000-page CBZ stress виконано; real large CBR/CB7 corpus ще зовнішній input.

## Real-data evidence

На наданому `flibusta_online_fb2.inpx` виконано fresh/re-import для **707,154** records, V65→V67 migration, Lucene rebuild/reopen, restart, V33 profiling і DB integrity. Повний evidence зберігається в `docs/release/REAL-DATA-ACCEPTANCE-V34-2026-10-02.md`.

Три надані FB2 ZIP archives пройшли real-file reader probe. V34 synthetic comic stress: 1000/1000 pages, 100 duplicate groups, 0 failures.

## External acceptance boundary

Залишаються зовнішні gates, які не можна чесно закрити локальним Linux/Xvfb suite:

- Windows installer/portable/upgrade, Calibre/process teardown/file locks, DPI/DPAPI/signing;
- physical Kobo first sync/open/read/progress/metadata/reconnect/revoke;
- real Readwise account + rate-limit/partial-network behavior;
- unrestricted supported browser для PWA install/offline/reconnect/auth-revocation;
- candidate-bound GitHub/SBOM/SCA/CodeQL/distribution evidence;
- real large CBR/CB7 corpus і довший target-machine soak.

Exact-current Maven/test totals після останнього source cleanup фіксуються тільки в `docs/release/CURRENT-VALIDATION.md`; цей документ навмисно не дублює число, доки фінальний `clean verify` не завершено.
