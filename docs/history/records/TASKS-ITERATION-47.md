# MyHomeLib — Iteration 47 — Comic Reader CBZ/CBR

**Дата:** 12.09.2026  
**Задача:** MHL-208  
**База:** audited Iteration 46  
**Release constraint:** MHL-010/011/012/017/018/019 залишаються OPEN до реального Windows/GitHub evidence; Iteration 47 не змінює їхній статус.

## 1. Native comic format semantics

CBZ/CBR переведено з generic archive semantics у native `BOOK`/`NATIVE` reader formats. `ComicArchiveImporter` реєструє фізичний `.cbz`/`.cbr` як одну книгу, а `ZipImporter`/`RarImporter` більше не трактують сторінки comic container як окремі книги.

## 2. Page ordering and archive safety

`ComicPageNameSupport` централізує whitelist зображень (`jpg/jpeg/png/gif/bmp`), safe-entry правила та natural numeric ordering. Небезпечні `..`, absolute/drive paths, hidden/unsupported entries відкидаються.

## 3. Lazy bounded decode

`ComicDocumentSession.open(...)` лише перелічує та сортує сторінки. Bytes сторінки читаються лише під час render. Ліміти: 64 MiB compressed bytes/page, 20M source pixels/page, 64 MiB raster LRU cache. Для viewport/thumbnails ImageIO використовує source subsampling.

## 4. Reader UX

`ComicReaderView` підтримує:
- fit page;
- fit width;
- continuous scroll;
- dual-page spreads;
- manga RTL visual ordering;
- thumbnails;
- zoom/navigation;
- bookmarks та persisted reading position/progress.

Heavy decode працює поза JavaFX thread; background results мають generation/session guards і cancellation.

## 5. Workspace integration

`NewReaderWorkspaceController` маршрутизує CBZ/CBR у `ComicDocumentSession`, адаптуючи `BookResourcePort.listArchiveEntries/readArchiveEntry` до `ComicPageSource`. Existing workspace lifecycle, autosave, reading sessions, bookmarks, Back/dispose reuse existing contracts. Text-only annotations/TOC/search не запускаються для image comics.

## Acceptance — виконано 12.09.2026

1. Natural page ordering + safe-name tests — PASS.
2. Lazy decode/cache + page safety tests — PASS.
3. CBZ physical container list/read test — PASS.
4. Comic importer one-container/one-book tests — PASS.
5. Reader source/workspace contract tests — PASS.
6. `tools/iteration47-comic-reader-check.py` — 8/8 PASS.
7. Architecture/completeness/localization/static/supply-chain/XML/archive gates — PASS.
8. Full 13-module offline `mvn test` — BUILD SUCCESS: 820 tests, 0 failures, 0 errors, 10 skipped.
9. E2E — 10/10 PASS.
10. Clean source package + SHA-256 — required for final handoff.

## Статус

**MHL-208: DONE (local technical acceptance).** External release gates MHL-010/011/012/017/018/019 залишаються OPEN і не підміняються локальними результатами.
