# MyHomeLib — V34 External Test R9 validation

**Дата:** 2026-10-04  
**База:** V34 External Test R8, frozen base SHA `1810a78bdd9f9647eaf246742e27c9852efbff53`  
**Мета:** закрити два fail-open ризики у post-R5 live acceptance aggregation без зміни production Java/POM/runtime behavior або memory tuning.

## 1. Знайдені acceptance-evidence defects

### 1.1. Windows functional + memory reports могли походити з різних sessions/hosts

`post-r5-live-acceptance-check.py` окремо перевіряв candidate SHA та вміст functional/memory reports, але не вимагав, щоб обидва Windows reports були отримані в одній `acceptanceSessionId` і на тому самому host/user/OS identity.

Це створювало false-green risk: формально валідні reports одного candidate SHA можна було агрегувати навіть тоді, коли functional acceptance і memory soak фактично виконувалися у різних Windows sessions або на різних машинах.

### 1.2. `--external-final` не був зв'язаний із реальним final-evidence schema

Фактичний `v71-final-external-acceptance.json` є schema-bound record `myhomelib-7.1-final-external-acceptance`; candidate binding та artifact/session hashes знаходяться в evidence row `candidate-binding`.

Старий post-R5 checker шукав SHA у спрощеній структурі й не перевіряв повний evidence/backlog contract. Через це реальний final record не був надійно сумісний з checker, тоді як shallow JSON на кшталт `{"overall":"PASS","candidateSha":"..."}` мав недостатньо строгий contract.

## 2. R9 fix

Production Java, `pom.xml`, runtime resources і всі memory defaults **не змінювалися**.

`tools/post-r5-live-acceptance-check.py` тепер:

- перевіряє `candidateSha`, `acceptanceSessionId`, `hostFingerprintSha256`, `userFingerprintSha256`, `osVersion`, `osBuild`, `osArchitecture` для Windows functional/memory reports;
- вимагає повну тотожність цих Windows identity fields між functional acceptance та memory soak;
- використовує central `evidence_contracts.validate_record` для `--external-final`;
- приймає лише scenario `myhomelib-7.1-final-external-acceptance` з `overall=PASS` і без failure;
- вимагає exact backlog MHL-010/011/012/017/018/019;
- вимагає exact evidence set `github`, `github-ingest`, `harness-binding`, `windows`, `windows-archive`, `candidate-binding`;
- перевіряє exact candidate SHA в усіх evidence rows;
- для `candidate-binding` перевіряє Windows integrity candidate SHA, MSI/EXE/portable/checksums/integrity/source-tree/dist-manifest/harness/host/user SHA-256, acceptance session UUID, positive GitHub run IDs, HTTPS run URLs та project version.

Runbook і static-release contracts синхронізовано з цими вимогами.

## 3. RED → GREEN regressions

До fix новий regression для Windows cohesion падав через відсутній `validate_windows_cohesion` — RED підтверджено.

Після fix `post-r5-live-acceptance-check-test.py` PASS і додатково доводить fail-closed поведінку для:

- mixed Windows acceptance sessions;
- shallow fake external-final JSON;
- mismatched `windowsIntegrityCandidateSha`;
- позитивного realistic full final external record.

Також PASS:

- `github-connected-acceptance-test.py`;
- `github-acceptance-artifact-ingest-test.py`;
- `release-candidate-integrity-test.py`;
- `windows-acceptance-evidence-check-test.py`;
- `windows-acceptance-harness-binding-test.py`;
- `external-acceptance-readiness.py --run-regressions` → `READY_FOR_LIVE_EVIDENCE`;
- supply-chain policy;
- static release;
- architecture + architecture regression + module coverage (**17 runtime/product modules**);
- implementation completeness;
- project hygiene/build-check після видалення generated Python cache;
- UI action inventory **485/485**;
- action-specific behavior **484 automated + 1 justified exclusion**;
- user-function documentation **485/485 → 31 flows**;
- desktop localization **3746** synchronized UK/EN/BG keys;
- Web localization **87** keys;
- help/genre localization **75** pages / **335** genres per locale.

## 4. Maven baseline і R9 verification limitation

R9 не змінює Java source, `pom.xml` або runtime resources. Authoritative one-shot Maven baseline залишається frozen R7 run:

- **2087 tests**;
- **0 failures**;
- **0 errors**;
- **23 skipped**;
- Surefire test/product modules: **20/20**;
- reactor projects: **21/21 SUCCESS**;
- result: **BUILD SUCCESS**.

На exact-current R9 worktree root Maven запускали повторно під JDK 21, Maven 3.9.16, supplied offline repository, JavaFX 21.0.12, `C.UTF-8` і Xvfb. Single-thread `clean verify`, `-T 4 clean verify` та warm `-T 4 verify` були примусово завершені зовнішнім execution limit приблизно через 5 хвилин до reactor summary. До termination у завершених модулях не зафіксовано Maven `BUILD FAILURE`, test failure або error.

Тому R9 **не** оголошує новий one-shot `BUILD SUCCESS`; це має бути отримано в exact-candidate CI/Windows environment. R7 baseline не підміняє цей майбутній exact-R9 result, а лише доводить незмінність Java/build baseline для R9 release-evidence-only diff.

## 5. Memory decision

R9 не відкриває новий memory tuning. Залишаються чинними виміряні R5/R7 рішення:

- Hikari interactive max pool: **2**;
- SQLite cache: **16 MiB/connection**;
- SQLite mmap: **64 MiB**;
- `BookCache`: **64 MiB**;
- `CoverCache`: **64 MiB**;
- desktop `-Xmx1024m`, G1;
- final controlled Reader retention probe: **224 B** після 20 open/close.

Наступна memory-зміна можлива тільки після representative same-Windows-machine A/B та 1–2 h soak з Working Set/heap/GC/handles/threads evidence.

## 6. R9 release decision

R9 є **live-acceptance evidence cohesion candidate**. Він не закриває жоден Windows/device/provider/browser/connected-CI gate локально.

Наступний крок після freeze exact R9 SHA:

1. Windows functional + memory acceptance на одному bound host/user/session і одному SHA;
2. physical Kobo / real Readwise / unrestricted PWA / real CBR-CB7;
3. exact-candidate GitHub CI + SBOM/SCA/CodeQL/integrity/signing evidence;
4. final post-R5 aggregation із реальним full external-final record;
5. новий повний exact-R9 `clean verify`/CI `BUILD SUCCESS` у середовищі без локального 5-minute execution limit.
