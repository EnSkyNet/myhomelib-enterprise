# MyHomeLib — V6 practical deep-audit checkpoint

Дата: 2026-09-27  
Baseline: `myhomelib-enterprise-audited-performance-v5-fixed-2026-09-27.zip`

## Принцип цього циклу

Це desktop/home-library application, але якість не спрощувалась. Зміни робились лише для відтворених дефектів або для перевіреного build/runtime defect. Пріоритет: коректність, проста поведінка, bounded resources, recovery після мережевих збоїв, реальна збірка/запуск, без зайвої архітектурної складності.

## Підтверджені дефекти і виправлення

### 1. Reader search: integer overflow біля максимальних text offsets

`ReaderSearchService` раніше обчислював межі вікна через `int + int`. Для текстового offset близько `Integer.MAX_VALUE` це могло переповнитись і сформувати від'ємну/некоректну межу `getText()`.

Виправлення:
- додано overflow-safe `boundedEnd()` з арифметикою через `long`;
- застосовано до primary window, overlap та context end;
- додано regression test для paragraph offset біля `Integer.MAX_VALUE`;
- Unicode/case-insensitive пошук продовжує працювати на original coordinates без lowercased-копії книги.

### 2. HTTP resume: неповна/підмінена partial response

Посилено `HttpResumeSupport` і direct online-book/catalog download:
- strict validation `Content-Range start/end/total`;
- звірка `Content-Length == end-start+1`, коли заголовок є;
- overflow-safe expected total;
- перевірка entity validator під час resume;
- перевірка фактичного розміру partial після запису проти очікуваного total;
- truncated resumed payload не публікується як complete.

### 3. HTTP resume validator: malformed ETag

Раніше некавычкований `ETag` міг потрапити в `If-Range` як начебто strong ETag.

Виправлення:
- resume використовує лише quoted strong ETag;
- malformed/weak ETag -> fallback на `Last-Modified`;
- якщо придатного validator немає, partial state не використовується для небезпечного resume.

Regression tests додані для malformed unquoted ETag з/без `Last-Modified`.

### 4. Catalog resume: malformed Content-Range не self-recovering

Для online-book invalid resume вже приводив до чистого retry, а catalog path після malformed `Content-Range` очищав partial state, але завершував поточний виклик замість негайного fresh download.

Виправлення:
- invalid resume response класифікується як retryable після безпечного cleanup;
- наступна спроба виконується без stale `Range`/`If-Range`;
- cause не губиться в `RetryableDownloadException`.

Regression test спочатку відтворив failure, після fix проходить.

### 5. Source ZIP / shell helper execution

У supplied source ZIP helper scripts у `tools/*.sh` можуть мати mode `0644`. Root scripts раніше викликали їх як executable, через що build/package path залежав від способу розпакування ZIP.

Виправлення:
- root Unix scripts викликають non-top-level helpers через `bash ...`;
- static release check фіксує цей контракт.

### 6. Реальний jpackage blocker: відсутній bundled libmobi source/runtime

V5 містить `tools/converters/libmobi/mobitool.exe`, але не містить Linux/macOS `mobitool` і не містить `tools/converters/libmobi/build-src`. При цьому `prepare-runtime-tools.sh` безумовно запускав CMake на відсутньому source dir, тому Linux `package-desktop.sh app-image` падав до `jpackage`.

Окремо `tools/CONVERTERS-SHA256.txt` містить 180 записів, із яких у supplied V5 відсутні 127: 126 під `tools/converters/libmobi/build-src` і Linux/macOS `mobitool`.

Виправлення зроблено без прихованого зниження release-вимог:
- normal desktop packaging: `libmobi` є optional runtime tool; якщо bundled source/runtime відсутній, збірка продовжується без embedded `mobitool`;
- runtime application і далі може знайти configured/system `mobitool`;
- full `release.sh` / `release.ps1` встановлюють `MHL_REQUIRE_LIBMOBI=1` і fail-closed, якщо повний libmobi runtime/license відсутній;
- `mobitool` ніколи не пакується без `COPYING`;
- Linux та Windows packaging/smoke contracts вирівняні;
- static release check перевіряє strict/optional contract.

Таким чином звичайну збірку домашньої бібліотеки не блокує відсутній optional converter, але повний реліз не може помилково назвати такий bundle повним.

## Перевірки після виправлень

### Targeted / module tests

