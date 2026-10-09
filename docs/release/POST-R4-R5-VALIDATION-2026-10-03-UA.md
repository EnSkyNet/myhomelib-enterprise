# MyHomeLib — V34 External Test R5 validation

**Дата:** 2026-10-03  
**База:** V34 External Test R4  
**Мета:** закрити evidence-first memory A/B з handoff без погіршення 707k-class import, залишити лише виміряні production-зміни та повторити повний release regression.

## 1. Same-environment memory A/B на 707 154-book реальній БД

Використано наданий `flibusta_online_fb2.inpx`:

- records: **707 154**;
- input size: **39 184 820 bytes**;
- SHA-256: `75bebb7a7ccf203bd934ef2af986f17d737ba4c4abfc277956f60bb84a6c7655`;
- generated SQLite DB для interactive probe: приблизно **1.58 GB**;
- усі профілі порівнювалися в окремих JVM у тому самому Linux/JDK 21 environment і на тій самій БД.

Для контрольованого A/B `DataSourceConfig` отримав package-private `InteractiveProfile`; production не читає A/B system properties. Test-only `InteractiveDataSourceTestProfiles` та opt-in `RealSqliteInteractiveTuningProbeTest` дозволяють відтворити профіль без зміни production API/defaults.

Raw evidence: `docs/release/PERFORMANCE-R5-707K-SQLITE-AB-2026-10-03.json`.

### R4 baseline A: Hikari 4 / SQLite cache 16 MiB / mmap 64 MiB

Два окремі interactive runs після прогріву:

- RSS after: **235 405 312 B**, **229 597 184 B**;
- page median: **933.137 / 957.672 ms**;
- author median: **1449.801 / 1414.600 ms**;
- statistics median: **1047.306 / 941.150 ms**;
- parallel-4 median: **14.764 / 14.375 ms**.

### Candidate B: Hikari 2 / SQLite cache 16 MiB / mmap 64 MiB

Два окремі runs:

- RSS after: **196 435 968 B**, **188 071 936 B**;
- page median: **945.280 / 956.200 ms**;
- author median: **1389.520 / 1544.677 ms**;
- statistics median: **1001.900 / 1017.530 ms**;
- parallel-4 median: **12.999 / 14.265 ms**.

Середня зміна B проти A:

- RSS after warm interactive probe: **−17.31%**;
- page median: **+0.56%**;
- author median: **+2.44%**;
- statistics median: **+1.56%**;
- parallel-4 median: **−6.43%**.

Ці числа є evidence саме для контрольованого probe, а не заявою про повний desktop idle RSS на Windows.

## 2. Відхилений SQLite reduction

Окремо перевірено `cache 16 → 8 MiB` та `mmap 64 → 32 MiB`.

- pool 4 + reduced SQLite: author median **1692.656 ms**;
- pool 2 + reduced SQLite: author median **1848.528 ms**.

Порівняно з R4 baseline average (**1432.201 ms**) це матеріальне погіршення author-query. Тому production SQLite cache/mmap у R5 **не зменшуються**.

Так само без representative A/B залишено без змін:

- `BookCache` — 64 MiB;
- `CoverCache` — 64 MiB;
- desktop `-Xmx1024m`.

## 3. 707k fresh import regression для pool=2

Fresh import виконано окремо для R4 pool=4 і candidate pool=2.

Baseline pool=4:

- imported: **707 154**;
- errors: **0**;
- import duration: **57 238 ms**;
- throughput: **12 354.62 books/s**;
- `quick_check=ok`.

Candidate pool=2:

- imported: **707 154**;
- errors: **0**;
- import duration: **54 683 ms**;
- throughput: **12 931.88 books/s**;
- `quick_check=ok`;
- counts ідентичні baseline: books **707154**, authors **166231**, bookAuthors **856591**, genres **272**, bookGenres **1102219**, catalogState **707154**, deleted **135207**.

Import duration не погіршився; у цьому run він був приблизно **4.46% меншим**. OOM/GC-thrash не спостерігалося.

Після production-default switch виконано idempotent re-import:

- imported **0**;
- processed/skipped **707 154**;
- errors **0**;
- duration **590 ms**;
- counts незмінні.

## 4. Production рішення R5

Прийнято тільки виміряне покращення:

- `DataSourceConfig.INTERACTIVE_MAX_POOL_SIZE`: **4 → 2**.

Не змінено:

- interactive SQLite cache: **16 MiB/physical connection**;
- SQLite mmap: **64 MiB**;
- `BookCache`: **64 MiB**;
- `CoverCache`: **64 MiB**;
- desktop heap: **`-Xmx1024m`**.

## 5. UI action evidence hardening

Новий real-data performance probe випадково містив загальні слова на кшталт `add`/`next`/`sleep`. Старий action-specific checker міг сприйняти такі слова в будь-якому JUnit test як programmatic UI evidence.

R5 робить gate точнішим: файли `*ProbeTest.java` є opt-in observational/performance diagnostics і більше не використовуються як action-specific UI behavior evidence. Reviewed registry refresh після зміни лишається повним:

- UI actions: **485/485**;
- action-specific behavior: **484 automated + 1 justified exclusion**;
- user-function documentation: **485/485 → 31 flows**.

## 6. Full regression

Після production `pool=2` виконано повний root offline `clean verify` під `C.UTF-8` + Xvfb, JDK 21, Maven 3.9.16 та supplied offline repository.

Exact aggregate першого повного R5 regression run:

- **2087 tests**;
- **0 failures**;
- **0 errors**;
- **23 skipped**;
- Surefire reports: **20/20** test/product modules;
- `BUILD SUCCESS`.

Порівняно з R4 додалися opt-in/test-contract checks; додатковий skip — real SQLite tuning probe, який запускається окремо тільки з явним real DB path.

Static/release gates після reviewed UI evidence refresh:

- UI action coverage — PASS;
- UI action behavior — PASS;
- user-function documentation — PASS;
- UK/EN/BG localization — PASS;
- Web localization — PASS;
- help/genre localization — PASS;
- architecture — PASS;
- static release — PASS;
- supply-chain policy — PASS;
- implementation completeness — PASS.

## 7. Що R5 не оголошує закритим

Linux same-DB probe підтверджує доцільність `Hikari 4 → 2`, але не підміняє target-machine Windows memory baseline/soak. Так само зовнішніми залишаються physical Kobo, packaged Windows updater/Calibre/DPAPI/DPI/file-lock/rollback/signing, real Readwise account, unrestricted-browser PWA, real large CBR/CB7 corpus та 1–2 h production soak.
