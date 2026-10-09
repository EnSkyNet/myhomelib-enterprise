# MyHomeLib — post-R5 live acceptance runbook

**База:** V34 External Test R5.  
**Призначення:** зібрати fail-closed evidence для зовнішніх Windows/device/provider/browser/corpus/soak gates, які локальний Linux/Xvfb regression не може закрити.

## 1. Правило candidate binding

Усі live-звіти мають належати одному exact Git candidate SHA. Не змішувати різні commits, rerolled binaries, Windows hosts/users або повторно зібрані пакети. Якщо production/source/release-tooling змінюється після початку live acceptance, freeze candidate повторюється.

Перед live-run локально:

```bash
python3 tools/external-acceptance-readiness.py --run-regressions
python3 tools/post-r5-live-acceptance-check-test.py
python3 tools/windows-acceptance-harness-binding-test.py
```

На Windows використовувати той самий `windows-host-binding`, що й existing MHL-011/012 harness.

## 2. Windows P0: repeated-selection / Book Details + Calibre + updater

На bound Windows 10/11 x64 standard-user session:

```powershell
.\tools\windows-post-r5-functional-acceptance.ps1 `
  -CandidateSha <40-char-Git-SHA> `
  -Launcher <installed-MyHomeLib.exe> `
  -CalibreExe <tools\converters\Calibre\ebook-convert.exe> `
  -LibmobiExe <tools\converters\libmobi\mobitool.exe>
```

Перед цим має бути завершений candidate-bound `windows-release-desktop-acceptance` із того самого `v71-windows-acceptance-start.ps1` session. Functional runner fail-closed перевіряє його JSON, candidate/session/host identity, installed launcher path та `launcherSha256`, повторно хешує фактичний installed `MyHomeLib.exe` і копіює desktop-acceptance JSON у власний evidence directory з окремим SHA-256 binding. Довільний `-Launcher`, який не збігається з accepted installed binary, відхиляється до збору PASS evidence.

Runner не симулює PASS. Він збирає screenshot/text evidence для:

- WBD-01..06 — repeated selection, passive Book Details non-blocking, status/Operation Center/JavaFX behavior;
- CAL-01..09 — real bundle versions, 1800 s timeout, >300 s FB2→EPUB, cleanup, cancel/process-tree/locks, close-while-active, batch max-2/cancel/partial failure, unrelated navigation;
- WIN-01..12 — install/portable/upgrade/updater/locks/health-marker/rollback/rejections/DPAPI/DPI/uninstall/signing-distribution.

`CAL-03` автоматично вимірює wall-clock interval між стартом і завершенням UI conversion та ставить FAIL, якщо він не перевищив 300 s. `CAL-05/06/07` автоматично перевіряють залишкові `ebook-convert`/`calibre-parallel` processes і `mhl-calibre-*` workspaces після відповідного сценарію.

Для dry checklist без заяви про acceptance:

```powershell
.\tools\windows-post-r5-functional-acceptance.ps1 `
  -CandidateSha <40-char-Git-SHA> -ChecklistOnly
```

### R14 regression — INDEX не блокує online DOWNLOAD

На тому самому candidate-bound Windows session окремо повторити реальний R14 сценарій:

1. запустити full-text rebuild, який у UI показує «Оновлення пошукового індексу»;
2. під час активного rebuild завантажити remote-only книгу з online library;
3. фізичне завантаження й storage commit не повинні чекати завершення rebuild;
4. після завершення rebuild/deferred repair catalogue search і, для supported format, full-text search мають бачити книгу;
5. Operation Center не повинен показувати INDEX↔DOWNLOAD blocking conflict;
6. SWITCH/DELETE/RESTORE під active INDEX мають залишитися заблокованими як collection-destructive lifecycle operations.

Зафіксувати timestamps start/end INDEX і DOWNLOAD, screenshot Operation Center та sanitized log fragment. Linux/Xvfb regression доводить code contract, але не підміняє цей Windows/network timing acceptance.

## 3. Physical Kobo / real Readwise / unrestricted-browser PWA / real CBR-CB7 corpus

Для кожного реального середовища evidence recorder створює standardized JSON/Markdown, копіює окремий evidence file для кожного PASS check і фіксує його `evidenceSha256` у тому самому report row:

```bash
python3 tools/post-r5-live-evidence.py kobo     --candidate-sha <SHA>
python3 tools/post-r5-live-evidence.py readwise --candidate-sha <SHA>
python3 tools/post-r5-live-evidence.py pwa      --candidate-sha <SHA>
python3 tools/post-r5-live-evidence.py comics   --candidate-sha <SHA>
```

Для підготовки checklist без PASS:

```bash
python3 tools/post-r5-live-evidence.py kobo --candidate-sha <SHA> --checklist-only
```

Provider secrets/tokens не вставляти в evidence, screenshots, notes або source archive.

## 4. Target Windows memory + 1–2 h soak

Запустити **той самий candidate-bound installed launcher**, який пройшов Windows functional acceptance, визначити його PID і виконати:

```powershell
.\tools\windows-target-memory-soak.ps1 `
  -CandidateSha <SHA> `
  -ProcessId <PID> `
  -SoakSeconds 3600 `
  -SampleIntervalSeconds 60
