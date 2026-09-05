# MyHomeLib Enterprise — refactoring & hardening report

Дата: 2026-09-03  
Базова версія: 7.1.0  
Базовий commit робочої копії: `d789bb7`  
Фінальний source-refactoring commit перед цим звітом: `f500d60`

## 1. Що зроблено

Проведено послідовний refactoring/hardening без повного rewrite. Основні зміни розбиті на окремі перевірені commits.

### Дані, SQLite та великі каталоги

- Unicode-нормалізація пошуку авторів, включно з кирилицею.
- Keywords переведені з runtime `WITH RECURSIVE` parsing на нормалізовані `keywords` + `keyword_books` з backfill/indexes.
- Pageable/search book rows переведені на lightweight projection; повна модель завантажується лише там, де вона реально потрібна.
- Author navigation переведена на keyset pagination.
- Main catalog TITLE navigation переведена на двонаправлену keyset pagination.
- Author/Group/catalog continuation повторно використовує відомий total і не виконує `COUNT(*)` на кожній сторінці.
- Додано індекс для normalized-language facet/filter (`V47`).
- Додано вузький partial covering index активних книг для exact count (`V48`).
- Persistence errors більше не маскуються як `0`, `List.of()`, `Optional.empty()` у критичних DB/Lucene paths.

### Local import та проблеми, відтворені на реальних книгах

- FB2 cover parser більше не бере останню картинку книги замість cover; declared `<coverpage>` має пріоритет, fallback — перше валідне зображення.
- Виправлено Book Details/Inspection для FB2 з named main body (`<body name="...">`): довільний named body більше не вважається footnotes body.
- Local author normalization об'єднує типові перестановки `first-name/last-name` і розділяє типові concatenated creator strings на окремих авторів.
- Directory rescan оновлює існуючі метадані (`updateExisting=true`) зі збереженням user state.
- ZIP archive entry handling більше не залежить від filesystem locale; підтримано Windows-1251/CP866 legacy names.
- Пошкоджений archive/resource більше не маскується як нормальна відсутність entry.

### Reader

- Додана структурована semantic style model: title/chapter/section/subtitle/epigraph/quote/poem/poem-author/text-author/annotation/link/footnote/strong/emphasis/code.
- Для semantic styles підтримані font/size/weight/color/alignment/spacing before/after.
- Reader settings/preferences зберігають semantic styles структуровано; `customCss` не є основним API.
- FB2 semantic parsing розширений для chapter/section title, subtitle, epigraph, cite/quote, verse/poem, authors, annotation, footnotes, strong/emphasis.
- Large image resources залишаються bounded: великі ресурси переходять у temp storage, а failure temp-storage не перетворюється на unbounded heap fallback.
- EPUB metadata parsing та Reader EPUB parsing уніфіковані й hardened.

### Async/UI/lifecycle

- Search, Author, Book Details, Reader, Dashboard, Statistics та online-update UI захищені collection-scoped request token (`requestId + collectionId`).
- Collection runtime state (`CREATING/READY/IMPORTING/INDEXING/UPDATING/ERROR/DELETING`) проектується з Operation Center lifecycle замість незалежних flags.
- Integrity UX розширено: Books/Authors/Genres/Series/Relations/SQLite/Lucene/problem count/export report/safe repair flow.
- Search query construction винесено з великого controller у immutable query snapshot/helper.

### Online update / consistency

- Перед online catalog update створюється SQLite checkpoint.
- При failure/cancellation після commit відновлюються SQLite, Lucene та statistics.
- Failed rollback зберігає recovery checkpoint.
- UI error model містить stage/source/last successful version/local state/rollback result/safe Retry.
- Backup/restore використовує єдиний CollectionDatabasePathResolver.

### Parser/security/hardening

- XML/StAX creation централізоване через fail-closed `SecureXmlInputFactory`; DTD та external entities вимкнені.
- INPX importer переведений на єдиний streaming `InpxReader`, включно зі standalone `.inp`.
- Додані archive-bomb/entry-size/compression-ratio guards для ZIP/EPUB/INPX.
- EPUB mixed-content metadata читаються bounded reader-ом.
- TXT importer відрізняє expected charset fallback від реальної I/O помилки.
- Backup export не видає success при пошкоджених reader preferences.
- `Thread.sleep()` у JavaFX/UI: 0; production sleeps залишені лише у перевірених retry/backoff paths.

