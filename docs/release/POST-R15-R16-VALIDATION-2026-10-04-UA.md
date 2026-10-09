# MyHomeLib — V34 External Test R16 validation

**Дата:** 2026-10-04  
**База:** V34 External Test R15 / R15.1 portable launcher hotfix  
**Фокус:** defects, підтверджені повторним Windows тестом користувача: QUERY→CONVERSION conflict, repeated download local files, INDEX/download starvation, stale cover pipeline, long ConnectionScript waits, accumulated author/background QUERY tasks.

## 1. Фактичний Windows evidence

Наданий R15.1 `myhomelib.log` показав:

- online downloads могли чекати ~120 s і більше; великий `729586` мав невдалий network flow 245311 ms до HTTP 502;
- після retry той самий `729586` фізично зберігся локально, але stale catalogue projection/local lookup лишав ризик повторного materialization у наступних flows;
- Book Details parser уже швидкий: великий 17.7 MiB FB2 — 683 ms, звичайні томи переважно 30–73 ms;
- conversion не стартував: `LibraryOperationConflictException: CONVERSION недоступна, поки виконується QUERY`;
- під час shutdown залишалися накопичені author/background tasks; executor повідомляв, що running tasks тримають lifecycle leases.

Тому R16 виправляє orchestration/state, а не FB2 parser.

## 2. Operation coordinator / background work

- Shared QUERY root тепер сумісний із same-collection `DOWNLOAD`, `CONVERSION`, `INDEX`, `EXPORT`, `READER_STATE`; destructive lifecycle operations лишаються несумісними.
- Coordinator рахує активні operation types усередині shared session, а не лише root type.
- Download бачить active INDEX навіть якщо root session — QUERY, і відкладає derived Lucene/full-text synchronization поза caller thread.
- CONVERSION винесена в dedicated operation virtual-thread lane, тому пасивна presentation queue не затримує старт user operation.
- Author workspace використовує latest-request-wins cancellable INTERACTIVE QUERY tasks; superseded metadata/books requests скасовуються і cancellation не логуються як user-visible failure.

## 3. Already-local books / repeated HTTP download

`BookDownloadCoordinator` тепер виконує local-first reconciliation:

- перевіряє DTO storage;
- повторно перевіряє current active collection root;
- реконструює legacy online FB2 archive path через LibID/title/author;
- `BookResourceResolver` приймає server-renamed FB2 entry усередині ZIP;
- local check виконується до network і повторно після download-slot acquisition / authoritative DB reload;
- `canDownload`, open/export/batch/ensure-local paths використовують той самий physical local resolver.

На всіх 18 наданих ZIP direct real-file probe: **18/18 existing local archives знайдено**, включно з server-renamed entries та `729586`.

## 4. INDEX + DOWNLOAD

- Download preflight/authoritative reload переведено в NETWORK lane.
- INDEX lease може співіснувати з DOWNLOAD у same collection.
- Якщо INDEX є active member shared session, committed download не чекає Lucene writer: derived-index repair schedule виконується асинхронно після фізичного save/DB commit.
- Regression окремо перевіряє QUERY-root + joined INDEX + DOWNLOAD.

## 5. Covers

Real supplied corpus cover probe: **18/18 covers extracted**; звичайні книги приблизно 6–54 ms, `729586` — 105 ms.

R16 presentation fix:

- cover extraction працює на INTERACTIVE lane;
- попередній request скасовується при selection change;
- stale result не може переписати нову selection;
- failed/cancelled current request очищує `currentLoadingRequest`, тому повернення до тієї самої книги реально запускає retry замість постійно порожньої обкладинки;
- production log отримав `COVER_START/DONE/NONE/FAIL`.

## 6. Network hang policy

- default `online.readTimeoutSeconds`: 30 s;
- ConnectionScript request timeout hard-capped до 30 s навіть якщо copied portable settings містять legacy 120 s;
- default automatic retry count = 0, щоб timeout не множився на кілька хвилин;
- long network work ізольований у NETWORK virtual-thread lane й не займає Book Details/cover/operation workers.

## 7. Conversion

- Windows R15.1 conversion error був lifecycle conflict до запуску converter; R16 дозволяє CONVERSION при passive QUERY root.
- FBC vendor path і R15 fail-closed FB2/FB2.ZIP→EPUB policy збережені: default AUTO не робить silent slow Calibre fallback.
- original-primary/V68 repair/secondary-conversion/EPUB OPF fallback з R13 збережені.

## 8. Real-corpus evidence

- local archive reconciliation: **18/18** supplied files;
- cover extraction: **18/18** supplied files;
- попередній isolated details stress на цьому corpus: всі книги локально аналізуються без network dependency; Windows R15.1 log також підтвердив 30–73 ms normal details та 683 ms для 17.7 MiB compilation.

## 9. Regression

Перший exact-R16 full `clean verify` знайшов stale cancellation test, який усе ще mock-ав старий generic conversion executor. Test contract оновлено під dedicated operation lane; після цього виконано повторний **uninterrupted root offline `clean verify`**:

- **2106 tests**;
- **0 failures**;
- **0 errors**;
- **23 skipped**;
- Surefire XML: **631 reports / 20 test-product modules**;
- **21/21 reactor projects SUCCESS**;
- **BUILD SUCCESS**;
- total time **02:39 min**.

Affected targeted regression перед full root: application 26 + infrastructure 38 + UI 21 = **85 tests / 0 failures / 0 errors**; після final cancellation fix додатково conversion/UI targeted **17/17 PASS**.

Static/release gates: architecture PASS, implementation completeness PASS, static release PASS, supply-chain PASS, UI action coverage 485/485, action-specific behavior 484+1, UK/EN/BG 3746, Web 87, help 75.

## 10. Windows retest

На fresh R16 portable перевірити в одному сеансі:

1. existing 18-book corpus — жодного HTTP для вже фізично присутніх ZIP;
2. INDEX rebuild + online download — download стартує без очікування завершення INDEX;
3. rapid selection — details і covers стабільно повертаються після forward/back;
4. `729586` FB2.ZIP→EPUB — conversion стартує навіть при background QUERY, route у log має бути FBC;
5. restart — original лишається primary, EPUB secondary; EPUB Book Details працює;
6. close app після rapid navigation — не повинно бути burst `AuthorWorkspaceController` cancellation ERROR або stuck lifecycle lease warning.
