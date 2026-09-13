# Runtime fix-9 — 2026-08-30

Фактичний Windows runtime виявив:

1. `ConnectionScript, рядок 1: невідома команда HTTPS://FLIBUSTA.IS/`.
   MyHomeLib 2.5 ставить unknown command `Code=-1` і пропускає її. Java v7.1 тепер сумісно допускає тільки bare HTTP/HTTPS URL preamble, не послаблюючи validation інших команд.
2. Remote books мали `collection_root=.../.myhomelibcorp/cache/catalog-updates`.
   Це transient catalog cache. Import context тепер використовує permanent download root.
3. Startup міг invalidate Lucene через `syncSeriesFromBooks()` перед freshness check.
   Reuse перевіряється перед derived series sync; reusable marker reseal після sync.
4. `DirectoryChooser` міг кинути `IllegalArgumentException` для invalid initial folder.
   Додано readable-directory validation + retry без initial folder.

Validation: 46/46 offline checks PASS; standalone Java smoke PASS. Connected Maven/JavaFX acceptance лишається зовнішнім кроком.