```

До першого sample memory runner fail-closed читає `windows-post-r5-functional.json`, перевіряє той самий candidate/session/host, звіряє `ProcessId` executable path із functional `launcher` і SHA-256 process executable із functional `launcherSha256`, а також записує SHA-256 самого functional report. Інший PID/binary або змінений functional report відхиляється.

Runner збирає MEM-01..09 для cold startup, stabilized idle, author 500+, large FB2 details, Search, Reader, large INPX import, post-import idle і Lucene reopen/rebuild. MEM-10 виконує ≥1 h sampling. Для кожного checkpoint фіксуються working set, JVM heap used/committed, thread/handle count, elapsed time, cumulative GC collections/time через `jstat -gcutil`, а також raw `jcmd` native-memory evidence (де NMT доступний). Для live run на PATH потрібні `jcmd` і `jstat` із JDK 21.

Для MEM-10 runner додатково зберігає raw timestamped samples, peak working set/heap та first→last delta для working set, heap, threads, handles, GC collections і GC time. Після soak оператор явно ставить `PASS` або `FAIL` на основі цього drift/GC evidence та додає decision note; `FAIL` робить весь memory report FAIL. Final checker повторно обчислює actual wall-clock, sample count, peaks/deltas і cumulative-GC consistency з raw `MEM-10.json`; одного declared `soakDurationSeconds=3600` недостатньо. NMT може бути недоступний, якщо exact packaged JVM не запущена з Native Memory Tracking — raw відповідь зберігається, але сама відсутність NMT не підміняє working-set/heap/GC measurements.

Не змінювати SQLite cache/mmap, `BookCache`, `CoverCache` або `-Xmx1024m` лише через одиничне спостереження. Будь-який tuning — тільки після representative same-machine A/B.

## 5. Fail-closed final aggregation

Коли шість post-R5 live reports мають PASS на одному candidate SHA:

```bash
python3 tools/post-r5-live-acceptance-check.py `
  --root target/post-r5-live-evidence `
  --candidate-sha <SHA> `
  --out-dir target/post-r5-live-acceptance
```

Якщо exact-candidate GitHub/CI/security/distribution evidence вже закритий existing final external gate, додати:

```bash
  --external-final target/v71-final-external-acceptance/v71-final-external-acceptance.json
```

Checker fail-closed вимагає exact required check sets, `overall=PASS`, один candidate SHA, non-empty evidence note, окремий non-empty evidence file та правильний `evidenceSha256` для кожного PASS row; забороняє `..`/absolute evidence paths, заміну evidence bytes після створення report і повторне використання byte-identical evidence для різних PASS rows. Windows functional і Windows memory reports додатково мусять мати той самий `acceptanceSessionId`, host/user fingerprints, OS version/build/architecture — evidence з різних Windows sessions/hosts не агрегується. R11 також перевіряє runtime-binary chain: desktop acceptance copy/hash → installed `launcherSha256` → functional report hash → memory `processPath/processSha256`; інший executable або змінений проміжний report робить aggregation FAIL. Для memory soak додатково вимагається `soakDurationSeconds >= 3600`, не менше 30 raw samples у MEM-10, реальний raw wall-clock не коротший за declared soak, handle count + cumulative GC metrics на кожному checkpoint та точна відповідність raw samples ↔ peak/drift/GC-delta summary.

`--external-final` приймає саме повний `myhomelib-7.1-final-external-acceptance` record: повний backlog MHL-010/011/012/017/018/019, PASS для `github`, `github-ingest`, `harness-binding`, `windows`, `windows-archive`, `candidate-binding`, exact candidate SHA та candidate-integrity/artifact/session binding. Спрощений JSON лише з `overall=PASS` більше не може закрити цей gate.

## 6. Після live defect

Якщо будь-який live check знаходить дефект:

1. gate лишається OPEN/FAIL;
2. спочатку додати RED regression test, який відтворює знайдений defect настільки близько, наскільки дозволяє локальне середовище;
3. зробити мінімальний production fix без відкриття V35 scope;
4. targeted tests → root offline `clean verify` → static/release/localization/UI/architecture gates;
5. freeze нового exact candidate й повторити всі evidence, які source/hash change робить невалідними.

Linux/Xvfb, mocks, fixtures або checklist-only reports не є заміною real Windows/device/provider/browser acceptance.
