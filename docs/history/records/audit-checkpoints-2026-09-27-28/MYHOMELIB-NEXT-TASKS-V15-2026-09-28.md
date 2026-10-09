# MyHomeLib — залишкові задачі після V15 checkpoint

Дата: 2026-09-28  
Baseline для наступної сесії: `myhomelib-enterprise-optimized-v15-checkpoint-2026-09-28.zip`

## 1. Незмінні правила

1. defect: deterministic RED -> minimal fix -> targeted tests -> downstream contracts;
2. не переписувати стабільний код без підтвердженої причини;
3. collection isolation і lifecycle barriers важливіші за локальне прискорення;
4. refactor/decomposition — тільки після authoritative Maven/release gate;
5. environment/harness failure не маскувати як product PASS і навпаки.

## 2. P0 — authoritative Maven 3.9.16 / release gate

У середовищі з Maven 3.9.16 distribution або working wrapper download:

1. `./mvnw -v`;
2. `./mvnw clean verify`;
3. зафіксувати aggregate Surefire totals;
4. production package;
5. Linux `jpackage` app-image;
6. `tools/stage23-cross-platform-release-check.py` на реальному `dist/`;
7. packaged launcher `--release-smoke`;
8. ASCII + non-ASCII checkout path;
9. Windows x64 build/package/launcher acceptance.

## 3. P1 — Windows conversion/process acceptance

На Windows x64 відтворити окремо:

- Calibre conversion timeout;
- interrupt/shutdown під час `ebook-convert`;
- descendant process termination;
- immediate release of staged `input.*` file handle;
- cleanup private calibre workspace без delayed lock.

Якщо immediate cleanup реально RED — додати Windows regression/portable abstraction і тільки тоді мінімально змінювати `ProcessExecutionSupport`.

## 4. P1 — explicit book-conversion cancellation UX

Поточна гарантія: external Calibre має hard timeout (default 300 s), а інші UI/download/navigation operations більше не блокуються `CONVERSION`.

Окрема UX задача:

- визначити generalized cancellation contract для Operation Center;
- cancellable book-conversion entry;
- cancellation має реально сигналізувати `BooleanSupplier` і/або interrupt managed worker;
- terminal state тільки після фактичного завершення process cleanup;
- regression: cancel -> process tree terminated -> lease released -> no stale dialog callback.

Не робити ad-hoc cancel button лише для одного converter-а без lifecycle contract.

## 5. P1 — INDEX/DOWNLOAD concurrency follow-up

V15 має regression для correctness і non-blocking selective sync. На production-sized collection перевірити:

- download latency while 500k+ full Lucene rebuild is active;
- multiple parallel downloads under one INDEX root;
- executor saturation/rejection fallback;
- selective sync failure keeps Lucene freshness marker stale/recoverable;
- collection SWITCH remains blocked until all deferred repairs finish.

Будь-які зміни — тільки після measurable regression.

## 6. Architecture debt

Незмінний debt group:

- `DeferredMainFeatureActions`;
- `ReaderBookMapCoordinator`;
- `ReaderBookmarkCoordinator`.

Не змішувати його з operation-concurrency fixes.

## 7. P4 — staged decomposition, тільки після P0

Порядок із V14 зберігається:

1. `NewReaderWorkspaceController`;
2. `SearchWorkspaceController`;
3. `DesktopIntegrationBackend`;
4. `VersionedUserDataTransferAdapter`;
5. `InpxImportPipeline`.

Для кожного boundary: characterization tests -> one extraction -> targeted tests -> full Maven module suite -> relevant UI/Reader suite -> architecture/static gates.

## 8. Next checkpoint acceptance

- real Maven 3.9.16 `clean verify` green;
- production package/jpackage + release smoke green;
- Windows process/conversion acceptance captured;
- no new architecture debt;
- clean ZIP excludes `.git`, `.mvn/repository`, `target`, `dist`, temp/cache/logs while preserving Maven wrapper;
- ZIP CRC PASS + SHA-256 recorded.
