# MyHomeLib — V34 External Test R10 validation

**Дата:** 2026-10-04  
**База:** V34 External Test R9, frozen base SHA `8385c2f192c6e6200fd1cc1ed0f71eaaca95acb9`  
**Мета:** закрити два залишкові fail-open ризики у post-R5 live evidence: незв'язаний SHA-256 окремих PASS evidence files та можливість заявити MEM-10 soak за summary без перевірки raw wall-clock/samples.

## 1. Production scope

Production Java, `pom.xml`, runtime resources і memory defaults **не змінювалися**.

Чинні memory settings лишаються:

- Hikari interactive max pool: **2**;
- SQLite cache: **16 MiB/connection**;
- SQLite mmap: **64 MiB**;
- `BookCache`: **64 MiB**;
- `CoverCache`: **64 MiB**;
- desktop `-Xmx1024m`, G1.

R10 не відкриває нового RAM tuning без target-Windows evidence.

## 2. Defect A — PASS evidence file не був cryptographically bound до report row

R9 checker вимагав окремий non-empty evidence file і не дозволяв повторне використання byte-identical evidence для різних PASS rows. Але JSON row містив лише шлях. Після створення report файл можна було замінити іншими bytes, а сам report JSON при цьому не змінювався.

R10 додає `evidenceSha256` до кожного PASS row у:

- `windows-post-r5-functional-acceptance.ps1`;
- `windows-target-memory-soak.ps1`;
- `post-r5-live-evidence.py` для Kobo / Readwise / PWA / real comics.

`post-r5-live-acceptance-check.py` тепер fail-closed вимагає валідний 64-hex `evidenceSha256` та точну відповідність SHA-256 фактичного referenced file.

RED regression: evidence file змінюється після створення report, report row/hash лишається старим. R9 logic приймала bundle; R10 відхиляє його через evidence SHA mismatch.

## 3. Defect B — MEM-10 summary не доводив фактичний one-hour raw soak

R9 перевіряв:

- `soakDurationSeconds >= 3600`;
- `sampleCount >= 30`;
- required memory/GC/handle metrics;
- peak/delta summary fields.

Але checker не читав raw `MEM-10.json`. Теоретично summary із заявленими `3600 s` і `30+ samples` міг не відповідати фактичним raw timestamps/samples.

R10 checker додатково читає raw `MEM-10.json` і перевіряє:

- `id=MEM-10`, non-empty note;
- timezone-aware `started`, `completed`, sample timestamps;
- фактичний `completed-started >= soakDurationSeconds`;
- не менше 30 raw samples;
- raw sample count дорівнює report `sampleCount`;
- sample timestamps strictly increasing;
- `elapsedMs`, cumulative GC collection count і GC total time monotonic;
- sampled timestamp span узгоджений із declared soak та sample interval;
- report last-value metrics точно дорівнюють останньому raw sample;
- working-set/heap peaks точно перераховуються з raw samples;
- working-set/heap/thread/handle/GC deltas точно дорівнюють `last-first`.

RED regressions підтвердили дві старі false-green умови:

1. raw MEM-10 триває лише 120 s при declared `3600 s`;
2. report `workingSetDeltaBytes` не відповідає raw samples.

Після fix обидва сценарії fail-closed, позитивний producer-compatible fixture PASS.

## 4. Readiness hardening

`external-acceptance-readiness.py --run-regressions` тепер безпосередньо запускає `post-r5-live-acceptance-check-test.py` і structural check вимагає R10 contracts `evidenceSha256` + raw MEM-10 validation.

Таким чином `READY_FOR_LIVE_EVIDENCE` більше не може бути отримано, якщо цей post-R5 integrity regression не пройшов.

## 5. Regression evidence

PASS:

- `post-r5-live-acceptance-check-test.py`;
- `evidence-contracts-test.py`;
- `windows-acceptance-evidence-check-test.py`;
- `windows-acceptance-harness-binding-test.py`;
- `github-connected-acceptance-test.py`;
- `github-acceptance-artifact-ingest-test.py`;
- `v71-final-external-acceptance-check-test.py`;
- `v71-final-evidence-bundle-check-test.py`;
- `release-candidate-integrity-test.py`;
- `zip-evidence-safety-test.py`;
- `external-acceptance-readiness-test.py`;
- `external-acceptance-readiness.py --run-regressions` → `READY_FOR_LIVE_EVIDENCE`;
- static release — PASS.

Generic PWA recorder smoke також підтвердив: скопійований PASS evidence отримує `evidenceSha256`, який точно дорівнює SHA-256 фактичного copied file.

## 6. Maven baseline

R10 не змінює Java/POM/runtime resources. Authoritative незмінний Java baseline лишається R7 one-shot:

- **2087 tests**;
- **0 failures**;
- **0 errors**;
- **23 skipped**;
- **20/20** Surefire test/product modules;
- **21/21** reactor projects SUCCESS;
- `BUILD SUCCESS`.

Exact-R9/R10 one-shot root `clean verify` у поточному execution environment не вдається завершити через зовнішній приблизно 5-minute tool limit; це не оголошується новим exact-candidate Maven PASS. Новий uninterrupted full build залишається connected CI/Windows requirement.

## 7. Release decision

R10 є **post-R5 live-evidence integrity candidate**.

Локально він закриває лише integrity/readiness дефекти tooling. Він **не** закриває:

- Windows Book Details / Calibre / updater / DPAPI / DPI;
- target Windows 1–2 h memory soak;
- physical Kobo;
- real Readwise;
- unrestricted-browser PWA;
- real large CBR/CB7 corpus;
- connected GitHub SBOM/SCA/CodeQL/signing/distribution.

Усі ці gates лишаються зовнішніми й мають бути повторно зібрані вже на frozen exact R10 SHA.
