# MyHomeLib — V34 External Test R2 / post-R1 validation

**Дата:** 2026-10-03  
**База:** V34 External Test R1 (`myhomelib-enterprise-v34-r1-handoff-2026-10-02`)  
**Scope:** тільки дефекти й P1-аудит із `MYHOMELIB-NEXT-TASKS-AFTER-R1-2026-10-02-UA`; новий V35 product scope не відкривався.

## 1. P0 — conversion artifact semantics

- Чинна R1-база вже використовувала `upsertArtifact(..., false)` і migration V63 для відновлення original projection, тому автоматичне promotion нового conversion artifact у primary у поточному source не відтворювалося.
- Закрито залишковий regression: повторна конвертація в той самий target format більше не створює новий artifact row. Для вже наявного derived conversion artifact повторно використовується його id; для нового — deterministic UUID від stable book id + normalized target format.
- Original primary не змінюється без explicit `Зробити основним`; conversion failure не виконує promotion.
- Додано regression `repeatedConversionToSameFormatReusesExistingDerivedArtifactRow`.

Validation: conversion application suite **21/21 PASS**; V63/preferred-artifact persistence regression — **PASS**.

## 2. P0 — повторна навігація до автора

- Причину підтверджено: JavaFX `selectedItemProperty` не спрацьовує повторно при кліку по вже selected row після переходу в інший workspace.
- `NavigationPanelController` тепер повторно dispatch-ить primary-click по тому самому непорожньому `ListCell`, не дублюючи normal selection event для іншого рядка.
- `WorkspaceManager` regression підтверджує `author → Operations → same author` та працездатну history.
- Для missing/deleted author додано fail-safe стан `Автора не знайдено або він більше недоступний`, очищення stale data і disable follow action.

Validation: navigation/operation targeted suite — **PASS**; full UI suite на фінальному дереві — **370/370 PASS**.

## 3. P0 — remote-only Book Details

- Книга без local artifact тепер показує штатний стан **`Книгу не завантажено`** і не запускає parser/inspection.
- Якщо current remote collection підтримує materialization, доступна дія **`Завантажити`**.
- Після успішного download Book Details автоматично reload-иться та переходить до local inspection без повторного user click/download.
- `MISSING`/`CORRUPT` local artifact не маскується як remote-only: такий запис іде в inspection і зберігає технічний local-file error semantics.
- UK/EN/BG каталоги синхронізовані; bundled і canonical `Lang/*.json` однакові.

Validation: Book Details contracts — **PASS**; UI localization guard — **3746** synchronized keys, **PASS**.

## 4. P1 — operation/status consistency

- Passive Book Details analysis залишається plain managed background task і не бере `LibraryOperationCoordinator` lease.
- Перевірено всі три UI caller-и `submitCollectionScopedCancellable`: Book Workspace, Reader і Content Search тепер обробляють synchronous `LibraryOperationConflictException`/executor rejection замість uncaught JavaFX runtime exception.
- `UiExceptionSupport` відображає conflict як локалізоване `common.error.library_busy` без витоку technical exception text.
- Додано `LibraryOperationConflictUiHandlingContractTest` для всіх cancellable collection-scoped UI call sites.

Validation: exact-current conflict targeted suite **13/13 PASS**; broader targeted UI/operation regression **24/24 PASS**; full UI **370/370 PASS**.

## 5. P1 — memory baseline

Виконано локальний synthetic baseline на 100k catalogue profile. Raw evidence: `docs/release/PERFORMANCE-R2-100K-2026-10-03.json` і `docs/release/PERFORMANCE-R2-100K-2026-10-03-TIME.txt`. Це **не** Windows/707k same-machine A/B, тому production limits навмисно не змінювалися.

- Maven/JVM process max RSS за `/usr/bin/time -v`: **462,192 kB (~451 MiB)**.
- 8 MiB FB2 parse peak heap delta: **37,052,008 B (~35.3 MiB)**.
- 8 MiB EPUB parse peak heap delta: **30,939,064 B (~29.5 MiB)**.
- Reader 20x open/close retained bytes: **144 B**.
- Lucene 100k representative query: **11.72 ms**.
- 100k import probe: **~59,532 books/s**.

Висновок цього проходу: доказів для безпечного `Hikari 4→2`, SQLite cache/mmap або `BookCache`/`CoverCache 64→32 MiB` недостатньо. `Xmx=1024m` також не змінювався. Same-environment Windows/large-library A/B залишається live/performance gate.

## 6. Regression evidence

Після production changes виконано розбитий offline regression, оскільки один uninterrupted root `clean verify` у цьому execution environment тричі був примусово завершений зовнішнім **5-хвилинним tool limit**, а не Maven test failure. Остання single-run спроба на фінальному дереві пройшла full Application **509/509** і була перервана під час наступних reactor modules; до termination failures/errors не було.

Повне покриття добито module/chunk runs:

- Application: **509 tests / 0 failures / 0 errors / 1 skipped**;
- Infrastructure: **681 / 0 / 0 / 12 skipped**, reports для **179/179** test classes;
- Reader: **150 / 0 / 0 / 1 skipped**;
- UI exact-current: **370 / 0 / 0 / 0 skipped**;
- Bootstrap: **57 / 0 / 0 / 4 skipped**;
- E2E: **15/15 PASS**, **6/6** classes;
- Architecture: **20/20 PASS**;
- інші reactor modules, що були покриті split runs, — без failures/errors;
- до додавання фінального operation-conflict contract aggregate split reports становив **2080 tests / 0 failures / 0 errors / 21 skipped**; новий contract додає ще **1 PASS**.

Release/static gates на фінальному source:

- UI action inventory: **485/485 mapped** = **274 FXML + 205 programmatic + 6 Web**;
- action-specific behavior: **484 automated + 1 justified exclusion = 485/485**;
- user-function documentation: **485/485** actions → **31** flows;
- UI localization: **3746** synchronized UK/EN/BG keys — PASS;
- Web localization: **87** synchronized keys — PASS;
- help/genre localization: **75** help pages, 335 genres per locale — PASS;
- architecture check — PASS;
- static release check — PASS;
- supply-chain policy — PASS;
- implementation completeness — PASS.

`project-hygiene-check.py` треба запускати після видалення Maven `target/` та Python `__pycache__`; clean source package формується саме після такого cleanup.

## 7. Що не можна позначати як PASS у Linux/offline session

- R1 repeated-selection/non-blocking behavior на реальній Windows — ще потрібна повторна live перевірка нового candidate.
- Physical Kobo V30.
- Packaged Windows updater / Calibre / DPAPI / DPI / file locks / rollback / signing.
- Real Readwise account.
- Unrestricted-browser PWA.
- Real large CBR/CB7 corpus.
- 1–2 h production soak на цільовій машині.
- Same-machine Windows/707k memory A/B.

Ці gate-и не підміняються Linux/Xvfb regression evidence.
