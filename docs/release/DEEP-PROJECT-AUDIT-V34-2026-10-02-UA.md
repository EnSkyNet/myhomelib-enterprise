# MyHomeLib V34 — фінальний deep project audit перед зовнішнім тестуванням

**Дата:** 2026-10-02  
**Candidate:** MyHomeLib Enterprise 8.0.0 / V34 external-test source candidate  
**Мета:** перевірити source tree, imports/dependencies, дублювання, посилання, документацію, історію, architecture/user-function coverage і чистоту пакета перед фізичним/Windows/provider acceptance.

## 1. Підсумок

Статус: **PASS для локального source/offline boundary**.

Після audit-fixes виконано повний Maven `clean verify`: **21/21 reactor projects SUCCESS; 2074 tests / 0 failures / 0 errors / 22 skipped**, Architecture **20/20**, E2E **15/15**.

Зовнішні gates (physical Kobo, Windows packaged updater/Calibre/DPI/DPAPI/signing, real Readwise, unrestricted supported browser PWA, candidate-bound GitHub/distribution) залишаються окремими та не вважаються пройденими локальним PASS.

## 2. Imports, compilation і dependency declarations

- Java source/test files у source tree: **2105**.
- Production Java files у implementation-completeness scan: **1282**.
- Missing imports / unresolved symbols: **0** за exact-current full reactor compilation.
- Unused explicit imports: **0**.
- Unused Spring constructor dependencies: **0**.
- Unconsumed use cases / unimplemented output ports: **0**.
- Maven POM files: **21**.
- Duplicate dependency declarations у межах одного POM: **0**.
- Duplicate plugin declarations у межах одного POM: **0**.

Під час аудиту знайдено і видалено один unused `Optional` import у `KoboSyncPort`.

## 3. Дублювання і structural hygiene

- Duplicate Java FQCN: **0**.
- Exact duplicate production Java files: **0**.
- Exact cross-file method clones ≥180 chars: **0** після cleanup.
- TODO/FIXME/unsupported production markers: **0**.
- Empty public/protected methods: **0**.
- Current/release byte-identical duplicate docs: **0**.
- Flyway migration versions: унікальні в кожній migration location; production SQLite chain — **67 migrations**.

Audit знайшов один exact cross-module JSON escaping clone. Його консолідовано у вузький `shared.text.JsonStrings` з regression tests; annotation export, OPDS/Web progress JSON, OPDS2 і Kobo renderer використовують один primitive.

### Reviewed resource-name collisions

Є рівно два навмисні classpath resource-name collisions:

1. `META-INF/services/com.myhomelibcorp.plugin.api.PluginEntrypoint` — окремі service descriptors у двох **окремих plugin artifacts** (`converter-calibre-plugin` та developer-only `plugin-samples`), тому runtime merge не є source duplication дефектом.
2. `update/trusted-keys.properties` — однаковий public trust-key set присутній у main Infrastructure і standalone JDK-only updater, бо updater не має internal product-module dependency. `project-hygiene-check.py` тепер fail-closed перевіряє semantic equality key entries, щоб ці копії не могли тихо розійтися.

Будь-який новий resource path collision тепер є hygiene failure до окремого review.

## 4. Architecture

- Root reactor: **20 child modules / 21 projects**.
- Runtime/product architecture registry: **17/17 modules**.
- Python architecture check: PASS, graph acyclic і hard boundaries intact.
- ArchUnit: **20/20 PASS**.
- Standalone `myhomelib-updater` тепер явно входить у architecture registry і має JDK-only/no-product-dependency rule.
- UI direct-Infrastructure/JDBC/Lucene regressions не з'явилися; existing debt залишається ratcheted, а не розширеним.

До аудиту updater був у Maven reactor, але не був повністю класифікований у Python/ArchUnit registries. Це виправлено.

## 5. UI/user functions/localization

- UI action inventory: **484/484** — 273 FXML + 205 programmatic + 6 Web.
- Action-specific behavioral evidence: **483 automated + 1 justified exclusion = 484/484**.
- `user_function_ref`: **484/484** actions → **31** canonical flows у `docs/current/USER-FUNCTIONS-UA.md`.
- UI localization: **3742 synchronized keys**, shipped `uk/en/bg`, hardcoded alphabetic JavaFX UI-sink literals: 0.
- Web localization: **87 synchronized keys**, 82 referenced by Web/OPDS code; raw hardcoded Cyrillic renderer/sendText literals: 0.
- Help: **75 Markdown pages**, UK/EN/BG genre catalogs aligned (**335** entries кожен).

## 6. Документація та історія

Канонічна project documentation зібрана під `docs/`:

- `docs/current/` — current product/architecture/operations/development/user-function contracts;
- `docs/release/` — exact-current validation, real-data evidence, external runbook/test plan, deep audit;
- `docs/history/` — повна consolidated history + **247** exact records, archived handoffs/source notes/artifacts;
- `docs/plugin-sdk/` — authoring, compatibility та sample guide.

Поза `docs/` навмисно залишаються тільки:

- `README.md` — canonical repository entry point;
- `ARCHITECTURE.md` — canonical current architecture entry point;
- `myhomelib-ui/src/main/resources/help/**` — runtime F1-help assets, тобто продуктова частина, а не project documentation.

Cleanup у цьому audit:

- historical V29/V30/V34 checkpoint status files перенесені з root у `docs/history/records/checkpoints/`;
- benchmark README зміст консолідовано в `docs/current/SMART-COLLECTIONS-PERFORMANCE.md`;
- language-pack specification перенесено в `docs/current/LANGUAGE-PACKS.md`;
- plugin sample README перенесено в `docs/plugin-sdk/SAMPLES.md`;
- missing historical Iteration 54 record відновлено з consolidated history evidence.

## 7. Посилання

### Local Markdown

Fail-closed scan перевірив **21** local Markdown links; broken paths: **0**; repository-escaping links: **0**. Поточні local links не використовують fragment anchors, тому окремих broken-anchor cases немає.

### External references

На 2026-10-02 вручну revalidated усі **6 unique external HTTP(S) references**, що зустрічаються в project/history Markdown:

- `https://github.com/janeczku/calibre-web/blob/master/cps/kobo.py` — exists;
- `https://github.com/janeczku/calibre-web/blob/master/cps/kobo_auth.py` — exists;
- `https://github.com/new-usemame/Calibre-Web-NextGen` — exists;
- `https://alex80.github.io/mhl/download/inpx/` — exists;
- `https://alex80.github.io/mhl/update/` — exists;
- `https://play.google.com/store/apps/details?id=com.neverland.alreaderext` — exists.

External availability є time-dependent і не може бути гарантована самим source archive після дати аудиту.

## 8. Release/source-tree gates

PASS:

- `project-hygiene-check.py`;
- `implementation-completeness-check.py`;
- `static_release_check.py`;
- `architecture-check.py` + architecture module coverage;
- `supply-chain-policy-check.py`;
- UI inventory + behavioral coverage + user-functions mapping;
- UI/Web/help localization checks;
- full root Maven `clean verify`;
- exact unpacked external-test candidate under Linux/Xvfb: **21/21 reactor projects SUCCESS**, **2074 tests / 0 failures / 0 errors / 22 skipped**, `BUILD SUCCESS`, **2:36**.

Production Boot JAR exact-current build evidence:

- size: **65,631,803 bytes**;
- SHA-256: `4b4952d7900177d0493f2606863a72c01e67e63e9f0e0df59ce55876878065df`.

## 9. Реальні дані

Real-data acceptance залишається чинним для цієї code line: 707,154-record INPX, V65→V67 migration, full SQLite integrity, Lucene rebuild/reopen, V33 bounded recommendations, V34 1000-page CBZ stress і три supplied FB2 ZIP probes. Деталі: `REAL-DATA-ACCEPTANCE-V34-2026-10-02.md`.

## 10. Що ще має пройти зовні

Перед public release обов'язково окремо:

- physical Kobo first-sync/read/progress/reconnect/revoke;
- Windows x64 installer + portable + upgrade + updater rollback + Calibre/process/file-lock/DPI/DPAPI;
- real Readwise account/rate-limit/partial failure;
- unrestricted supported-browser PWA install/offline/reconnect/revocation;
- large real CBR/CB7 corpus;
- exact-candidate GitHub/SBOM/SCA/CodeQL/signing/distribution evidence.

Цей audit дозволяє перейти до зовнішнього тестування; він не підміняє ці external/live gates.

## 11. Exact archive finalization

Після формування external-test source candidate його розпаковано в окремий чистий каталог і виконано повний offline Linux/Xvfb reactor: **21/21 SUCCESS; 2074 tests / 0 failures / 0 errors / 22 skipped; BUILD SUCCESS; 2:36**. JavaFX contract tests, що закономірно не стартують без DISPLAY, пройшли під `xvfb-run`; це зафіксовано як обов'язкова Linux headless build умова, а не як production workaround.

Фінальний audit також виявив stale tooling contract у `external-acceptance-readiness.py`, який досі вимагав V26/V29 validation markers. Structural tokens оновлено до V34 exact-current evidence і додано regression test. `--run-regressions` після виправлення повертає **READY_FOR_LIVE_EVIDENCE**; зовнішні gates залишаються OPEN.

## 12. External-test revision addendum — Book Details selection

Після первинної deep-audit finalization відтворено runtime/UX дефект для вже завантаженої онлайн-книги. Повторного HTTP download не було: selection запускав `BookDetailsAnalysisService`, який через `ResolveBookContentUseCase` міг локально витягувати archive entry у temp-файл для rich inspection. Оскільки цей passive enrichment був оформлений як collection-scoped `QUERY`, `LibraryOperationCoordinator` робив бібліотеку busy; status bar показував background operation, а Operation Center не мав відповідного journal entry. Після завершення відновлювався попередній foreground text про download completion.

Revision переводить passive Book Details analysis, cover extraction і similar-books lookup на managed unscoped presentation tasks, залишаючи stale-result/lifecycle guards. Basic catalog metadata показуються синхронно до rich analysis. Нові regression tests перевіряють selection contract і відсутність global library lease для plain presentation task.

Exact-current verification після revision: **21/21 reactor projects SUCCESS; 2076 tests / 0 failures / 0 errors / 22 skipped; Architecture 20/20; E2E 15/15; BUILD SUCCESS; 2:44**. Production Boot JAR: **65,631,747 bytes**, SHA-256 `f2cfc63e4f33ab2b0c0585c2bea1b9dc8b34ee468ed9c5ff52d9af8ebba94c06`.