- HTTP resume + online book + remote catalog: **47 tests, 0 failures, 0 errors, 0 skipped**.
- `myhomelib-reader`: **124 tests, 0 failures, 0 errors, 1 skipped**.
- `myhomelib-infrastructure`: усі **161 test classes** перевірені чотирма контрольованими прогонами:
  - chunk 1: 211 tests, 0 failures/errors, 1 skipped;
  - chunk 2: 170 tests, 0 failures/errors, 2 skipped;
  - chunk 3: 89 tests, 0 failures/errors, 3 skipped;
  - chunk 4: 132 tests, 0 failures/errors, 3 skipped;
  - разом: **602 tests, 0 failures, 0 errors, 9 skipped**.

### Static/security gates

PASS:
- `tools/implementation-completeness-check.py`;
- `tools/static_release_check.py`;
- `tools/xml-archive-security-check.py`;
- `tools/supply-chain-policy-check.py`;
- `bash -n` для змінених Unix build/package scripts.

Implementation completeness після V6:
- production Java files: 1170;
- TODO/FIXME/unsupported markers: 0;
- empty public/protected methods: 0;
- unused explicit imports: 0;
- unused Spring constructor dependencies: 0;
- exact cross-file method clones >=180 chars: 0 warnings.

### Build/package/runtime

- full reactor `./mvnw -o -Dmaven.repo.local=<offline-repo> -DskipTests package`: **BUILD SUCCESS**, 10.649 s у цьому середовищі;
- Linux `jpackage --type app-image`: **PASS** після packaging fix;
- packaged launcher smoke: **PASS**, `MYHOMELIB_RELEASE_SMOKE_OK`;
- packaged EPUBCheck 5.4.0 smoke: **PASS**;
- `stage23-cross-platform-release-check.py --dist dist`: **PASS** для Linux app-image/runtime; expected warning: checksums не генерувались у цьому app-image-only прогоні; bundled `mobitool` відсутній через missing source/runtime.

### Consolidated clean verify

`./mvnw ... clean verify` був повторно запущений. До ліміту audit runner усі вже завершені модулі були green, включно з `myhomelib-application` 439/0/0/1 та кількома наступними модулями, але runner перервав процес під час `myhomelib-infrastructure` test compilation (module 11/20). Це **не test failure**, але й **не зараховується як consolidated PASS**.

Тому release evidence тут сформульовано чесно: production package PASS + changed-module suites PASS + static/security PASS, а один безперервний 20/20 `clean verify` лишається зовнішнім acceptance item для середовища без короткого execution ceiling.

## Реальні великі дані, перевірені в цьому циклі

На `flibusta_online_fb2.inpx`:
- processed/imported: **707154 / 707154**;
- errors: **0**;
- explicitly deleted: **135207**;
- active books: **571947**;
- Lucene documents після rebuild: **571947**;
- reconciliation active DB books == Lucene docs: **точний збіг**;
- import pipeline у зафіксованому прогоні: близько **54.4 s**, ~12.99k records/s;
- Lucene rebuild: близько **35.4 s**, ~16.17k docs/s;
- measured Lucene peak heap: **193981872 bytes** (~185 MiB).

Це підтверджує, що поточний INPX -> SQLite -> Lucene pipeline працює на реальному каталозі >700k записів, а подальші performance-зміни треба робити тільки за профілем, не інтуїтивно.

## Що лишається

1. Відновити verified `libmobi 0.12 build-src` + `COPYING` (або інший повний ліцензований runtime input), після чого пройти strict `release.sh` і packaged mobitool smoke.
2. Один безперервний 20/20 `clean verify` у середовищі без короткого runner timeout.
3. Реальний Windows x64 `jpackage` + launcher/runtime smoke; Windows scripts виправлені статично, але Linux-середовище не може чесно підтвердити виконання PE/runtime.
4. Продовжити P1 audit: Unicode canonical-equivalence expectations, parser fuzz/bounds, cancellation/shutdown/SQLite contention. Не змінювати поведінку без відтвореного дефекту або виміряного bottleneck.

## Висновок

V6 є кращою практичною базою за V5 для подальшої роботи: мережевий resume безпечніший і самовідновлюваний, Reader search не має int-overflow у window arithmetic, desktop app-image реально збирається і запускається, а optional converter більше не блокує звичайний package path і водночас не може непомітно пройти як повний release.