## 2. Performance baseline

Актуальний deterministic SQLite baseline збережений у `docs/performance-baseline.json`.

| Books | First page p95 | TITLE keyset after 50k p95 | OFFSET 50k p95 | Exact active COUNT p95 | Authors first keyset p95 | Languages p95 | Filtered page p95 | Import probe |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 100k | 0.123 ms | 0.126 ms | 24.230 ms | 1.036 ms | 4.671 ms | 9.738 ms | 0.179 ms | 37,842 books/s |
| 500k | 0.137 ms | 0.139 ms | 27.913 ms | 5.168 ms | 15.208 ms | 38.601 ms | 0.161 ms | 46,187 books/s |
| 1M | 0.144 ms | 0.355 ms | 32.197 ms | 10.162 ms | 32.508 ms | 76.473 ms | 0.192 ms | 41,957 books/s |

Окремий paired A/B на 1M для `V48`: exact active count приблизно `147 ms` без partial index проти `13–15 ms` з `idx_books_active_id`.

## 3. Build / acceptance status

### Maven

- `test-compile` у всіх 13 reactor projects: PASS.
- `clean verify -DskipTests`: PASS.
- `clean verify -Pproduction -DskipTests`: PASS.
- Bootstrap executable JAR та MCP shaded JAR збираються.

### JUnit runtime

Після Windows Surefire-прогонів були виправлені застарілі test contracts/fixtures, що проявилися після refactoring: rollback statistics lifecycle, normalized language SQL, UUID BookId fixture, current Flyway schema для catalog import tests, `BookListRowMapper` Spring fixture та явний Lucene query-availability lifecycle у standalone tests.

Через відсутність `surefire-junit-platform:3.2.5` у переданому Linux offline-cache ті самі JUnit Jupiter тести додатково виконані без Maven Surefire напряму через `junit-jupiter-engine`:

- application: **84/84 PASS**;
- infrastructure: **189/189 PASS** (UTF-8 locale);
- domain: **5/5 PASS**;
- reader: **35/35 PASS**;
- OPDS: **2/2 PASS**;
- MCP: **6/6 PASS**;
- architecture-tests: **12/12 PASS**.

Разом: **333 JUnit tests PASS** поза JavaFX UI runtime. UI JUnit: 21 тест проходить у Linux, 2 navigation tests потребують реального JavaFX toolkit і тому залишаються Windows-runtime acceptance, а не code failure.

### Quality gates

PASS:

- architecture-check
- implementation-completeness-check
- functional-regression-check
- refactoring-p0-progress-consistency-check
- large-library-pre-stage7-check
- catalog-keyset-pagination-check
- list-continuation-count-reuse-check
- async-generation-audit-check
- collection-runtime-state-check
- persistence-error-transparency-check
- thread-sleep-audit-check
- Reader Stage 19/20
- UI orchestration Stage 25A
- Reader refactor Stage 25B
- Search/sync refactor Stage 25C
- online-update-rollback-check
- XML/archive-security-check
- Stage 24 performance check
- author-search-normalization-check
- directory-import-lifecycle-check
- INPX/Lucene consistency
- reading-statistics consistency
- user-data consistency
- startup nonblocking / transaction checks
- catalog lifecycle regression
- import-index lifecycle
- V7.1 standalone Java smoke
- genre/export authority
- UI function reachability
- static release check
- release artifact presence/checksum validation

## 4. Що не можна чесно назвати runtime PASS у цьому контейнері

1. Maven Surefire `clean verify -Pproduction` із **усіма** тестами все ще треба повторити на Windows після RC3. У Linux offline-cache немає `org.apache.maven.surefire:surefire-junit-platform:3.2.5`; 333 non-UI JUnit tests перевірені напряму через JUnit Jupiter engine, але це не підміняє фінальний Windows Maven reactor run.
2. Повний JavaFX Windows runtime acceptance (реальні кліки, DPI 100/125/150/200%, installer/jpackage) неможливо коректно виконати у Linux container без Windows GUI environment. Два `WorkspaceManagerNavigationStateTest` у Linux упираються саме у відсутність JavaFX toolkit/QuantumRenderer; інші UI JUnit tests проходять.
3. Windows/macOS native installers не будувалися тут; перевірені source/release contracts та JAR artifacts.
4. Реальні 700k/1M production library files користувача не надавалися; large-catalog SQL baseline використовує deterministic synthetic fixture. Два надані користувачем ZIP/FB2 кейси були використані для targeted parser/cover/author runtime regression.

