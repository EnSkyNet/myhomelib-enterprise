# Current V34 continuation status — 2026-11-01

Implementation baseline: `189c943ad5919656aa13e4688f6ac4282099b3ba`; this validation note is a documentation-only update on top of that tested code. Release remains **NO-GO** until physical Windows acceptance and the remaining supply-chain scan are complete.

## Merged implementation and validation

- PR #155 adds a real-Windows SQLite recovery-checkpoint probe; PR #156 adds the crash-between-marker-and-version-commit regression.
- PR #160 adds cross-collection group export: each registered collection is read through an isolated read-only SQLite session; the selected group is resolved by name in other collections; export retains collection provenance, metadata and archive-entry identity; ZIP batches are capped at 1,000 books; cancellation, ambiguous group matches and incomplete snapshots fail closed.
- PR #161 adds genre-taxonomy compatibility: source-scoped conflict codes, preserved original codes/provenance, category hydration and deterministic `db → fb2 → fbd` reader priority.
- Exact PR #161 candidate `164f51935d0fa06f16fdf74b1ba2b0cc8a290dc7`: Windows root Maven `clean verify` **2,891 tests / 0 failures / 0 errors / 29 skipped** ([run #41330447465](https://github.com/EnSkyNet/myhomelib-enterprise/actions/runs/41330447465)); PR CI PASS ([#41330447562](https://github.com/EnSkyNet/myhomelib-enterprise/actions/runs/41330447562)); CodeQL PASS ([#41330447505](https://github.com/EnSkyNet/myhomelib-enterprise/actions/runs/41330447505)); Windows test-only Portable PASS ([#41330447431](https://github.com/EnSkyNet/myhomelib-enterprise/actions/runs/41330447431)).
- Post-merge checks for exact master `189c943ad5919656aa13e4688f6ac4282099b3ba`: PR CI PASS ([#41332468356](https://github.com/EnSkyNet/myhomelib-enterprise/actions/runs/41332468356)); CodeQL PASS ([#41332468401](https://github.com/EnSkyNet/myhomelib-enterprise/actions/runs/41332468401)); Windows test-only Portable PASS ([#41332468362](https://github.com/EnSkyNet/myhomelib-enterprise/actions/runs/41332468362)).
- The Portable artifact is test-only and unsigned. Its CI host is Windows Server 2025, not the user's physical Windows 11 / 4 GB system. It is not a release candidate.

## Remaining external gates

- Issue #132 remains open for physical Windows 11 x64 / 4 GB acceptance with a real library and the user's actual books. CI and synthetic Windows smoke do not replace this.
- Dependabot PRs #138, #139 and #141 pass full root Maven `clean verify`, CodeQL and the core Windows gates; #141's performance baseline also passes. PR #140 is a workflow-only CodeQL Actions update: its Source Integrity, Fast Gate, DPAPI, organizer and CodeQL/CodeQL Java checks pass; no full root Maven job is defined for that change. All four blocking Dependency-Check jobs stop before scanning because `NVD_API_KEY` is absent from the **Dependabot** secret context. Configure that key in repository Settings → Secrets and variables → Dependabot, then rerun the four scans. Missing NVD data must not be treated as zero vulnerabilities; no SCA clearance is claimed.
- Issue #136 (vendored EPUBCheck HttpClient5/JBIG2 dependency triage) remains open; the staging/update utility is not evidence that the shipped HttpClient5 binary has been upgraded or that the CVE is resolved.
- Installed-app GUI, physical device/Calibre and installer lifecycle acceptance remain external gates.

## Branch consolidation

- PR #162 was closed as superseded, not merged: current master already contains TITLE/ID keyset continuation, the group-membership SQL guard and large-export pagination regressions through PR #160.
- Four Dependabot PRs #138–#141 remain open. All other reviewed branch tips are either contained in master or have been individually verified as superseded; cleanup is prepared separately and must be run with an explicit confirmation.

The following sections are preserved historical evidence.

# Поточні перевірки, 2026-10-18

**Current source candidate:** MyHomeLib Enterprise 8.0.0 / V34 External Test R18 — working branch `release/v34-sync` (exact-current root verification not complete).
**Date:** 2026-10-18
**Status:** **CURRENT CANDIDATE NOT VERIFIED; LIVE ACCEPTANCE REMAINS REQUIRED.**

Historical R18 Maven baseline aggregate: **2123 tests / 0 failures / 0 errors / 23 skipped**.
Detailed historical R18 evidence: `docs/release/POST-R17-R18-VALIDATION-2026-10-05-UA.md`.

These R18 historical numbers are not test results for the current branch. The 2026-10-13 validation below is another historical baseline, not release approval for the exact-current commit.


Дивіться [поточний звіт](AUDIT-REMEDIATION-2026-10-15-UA.md) і [зовнішню матрицю](AUDIT-EXTERNAL-ACCEPTANCE-2026-10-13-UA.md).

Root `-Pproduction clean verify` від 2026-10-13: **BUILD SUCCESS**, 2347 tests / 0 failures / 0 errors / 23 skipped, **2324 виконано**, 671 XML, 21/21 reactor modules SUCCESS. 15 жовтня перевірено всі 2448 хешів тестового маніфесту: збігаються. Java/POM/resources не змінено; нового root-прогону цього дня немає.

15 жовтня: **11 перевірок PASS**; окремо **Stage 25C FAIL** через FolderSyncService 375/360 рядків. Повний `offline_checks` не оголошується успішним. Докази: `audit-evidence-2026-10-13.zip` та `source-check-evidence-2026-10-15.zip`. Windows/user-book acceptance, новий performance A/B та live SCA відкриті.

Поточні defaults: Hikari pool=3, cache=-8192 KiB, mmap=33554432 bytes. Твердження щодо pool=2, відсутності root-прогону та результати в історичних розділах нижче описують попередні кандидати.

## Історичні перевірки попередніх версій

# Current validation status

**Оновлення 2026-10-12: ліміти метаданих EPUB та перевірки AppPaths.** Виконаний 2026-10-11 цільовий Maven-прогін: **53 тести / 0 failures / 0 errors / 0 skipped**. На попередньому коді новий `EpubPackageBudgetTest` відтворив 5 failures із 7 випадків; після виправлення всі 7 пройшли. `AppPathsLaunchDirTest` — 5/5 після усунення залежності від доступності process metadata у тесті й додавання fallback-випадків.

Перед пакуванням 2026-10-12 повторно внесено ті самі перевірені зміни до збереженої попередньої версії; Maven повторно не запускався. **Архітектурна перевірка та offline release checks фінального коду — PASS.** Запис результатів, diff і походження доказів: `docs/release/epub-package-budget-evidence/`.

**Повний root clean verify цього патча не виконано.** Раніше в середовищі не працювала перевірка дерева дочірніх процесів; цей тест не змінено й не вимкнено. Цільові 53 тести не замінюють root-прогін або Windows acceptance.

Схема content index залишається **2**; попередній перехід 1 → 2 потребує одноразової перебудови. Деталі: `docs/release/CONTENT-EXTRACTION-MEMORY-AUDIT-2026-10-11-UA.md`. Усі числа й acceptance-висновки нижче — історичні.

## Historical R18 validation snapshot, 2026-10-05

**Historical source candidate:** MyHomeLib Enterprise 8.0.0 / **V34 External Test R18 — post-live-test failure-transparency/persistence/integration hardening** derived from R17
**Date:** 2026-10-05
**Status:** **R5–R17 GUARANTEES RETAINED. R18 FIXES READER-PREFERENCES FAILURE TRANSPARENCY, KOBO REPOSITORY-ERROR MASKING, STALE READER-SESSION RESURRECTION, KOBO SPRING WIRING/PROXYABILITY, AND HARDENS TTS/COVER/WEBDAV/CLEANUP PATHS. EXACT R18 ROOT CLEAN VERIFY: 2123/0/0/23, 635 SUREFIRE REPORTS, 21/21 REACTOR PROJECTS SUCCESS, BUILD SUCCESS, 02:43. UI ACTION COVERAGE 486/486. WINDOWS/DEVICE/PROVIDER LIVE ACCEPTANCE REMAINS REQUIRED.**

## Implemented scope since V29

- **V30 — Kobo native sync:** narrow Application/Infrastructure/OPDS boundary, opaque device credential in `SecretStore`, V66 cursor/projection/progress-version state, incremental add/update/remove, stable book + content revision identity, lazy reuse of the existing converter toolchain, bidirectional progress translator with stale-version rejection, Integration Center UI and UK/EN/BG help/localization.
- **V31 — secure application auto-update:** signed release manifest and verification policy, bounded HTTPS transport/staging, bundled Ed25519 public trust keys, standalone `myhomelib-updater`, parent-exit replacement, backup/health marker/rollback, Settings actions and desktop packaging integration.
- **V32 — Readwise annotation connector:** bounded remote export port/use case, stable annotation fingerprint/checkpoint semantics, token only in `SecretStore`, V67 checkpoint state, retry only for idempotent-safe provider failures, Integration Center and Annotation Manager actions. Remote deletion is intentionally outside V32.
- **V33 — local recommendations:** deterministic metadata-only similarity policy with bounded SQLite candidate projection; no cloud, embeddings, vector DB or persisted recommendation index. Real-data profiling added keyword-only candidate coverage, normalized series identity, existing partial-index reuse and tighter bounded candidate selection. Book Details loads recommendations asynchronously.
- **V34 — exact duplicate comic pages:** bounded page-level exact SHA-256 + length evidence for CBZ/CBR/CB7, cancellation, corrupt-entry continuation, deterministic groups and report-only Book Details UI. Perceptual/near matching remains outside V34.

## Historical local evidence (2026-10-05)

Detailed exact-current evidence: `docs/release/POST-R17-R18-VALIDATION-2026-10-05-UA.md`. Next live/platform tasks: `docs/release/NEXT-TASKS-AFTER-R18-2026-10-05-UA.md`. R16 evidence remains in `docs/release/POST-R15-R16-VALIDATION-2026-10-04-UA.md`. R15 evidence remains in `docs/release/POST-R14-R15-VALIDATION-2026-10-04-UA.md`. R14 full-root evidence remains in `docs/release/POST-R13-R14-VALIDATION-2026-10-04-UA.md`. R13 evidence remains in `docs/release/POST-R12-R13-VALIDATION-2026-10-04-UA.md`. R12 performance evidence remains in `docs/release/POST-R11-R12-VALIDATION-2026-10-04-UA.md`. R10 evidence remains in `docs/release/POST-R9-R10-VALIDATION-2026-10-04-UA.md`; R9 evidence remains in `docs/release/POST-R8-R9-VALIDATION-2026-10-04-UA.md`; R8 evidence remains in `docs/release/POST-R7-R8-VALIDATION-2026-10-04-UA.md`; R7 memory baseline remains in `docs/release/POST-R6-R7-VALIDATION-2026-10-04-UA.md`; R6 harness baseline remains in `docs/release/POST-R5-R6-VALIDATION-2026-10-04-UA.md`; R5 memory A/B evidence remains in `docs/release/POST-R4-R5-VALIDATION-2026-10-03-UA.md`.

Environment: JDK 21, Apache Maven 3.9.16, supplied offline repository, UTF-8 locale, JavaFX under Xvfb where required.

- exact-current R18 root Maven `clean verify`: **2123 tests / 0 failures / 0 errors / 23 skipped**, **635 Surefire XML reports** across **20** test/product modules, **21/21 reactor projects SUCCESS**, `BUILD SUCCESS`, **02:43 min**;
- Application **521 / 0 / 0 / 1 skipped**; Infrastructure **693 / 0 / 0 / 13 skipped**; Reader **152 / 0 / 0 / 1 skipped**; UI **379/379**; Bootstrap **60 / 0 / 0 / 4 skipped**; E2E **15/15**; Architecture **20/20**;
- UI action inventory: **486/486 mapped** (**275 FXML + 205 programmatic + 6 Web**); action-specific behavior **485 automated + 1 justified exclusion**;
- user-function documentation: **486/486** actions linked to **31** documented flows;
- UI localization: **3748 synchronized UK/EN/BG keys**; Web localization: **87** synchronized keys; help: **76** Markdown pages;
- architecture/static-release/supply-chain/implementation-completeness/persistence-transparency/Spring-wiring/proxyability: **PASS**;
- real 707,154-book same-DB A/B accepted only Hikari interactive pool **4 → 2**: warm-probe RSS average **−17.31%** with page/author/statistics median deltas **+0.56% / +2.44% / +1.56%** and parallel-4 **−6.43%**;
- fresh 707,154-record import with pool=2: **707154 imported / 0 errors / 54.683 s**, same catalog counts, `quick_check=ok`; idempotent re-import **0 imported / 707154 skipped / 0 errors / 590 ms**;
- reduced SQLite cache/mmap profile rejected because author-query materially regressed; SQLite cache/mmap, BookCache, CoverCache and `-Xmx1024m` remain unchanged.

The memory numbers above are same-environment Linux probe evidence, not a claim of final Windows desktop idle RSS. Target-machine soak remains external.

## V34 External Test R18: full post-live-test hardening audit

- Reader settings persistence failures now propagate to the UI instead of being logged and silently treated as success.
- Kobo malformed IDs still map to not-found, but repository/database failures for valid IDs now propagate instead of being masked as empty results.
- Reader session Preferences are authoritative over best-effort SQLite mirror; a clear tombstone prevents stale DB state from resurrecting after failed delete.
- `SqliteKoboSyncAdapter` is proxyable and has an explicit production injection constructor; adapter and Spring startup smoke are green.
- TTS provider fallback, cover/archive identity + decode recovery, WebDAV secret/config consistency and cleanup diagnostics are hardened.
- Empty-catch ratchet: **196 current vs 202 baseline**; thread-sleep audit PASS with no JavaFX/UI blocking sleeps.
- Exact R18 root offline/Xvfb `clean verify`: **2123 / 0 / 0 / 23 skipped**, **635 Surefire XML reports**, **21/21 SUCCESS**, `BUILD SUCCESS`, **02:43 min**.
- UI inventory: **486/486 mapped**, action-specific behavior **485 automated + 1 justified exclusion**, documentation **486/486 across 31 flows**, UK/EN/BG **3748** synchronized keys.
- Local code/static/full-regression gates are green. Native Windows, physical device, provider/browser and connected release gates remain OPEN_EXTERNAL until real evidence is captured.

## V34 External Test R16: second Windows defect-driven hardening

- Latest Windows log proved conversion was rejected before converter start because active QUERY blocked CONVERSION; QUERY shared roots now permit safe same-collection CONVERSION/DOWNLOAD/INDEX/EXPORT/READER_STATE while destructive lifecycle operations remain protected.
- Coordinator tracks all operation types in the shared session, so a joined INDEX is still visible to download logic even when the root is QUERY; derived index repair is deferred off the download caller.
- Download preflight and authoritative reload use NETWORK lane and perform physical local reconciliation before HTTP and again after acquiring a slot. Supplied 18-book corpus local resolver: **18/18**, including server-renamed archive entries.
- Covers use cancellable INTERACTIVE work and failed/cancelled current requests no longer pin the request key forever. Supplied corpus cover extraction: **18/18**; big 17.7 MiB FB2 cover: **105 ms** in the direct probe.
- Author workspace cancels superseded metadata/book queries instead of accumulating queued QUERY leases.
- ConnectionScript legacy read timeout is capped at 30 s and default retries are zero.
- First full R16 root run found one stale cancellation-test contract after executor-lane change; after correcting the test, exact-current root `clean verify`: **2106/0/0/23 skipped, 21/21 SUCCESS, BUILD SUCCESS, 02:39**.

## V34 External Test R15: real Windows UX/concurrency/conversion hardening

- Real R13 Windows log showed multiple 120 s-class online HTTP waits/502s while Book Details shared background workers with network tasks.
- NETWORK and INTERACTIVE lanes are isolated; Book Details selection cancels stale work and has a 5 s deep-analysis watchdog.
- All 18 supplied real FB2 books under 24 blocked network tasks completed interactive inspection in **751 ms total**; largest 17.7 MiB compilation: **241 ms**.
- Download/details/conversion logs now identify title/id/LibID/file/archive entry, route/engine and duration.
- Bundled `tools/converters/fbc-windows-amd64/fbc.exe` is explicitly discoverable; AUTO FB2/FB2_ZIP→EPUB does not silently fall through to slow Calibre unless explicitly enabled.
- R14 INDEX↔DOWNLOAD shared-lifecycle fix and R13 primary/V68/EPUB guarantees remain in the affected regression matrix.
- Exact-current affected suites and Windows-classifier 15-module packaging build are green. Full R15 root PASS is not claimed because root attempts exceeded the external 5-minute command limit; R14 remains the last uninterrupted full-root baseline.

## V34 External Test R14: non-blocking full-text index rebuild / release audit

- Confirmed that catalogue Lucene rebuild already allowed shared DOWNLOAD, while full-text `ContentIndexMaintenanceService` still acquired an exclusive INDEX lease even though the UI presented the same «Оновлення пошукового індексу» stage.
- Full-text rebuild now uses the shared detached INDEX root; same-collection online DOWNLOAD may join while collection-destructive/switch operations stay protected.
- A committed download under active INDEX defers both catalogue and full-text derived-index repairs off the caller thread.
- Full-text STALE holds are reference-counted so rebuild and concurrent committed mutation cannot clear each other's protection; low-level Lucene rebuild no longer publishes FRESH by itself.
- Full release audit found three R13 migration tests still ratcheted to schema 67 after V68; expectations are now 68 and the migration matrix is green.
- Exact-current full root `clean verify`: **2097 / 0 / 0 / 23 skipped**, **21/21 SUCCESS**, `BUILD SUCCESS`.
- Windows live INDEX+DOWNLOAD remains required because Linux/Xvfb concurrency evidence does not substitute for target-machine/UI/network timing.

## V34 External Test R13: conversion projection / EPUB / portable FBC regression fix

- Confirmed that `ConvertBookUseCase` already persisted conversion with `makePreferred=false`, but a missing `book_artifact_preferences` row allowed hydration to infer the first artifact as preferred; generated EPUB could therefore replace `Book.file` after a database round-trip.
- Secondary artifact insertion now first persists the canonical existing preference; domain fallback never infers a generated conversion while a source/imported artifact exists.
- V68 repairs already-affected missing-preference databases and restores source projection where conversion metadata identifies the original.
- EPUB Book Details falls back to OPF metadata when full spine/XHTML parsing fails; `dc:description` is exposed as annotation.
- Windows portable packaging now requires bundled FBC/fb2cng so FB2→EPUB AUTO does not silently degrade to system Calibre merely because the slim package omitted the specialized converter.
- Targeted conversion/domain/SQLite/reader regression suite and Windows fat-JAR build are green; exact root result is recorded in `POST-R12-R13-VALIDATION-2026-10-04-UA.md`.

## V34 External Test R12: FB2 passive Book Details performance

- Real supplied 5.8 MiB ZIP contains one **17,736,920-byte** FB2 with **7,756,433** parsed chars, **1,199,396** words and **4** embedded images.
- Product ZIP list/extraction is only tens of milliseconds in same-environment profiling; the measured hotspot was eager FB2 image-payload decode/temp-resource work during passive Book Details.
- A/B on the real FB2: parser `loadImages=true` **1341/1080 ms** vs `loadImages=false` **211/252 ms**.
- R12 keeps image ids/count during passive inspection and lazy-loads actual FB2 image payload only on explicit gallery access; repeat image opens reuse the lazy image document.
- Real-file passive inspection after fix: cold **418 ms**, warm **259/176/165 ms**; first explicit image open **1484/896/827/821 ms**.
- Added regression proving deferred binary payload and one-time lazy materialization.
- Exact-current root offline `clean verify`: **2088 / 0 / 0 / 23 skipped**, **21/21 reactor projects SUCCESS**, `BUILD SUCCESS`, **02:30 min**.

## V34 External Test R11: Windows runtime-binary binding hardening

- Production Java/POM/runtime resources and memory defaults are unchanged from R10.
- Exact R10 frozen source was first revalidated with an uninterrupted root offline `clean verify`: **2087 / 0 / 0 / 23 skipped**, **21/21 SUCCESS**, `BUILD SUCCESS`, **02:33 min**.
- `windows-release-desktop-acceptance.ps1` now records SHA-256 of the installed candidate launcher after candidate-bound installer acceptance.
- `windows-post-r5-functional-acceptance.ps1` requires the same candidate/session/host desktop acceptance, verifies launcher path + bytes against `launcherSha256`, and hash-binds a copied desktop acceptance JSON.
- `windows-target-memory-soak.ps1` requires the same functional report, verifies the supplied PID executable path + SHA-256 against that accepted launcher, and records the functional-report SHA-256.
- Final post-R5 aggregation validates the complete desktop → functional launcher → memory process runtime-binary chain. Negative regressions reject an alternate process hash, a changed functional report hash and modified desktop binding evidence.
- Exact-current R11 root offline `clean verify`: **2087 / 0 / 0 / 23 skipped**, **21/21 reactor projects SUCCESS**, `BUILD SUCCESS`.

## V34 External Test R10: live-evidence integrity hardening

- Production Java/POM/runtime resources and memory defaults are unchanged from R9/R8/R7.
- Every PASS row in Windows functional, Windows memory and Kobo/Readwise/PWA/comics reports now carries `evidenceSha256`; final aggregation verifies that hash against the actual referenced file, so evidence cannot be replaced after report creation without detection.
- MEM-10 aggregation now parses raw soak JSON and validates actual wall-clock duration, raw sample count/timestamps, monotonic elapsed/GC counters, peaks and first→last deltas against the report summary.
- Negative regressions reject a declared 3600 s soak backed by only 120 s of raw evidence, a tampered summary delta and a changed evidence file whose stored SHA no longer matches.
- `external-acceptance-readiness --run-regressions` now executes the post-R5 checker regression directly. Affected acceptance/readiness/static-release gates PASS.
- Exact-R10 one-shot Maven is not claimed locally because the same external ~5-minute execution limit prevents completion; unchanged Java/build baseline remains R7 **2087/0/0/23, BUILD SUCCESS** until exact-candidate CI/Windows completes it.

## V34 External Test R9: live-acceptance evidence cohesion hardening

- Production Java/POM/runtime resources and memory defaults are unchanged from R8/R7.
- Windows functional and target-memory reports must now share the exact same `acceptanceSessionId`, host/user fingerprints and OS version/build/architecture; mixed Windows sessions/hosts are rejected.
- `--external-final` now validates the real `myhomelib-7.1-final-external-acceptance` schema, exact backlog/evidence sets, candidate binding, artifact SHA-256 values, Windows integrity candidate SHA, GitHub run IDs/URLs and acceptance-session fingerprints.
- Negative regressions reject mixed Windows sessions, shallow fake external-final JSON and integrity-candidate mismatch.
- Affected acceptance/readiness/static-release gates PASS. R9 does not claim a new one-shot Maven PASS because local attempts were terminated by the external ~5-minute execution limit; the unchanged Java/build baseline remains the R7 **2087/0/0/23, BUILD SUCCESS** run until exact-R9 CI/Windows evidence is produced.

## V34 External Test R8: release-evidence consistency hardening

- Production Java/runtime behavior is unchanged from R7.
- The final R7 100k/8 MiB JVM memory probe is now retained as source release evidence at `docs/release/PERFORMANCE-R7-100K-MEMORY-2026-10-04.json`; its 20× Reader open/close retained delta is **224 B**.
- Corrected the stale **744 bytes** summary in this document to the exact final probe value **224 bytes**.
- `external-acceptance-readiness.py` no longer accepts historical `2076 tests / 22 skipped` tokens anywhere in this long document as proof that the *current* validation is synchronized. It binds the current R-revision to `POST-R<n-1>-R<n>-VALIDATION-*` and compares the current Maven aggregate with that detailed evidence.
- Negative regressions prove that a stale current aggregate or a mismatched current evidence document now fails readiness.

## V34 External Test R7: memory evidence hardening

- Production Java behavior and R5 memory settings remain unchanged; no new RAM tuning was accepted without target-machine evidence.
- Audited collection/cache/Reader resource lifecycle and reran focused memory/resource-release regressions; no new production retention defect was found locally.
- Local 100k/8 MiB performance probe retained only **224 bytes** after 20 Reader open/close cycles in that controlled JVM probe; this is useful leak-regression evidence but not a Windows desktop RSS claim.
- Fixed the Windows memory acceptance gap: live checkpoints now require cumulative GC collections/time via `jstat`, and MEM-10 records peak + first→last drift for working set, heap, threads, handles and GC.
- The fail-closed final checker rejects memory reports without GC/handle evidence or MEM-10 peak/drift summary.

## V34 External Test R6: post-R5 live evidence harness

- Production Java behavior is unchanged from R5; Hikari pool=2 and the retained SQLite/cache/Xmx settings are not retuned without new representative evidence.
- Added candidate-bound Windows functional and target-memory runners plus evidence recorders for Kobo/Readwise/PWA/real comics.
- Added a fail-closed aggregate checker requiring exact candidate SHA, complete check IDs, evidence per PASS, safe evidence paths, non-reused evidence bytes, and a minimum one-hour memory soak.
- Local checker/harness/static-release/hygiene/build/architecture/supply-chain gates pass; targeted Calibre **7/7** and batch **3/3** pass.
- Exact-current root offline `clean verify`: **2087 / 0 / 0 / 23 skipped, BUILD SUCCESS**. External live gates remain open until run on their real platform/provider/device.

## V34 External Test R5: measured memory tuning

- `DataSourceConfig` now uses an explicit interactive profile with production max Hikari pool **2**; test-only profiles make A/B reproducible without exposing runtime tuning through production system properties.
- The released SQLite cache **16 MiB/connection** and mmap **64 MiB** were A/B tested at lower values but retained because the reduced profile materially worsened the representative author query.
- Opt-in `RealSqliteInteractiveTuningProbeTest` records page/author/statistics/parallel query medians, heap and Linux RSS against a supplied real DB.
- `*ProbeTest.java` files are excluded from UI action-specific evidence discovery so observational/performance probes cannot accidentally satisfy UI actions by generic words such as `add`, `next` or `sleep`. Reviewed registry remains **485/485**.

## V34 External Test R4: status/test-runtime hardening

- Completed single-book download uses transient status semantics, so an old `Завантажено: ...` message is not resurrected after a later coordinated background operation. A foreground status created while the background operation is active is still restored correctly.
- Mockito modules Bootstrap and E2E now use the same explicit test `-javaagent` contract as the other Mockito modules; static release validation fails if a future Mockito test module omits it.
- Exact-current root `clean verify`: **BUILD SUCCESS**, **2084 / 0 / 0 / 22 skipped**, **02:27 min**.
- Production Hikari/SQLite/cache/Xmx values remain unchanged pending same-machine target evidence.

## V34 External Test R3: post-R2 invariant hardening

- Conversion keeps the original preferred artifact; repeated conversion to the same target format reuses the existing derived artifact row instead of growing duplicates. The conversion Request API itself no longer contains the obsolete `makePreferred` switch, so automatic promotion cannot be requested through this use case.
- Re-clicking the already selected author row explicitly redispatches navigation, so `author → Operations/Reader → same author` works; missing author resolves to a fail-safe localized state. A dedicated `author → Reader → same author` navigation-state regression is included.
- Remote-only Book Details shows `Книгу не завантажено`, skips inspection/parser work, exposes `Завантажити` when the active remote collection can materialize the book, and reloads details after successful download. Local `MISSING`/`CORRUPT` artifacts remain technical local-file states.
- All three cancellable collection-scoped UI callers handle synchronous library-operation conflicts/rejections; `UiExceptionSupport` renders a localized busy message instead of leaking an uncaught JavaFX runtime exception.
- Memory work is evidence-first: synthetic baseline recorded, but no production memory limits were lowered without Windows/large-library A/B.

## V34 external-test revision: non-blocking Book Details selection

Зафіксовано дефект UX/runtime: після завантаження онлайн-книги звичайне виділення рядка запускало rich Book Details analysis як collection-scoped `QUERY`. Для архівних книг це могло локально матеріалізувати FB2/EPUB entry у temp-файл і виконувати inspection; мережевого повторного download не було. Через `LibraryOperationCoordinator` пасивний `QUERY` показувався в status bar як фонова операція, але не журналювався в Operation Center, і тимчасово блокував несумісні library operations. Після завершення status bar відновлював попередній foreground message про успішне завантаження, що створювало враження повторного download.

Виправлено: базові catalog metadata відображаються відразу; passive rich analysis, cover extraction і similar-books enrichment виконуються через звичайний managed background executor без global library lease. Stale-result guards при зміні книги/колекції збережені. Явні user-triggered library operations залишаються coordinated. Додано regression contracts для Book Details selection, cover lifecycle та доказ, що plain presentation task не займає `LibraryOperationCoordinator`. Targeted regression: **12/12 PASS**; exact-current full reactor: **2076 tests / 0 failures / 0 errors / 22 skipped; 21/21 SUCCESS; 2:44**.

## Final deep source/project audit

Detailed evidence: `docs/release/DEEP-PROJECT-AUDIT-V34-2026-10-02-UA.md`.

- Java source/test files scanned: **2106**, duplicate FQCN: **0**; exact duplicate production Java files: **0**;
- implementation-completeness: **1282 production Java files**, TODO/FIXME/unsupported markers **0**, empty public/protected methods **0**, unused explicit imports **0**, unused Spring constructor dependencies **0**, unconsumed use cases/output ports **0**, exact cross-file method clones ≥180 chars **0**;
- Maven reactor: **20 child modules / 21 projects**, duplicate dependency/plugin declarations **0**;
- local Markdown links: **21 checked / 0 broken**; six unique external HTTP(S) references were manually revalidated on 2026-10-02;
- current/release exact duplicate docs: **0**; root historical/task docs: **0**; generated/cache artifacts are excluded from the external-test package;
- resource-name collisions reviewed: two intentional cases only — separate plugin `META-INF/services` descriptors and duplicated standalone/main updater public trust-key resource; semantic trust-key entries match and are guarded by hygiene checks;
- architecture registry now covers **17/17 runtime/product modules**, including standalone JDK-only updater;
- project documentation is consolidated under `docs/` except canonical root `README.md`/`ARCHITECTURE.md` and runtime F1-help assets.

## Large real-data acceptance

Detailed evidence: `docs/release/REAL-DATA-ACCEPTANCE-V34-2026-10-02.md`.

- supplied `flibusta_online_fb2.inpx`: **707,154 records**, fresh import **0 errors**, ~**58.152 s**;
- resulting catalog: **707,154 books**, **571,947 active**, **166,231 authors**;
- idempotent re-import: **0 imported / 707,154 skipped / 0 errors**, **598 ms**;
- real-size V65→V67 upgrade: **451 ms**, `quick_check=ok`, book/author counts unchanged;
- final `quick_check=ok`; final full `integrity_check=ok`; schema **V67**;
- Lucene: **571,947 docs**, rebuild **38.599 s**, ~14,834 docs/s; representative searches ~11.7–41.6 ms;
- corrected restart/reopen probe: first startup orchestration with one-time normalization/backfill **48.107 s**; second startup **214 ms**, existing Lucene index reused and immediately fresh;
- V33 latest 5-sample bounded recommendation probe on 1.59-GB DB: **329–1,387 ms**, worst **1,387 ms**, all ≤10 results; series lookup confirmed to use `idx_books_active_series_normalized`;
- V34 1000-page synthetic CBZ stress: **1000/1000 pages**, **100 duplicate groups**, **0 failures**, **6.270 s**;
- all three supplied FB2 ZIP archives pass the real-file reader probe.

The V33 recommendation work was changed based on real-data evidence, not speculative tuning. The final 21/21 `clean verify` above ran **after** those production changes.

## Local gate conclusion

V30–V34 source implementation is complete and the candidate has passed both the deterministic local suite and the available large real-data acceptance. The large-catalog work exposed and fixed the V33 unbounded recommendation path; restart/index-freshness behavior was also characterized with production shutdown ordering and passed without a production workaround.

This result still does **not** convert unavailable physical/platform/provider tests into live acceptance.

## External / unavailable gates still required

| Area | Required real evidence | Status |
|---|---|---|
| V29 PWA | supported unrestricted browser install, saved-book offline reload, reconnect replay, auth revocation | OPEN_BROWSER — local Chromium is policy-blocked by `URLBlocklist: ["*"]` |
| V30 Kobo | first sync, library appearance, open/read, progress both directions, cover/metadata, reconnect, revoke | OPEN_DEVICE |
| V31 updater | packaged Windows installer/portable/upgrade, restart/update, file locks, health-marker rollback, signing/distribution | OPEN_WINDOWS / OPEN_EXTERNAL |
| V32 Readwise | real account auth, rate-limit behavior, create/update dedup, partial/network failure | OPEN_EXTERNAL |
| V33 recommendations | large real-library bounded performance/restart/integrity | **PASS_REAL_DATA** |
| V34 comic duplicate scan | 1000-page CBZ stress + deterministic corrupt/cancel tests | **PASS_LOCAL_STRESS**; real large CBR/CB7 corpus still not supplied |
| V26 inherited Windows | Calibre >5-minute conversion/cancel/process-tree/locks; installer/portable/DPI/DPAPI | OPEN_WINDOWS |
| Candidate CI | exact-candidate GitHub checks, SBOM, SCA/Dependency-Check, CodeQL, reviewer evidence | OPEN_EXTERNAL |
| Distribution | Authenticode/signing, SmartScreen/Defender/public delivery | OPEN_EXTERNAL |

Linux/Xvfb/local deterministic evidence is not a substitute for the external gates above.
