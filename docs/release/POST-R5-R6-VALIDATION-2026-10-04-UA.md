# MyHomeLib — V34 External Test R6 validation

**Дата:** 2026-10-04  
**База:** V34 External Test R5  
**Мета:** не змінюючи product scope і не підміняючи live acceptance локальними fixtures, підготувати fail-closed evidence harness для post-R5 Windows/device/provider/browser/comic/memory gates та повторити exact-current локальні regression gates.

## 1. Production scope

Production Java behavior у R6 **не змінювався**. R5 memory-рішення збережено без додаткового tuning:

- interactive Hikari max pool: **2**;
- SQLite cache: **16 MiB/physical connection**;
- SQLite mmap: **64 MiB**;
- `BookCache`: **64 MiB**;
- `CoverCache`: **64 MiB**;
- desktop heap: **`-Xmx1024m`**.

Жоден Windows/Kobo/Readwise/PWA/real-comic/target-memory gate не оголошується PASS без відповідного live evidence.

## 2. Post-R5 live acceptance harness

Додано:

- `tools/windows-post-r5-functional-acceptance.ps1` — candidate-bound Windows R1/R2 Book Details, Calibre та packaged Windows acceptance;
- `tools/windows-target-memory-soak.ps1` — Windows memory checkpoints + 1–2 h soak із working set/heap/thread/handle evidence;
- `tools/post-r5-live-evidence.py` — evidence recorder для physical Kobo, real Readwise, unrestricted-browser PWA та real CBR/CB7 corpus;
- `tools/post-r5-live-acceptance-check.py` — fail-closed final aggregator;
- `tools/post-r5-live-acceptance-check-test.py` — positive/negative regression contracts;
- `docs/release/POST-R5-LIVE-ACCEPTANCE-RUNBOOK-UA.md` — порядок запуску та evidence contract.

Final checker вимагає exact candidate SHA-256, повний набір scenario/check IDs, `PASS` тільки з non-empty note/evidence, без unsafe relative paths та без byte-identical evidence reuse між checks. Для memory soak додатково потрібні duration не менше **3600 s** і достатня кількість samples.

## 3. Локальна перевірка harness/tooling

Підтверджено:

- post-R5 checker regression tests — **PASS**;
- Windows acceptance harness binding regression — **PASS**;
- `static_release_check.py` — **PASS**;
- `project-hygiene-check.py` — **PASS** після видалення generated `target/`/`__pycache__`;
- `build-check-v7.py` — **PASS**;
- implementation completeness — **PASS**;
- architecture module coverage — **17/17 PASS**;
- architecture check — **PASS**;
- supply-chain policy check — **PASS**.

Checklist-only Kobo recorder також пройшов dry-run; цей dry-run **не** є physical Kobo acceptance.

## 4. Targeted Calibre/batch regression

На JDK 21 / Maven 3.9.16 / supplied offline repository із наданим JavaFX 21.0.12 Linux bundle виконано:

- `CalibreConverterPluginTest`: **7/7 PASS**;
- `BookConversionUiServiceBatchTest`: **3/3 PASS**;
- targeted Maven result: **BUILD SUCCESS**.

Це підтверджує локальні code contracts, але **не замінює** Windows Calibre >5 min / cancel / process-tree / file-lock / packaged-app acceptance.

## 5. Exact-current full regression

Фінальний one-shot root offline `clean verify` для R6 candidate:

- **2087 tests**;
- **0 failures**;
- **0 errors**;
- **23 skipped**;
- Surefire reports: **20/20** test/product modules;
- result: **BUILD SUCCESS**.

Environment: JDK 21, Apache Maven 3.9.16, `C.UTF-8`, Xvfb для JavaFX, supplied offline Maven repository + supplied JavaFX 21.0.12 Linux artifacts.

## 6. Що R6 навмисно не закриває

Залишаються external/live gates:

- Windows repeated-selection/passive Book Details scenario;
- Windows Calibre >5 min, cancel/process-tree/locks/batch;
- packaged updater/installer/portable/DPAPI/DPI/rollback/signing;
- physical Kobo;
- real Readwise account;
- unrestricted-browser PWA;
- real large CBR/CB7 corpus;
- target Windows memory baseline + 1–2 h soak;
- exact-candidate connected CI/SBOM/SCA/CodeQL/reviewer/signing/distribution evidence.

Правило для будь-якого live defect лишається: **RED regression → minimal fix → targeted regression → full root `clean verify`**. Без live defect product code не змінювати.