## 5. Рекомендований фінальний acceptance на Windows

У корені проєкту:

```powershell
.\mvnw.cmd clean verify -Pproduction
```

Потім перевірити вручну:

- повторне сканування локальної колекції;
- авторів `Дмитрий Дорничев / Дорничев Дмитрий` та concatenated creator case;
- обидва передані ZIP з книгами;
- Book Details: content/images/TOC;
- Reader open/close/navigation;
- Followed Authors;
- online update failure/retry;
- Import 100k/500k/700k/1M;
- Windows DPI 100/125/150/200%;
- package/release scripts.


## 6. Stage 05 P3 Linux preflight — 2026-09-05

На реальному Flibusta corpus (562 307 INPX records; 444 779 active; 117 528 deleted) після прийнятого Stage 05 P2 checkpoint виконано фазовий preflight changed-full Online Update. Це Linux/JDK 21 evidence, а не заміна Windows acceptance.

Фактичні виміри в цьому середовищі:

- INPX count + SHA preflight: приблизно 0.43 с (типовий run 371 ms + 59 ms);
- validated SQLite recovery checkpoint: 1.791 с (VACUUM INTO 1.139 с + quick_check 0.652 с);
- changed-full importer, exactly 1000 title changes: stable P2 median 11.719 с; контрольний peak-memory run 11.915 с;
- selective Lucene update for exact 1000 IDs: 0.259 с (DB enrichment 0.185 с, Lucene writes 0.044 с, final commit 0.027 с);
- statistics invalidate + refresh: 0.761 с;
- measured post-download phase budget including INPX count/SHA: approximately 14.96 с;
- conservative process peak for the importer probe executed in the Maven JVM (`forkCount=0`): 1 221 276 KiB RSS (~1.165 GiB). This includes Maven/test-harness overhead and therefore is not a pure application-heap measurement.

`UpdateCollectionFromNetworkUseCaseStage6Test` remains green (10/10) and verifies that an unchanged downloaded full snapshot takes the fingerprint no-op path without SQLite checkpoint, importer replay, Lucene transaction or statistics refresh.

Network Download was not timed in the Linux container because outbound DNS/network access is unavailable there. P3 remains incomplete until Download and the same phase split are measured on the target Windows machine. The permanent opt-in `RealSelectiveLucenePerformanceProbeTest` was added so the selective Lucene phase can be reproduced without embedding benchmark DB/index artifacts in the repository.

### P3 scenario comparison — Linux/JDK 21

Після фазового changed-full preflight додано порівняння інших P3 сценаріїв на тому самому Flibusta corpus:

- **initial full**: production importer — **59.865 с** для 562 307 records (`inserted=444779`, `deleted state=117528`), після чого clean production-created DB дає full Lucene rebuild **21.485 с** для 444 779 docs і statistics refresh **0.880 с**;
- **identical no-op full snapshot**: новий opt-in `RealOnlineNoOpPerformanceProbeTest` вимірює production orchestration після того, як downloader уже повернув файл та SHA metadata; 100 runs — median **390 µs**, p95 **663 µs**, max **850 µs**, при цьому checkpoint/importer/Lucene/statistics не викликаються;
- **changed-full / 1000 title changes**: importer median **11.719 с**, selective Lucene ~0.26–0.29 с, statistics ~0.76–0.82 с;
- **small delta / ті самі 1000 title changes**: importer **0.509 / 0.582 / 0.444 с**, median **0.509 с**. У всіх трьох runs `updated=1000`, `deleted=0`; books/authors/relations залишаються точними. З використанням окремо виміряних checkpoint + selective Lucene + statistics assembled post-download budget становить близько **3.43 с**.

Synthetic delta не входить до code-only archive. Для коректного fallback-parsing `online.inp` у такому UTF-8 delta має зберігати BOM; перший diagnostic fixture без BOM був відкинутий, оскільки він змінював encoding detection і не був еквівалентний production corpus.

