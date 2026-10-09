# MyHomeLib — V34 External Test R11 validation

**Дата:** 2026-10-04  
**База:** V34 External Test R10  
**Мета:** закрити remaining false-green ризик Windows live acceptance: report/session/candidate SHA були зв'язані, але functional `-Launcher` і memory `-ProcessId` ще не доводили, що фактично тестується саме runtime binary, встановлений із exact candidate package.

## 1. Production scope

Production Java, `pom.xml`, runtime resources і memory defaults **не змінювалися**.

Memory settings лишаються:

- Hikari interactive max pool: **2**;
- SQLite cache: **16 MiB/connection**;
- SQLite mmap: **64 MiB**;
- `BookCache`: **64 MiB**;
- `CoverCache`: **64 MiB**;
- desktop `-Xmx1024m`, G1.

R11 не відкриває нового RAM tuning без target-Windows evidence.

## 2. Exact R10 build gate закрито перед R11

Frozen R10 source ZIP SHA-256 `a5c74d8be8b2cd62119c05cb030072181b75d83f5a420bee8a307a22c17d88cf` був повторно розпакований і пройшов один безперервний root offline run:

```text
xvfb-run -a bash ./tools/invoke-maven.sh -B -o -Dmaven.repo.local=<offline-repo> clean verify
BUILD SUCCESS
```

Exact aggregate:

- **2087 tests**;
- **0 failures**;
- **0 errors**;
- **23 skipped**;
- **20/20** Surefire test/product modules;
- **21/21** reactor projects SUCCESS;
- total time **02:33 min**.

Це закрило попередній R10 local build gap без source change.

## 3. Defect A — Windows functional report не був bound до installed candidate runtime binary

R10 вже вимагав exact candidate SHA, candidate-bound host/session, candidate-bound acceptance harness і SHA-256 кожного PASS evidence file. Але `windows-post-r5-functional-acceptance.ps1` приймав довільний `-Launcher`. Сам report міг містити правильний candidate SHA, хоча screenshot/manual checks виконувалися на іншому installed `MyHomeLib.exe`.

R11 використовує наявний `windows-release-desktop-acceptance` як runtime source of truth:

- desktop acceptance після запуску SHA-256-verified candidate installer фіксує `launcherSha256` фактично встановленого `MyHomeLib.exe`;
- functional runner fail-closed вимагає PASS `windows-release-desktop-acceptance` того самого candidate/session/host;
- `launcher` path має збігатися з accepted desktop launcher;
- фактичний installed launcher повторно SHA-256-хешується й має збігатися з `launcherSha256` desktop report;
- desktop acceptance JSON копіюється у functional evidence directory та сам hash-bound через `desktopAcceptanceSha256`.

Отже правильний текстовий candidate SHA більше не достатній для PASS — functional evidence зв'язаний із конкретними bytes installed launcher.

## 4. Defect B — memory `ProcessId` не був bound до functional candidate binary

R10 memory runner перевіряв candidate/session/host і збирав raw heap/GC/working-set evidence, але довільний PID міг належати іншому `MyHomeLib.exe`/runtime process.

R11 до першого memory sample:

- вимагає PASS `windows-post-r5-functional.json` того самого candidate/session/host;
- читає functional `launcher` + `launcherSha256`;
- визначає executable path заданого `ProcessId`;
- вимагає exact path match із accepted launcher;
- SHA-256 executable bytes заданого PID має збігатися з functional `launcherSha256`;
- memory report записує `processPath`, `processSha256` і `functionalAcceptanceSha256`.

Final post-R5 checker повторно звіряє цей chain:

`desktop acceptance evidence/hash → installed launcher path/hash → functional report hash → memory process path/hash`.

## 5. RED regressions

`post-r5-live-acceptance-check-test.py` тепер додатково відхиляє:

1. memory `processSha256`, що відрізняється від functional `launcherSha256`;
2. memory report із неправильним `functionalAcceptanceSha256`;
3. змінений/copied desktop acceptance evidence після формування functional report.

Позитивний producer-compatible fixture із повним runtime-binary chain PASS.

## 6. Readiness hardening

`external-acceptance-readiness.py` тепер structural fail-closed перевіряє наявність runtime-binding contracts у:

- `windows-release-desktop-acceptance.ps1`;
- `windows-post-r5-functional-acceptance.ps1`;
- `windows-target-memory-soak.ps1`;
- `post-r5-live-acceptance-check.py`.

`windows-post-r5-functional-acceptance.ps1` і `windows-target-memory-soak.ps1` також додані до required readiness file set.

Affected offline regressions, acceptance harness binding, static release, architecture, implementation-completeness, build-check і hygiene — PASS перед full reactor.

## 7. Exact-current R11 root regression

Final exact-current R11 tree проходить root offline `clean verify` під JDK 21 / Maven 3.9.16 / `C.UTF-8` / Xvfb / supplied offline repository:

- **2087 tests**;
- **0 failures**;
- **0 errors**;
- **23 skipped**;
- **20/20** Surefire test/product modules;
- **21/21** reactor projects SUCCESS;
- `BUILD SUCCESS`.

Production Java/POM/runtime resources не змінені, але цей run виконується саме на final R11 tree, тому R11 більше не успадковує Maven PASS лише від старішого candidate.

## 8. Release decision

R11 є **Windows runtime-binary binding hardening candidate**.

Локально закрито:

- exact R10 build gap;
- functional arbitrary-launcher false-green path;
- memory arbitrary-PID/binary false-green path;
- final aggregation runtime-binary cohesion.

Зовнішні gates лишаються зовнішніми:

- real Windows Book Details / Calibre / updater / installer / portable / DPAPI / DPI / rollback;
- target Windows 1–2 h memory soak;
- physical Kobo;
- real Readwise;
- unrestricted-browser PWA;
- real large CBR/CB7 corpus;
- exact-candidate connected GitHub SBOM/SCA/CodeQL/signing/distribution.

Для нового Windows run порядок тепер строгий: candidate-bound bootstrap/desktop acceptance → functional acceptance того самого installed launcher → memory soak PID того самого launcher → final aggregation.