Download як і раніше не входить до Linux цифр через відсутність outbound DNS/network у runtime; фінальний end-to-end Online Update і peak-memory acceptance треба повторити на Windows.

## Stage 05 P5 preflight — packaged portable launch directory (2026-09-05)

- Reproduced a real `jpackage` portable-mode defect: with `myhomelib2.ini` beside the native launcher, starting the packaged app from a different working directory used `user.dir` and wrote to the normal profile data directory instead of portable `data/`.
- Hardened `AppPaths.launchDir()` for packaged runtimes: explicit `myhomelib.launchDir` still wins; a jpackage runtime now resolves the native process executable directory; ordinary JVM launches retain the previous `user.dir` fallback.
- Added `AppPathsLaunchDirTest` for explicit override, ordinary JVM fallback and jpackage-process-directory semantics.
- Real Linux JDK 21 app-image probe: launcher started from an unrelated working directory with `myhomelib2.ini` beside it created `bin/data/{libraries,config,downloads,cache,logs,backups}` and did not create the normal profile data directory.
- This is a Linux packaging preflight only. Native Windows portable/EXE upgrade/uninstall and DPI acceptance remain release gates.


## Stage 05 P5 Windows installer lifecycle preflight — 2026-09-05

- Added a Windows-only `tools/windows-installer-acceptance.ps1` gate for disposable CI/VM profiles.
- `package-desktop.ps1` now supports a package-version override independent of the compiled JAR version, allowing CI to build a synthetic lower-version MSI without mutating the Maven project version. Normal release packaging is unchanged.
- CI builds a synthetic previous MSI and the current MSI with the same stable `--win-upgrade-uuid`, installs/upgrades/reinstalls/uninstalls them with `msiexec`, verifies one product registration, per-user launcher, Desktop + Start Menu shortcuts and `--release-smoke`.
- The harness writes deterministic user-data/library sentinels under `.myhomelibcorp` and requires them to remain byte-identical across upgrade, repeated current-package installation and uninstall. It then removes only those synthetic acceptance files because the runner started clean.
- MSI verbose logs are uploaded by the Windows CI job. The published EXE installer is still built separately.
- Synthetic installer upgrade proves packaging/Windows Installer mechanics only; a real previous-version MSI and real user database must still be exercised before final release. DPI 100/125/150/200% and interactive GUI/EXE installer acceptance also remain Windows-only manual gates.


## Stage 05 P4 Windows UI/DPI acceptance runner — 2026-09-05

- Added `tools/windows-ui-acceptance.ps1` for the mandatory 100/125/150/200% Windows runtime passes.
- The runner intentionally does not modify Windows DPI/scaling; the operator selects the scale in Windows Settings and records explicit PASS/FAIL/BLOCKED results.
- The protocol covers the handoff P4 list: repeated left/right sidebar cycles, Main Window/Search/Book Details/Reader, Reader toolbar and right sidebar, Collection Wizard, Backup/Restore, `дорничев`, `дорб`, `Дмитрий Дорничев`, `Дорничев Дмитрий`, case/space/Cyrillic variants, Back/Forward and Followed Authors.
- Each run records a packaged-launcher `--release-smoke` and the final geometry rule that no sidebar/toolbar/content pane may expand beyond the client area.
- This is acceptance tooling only. No Windows UI/DPI PASS is claimed until the four generated reports are completed on an actual Windows desktop.

## Stage 05 P4 Windows DPI acceptance hardening — 2026-09-05

- `tools/windows-ui-acceptance.ps1` now cross-checks the requested 100/125/150/200% run against `GetDpiForSystem()` (96/120/144/192 DPI).
- On a single-monitor acceptance machine, a known system-DPI mismatch is an automatic `AUTO-0 = FAIL`; an unavailable API observation is `BLOCKED`, so the report cannot silently claim PASS.
- On multi-monitor Windows, a system-DPI mismatch is `BLOCKED` rather than a false FAIL because the monitor hosting MyHomeLib can use different per-monitor scaling; P4-01 must confirm that monitor explicitly.
- This is acceptance-tooling hardening only; production Java code is unchanged.
