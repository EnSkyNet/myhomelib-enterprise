# Stage 11 — Extra-format Metadata and Images — Changelog

Дата: 2026-08-25

## Реалізовано

- Додано read-only metadata inspection для MOBI/AZW/AZW3 через PalmDB/MOBI/EXTH:
  - title;
  - author;
  - publisher;
  - description/annotation;
  - ISBN;
  - publication year;
  - language.
- Додано basic PDF metadata inspection (`Title`, `Author`, `Subject`, `Producer`) з bounded prefix parsing.
- Додано safe DjVu signature/basic metadata fallback без спроби повного декодування формату.
- `CoverReaderImpl` переведено з FB2-only fallback на реальний format dispatch.
- EPUB cover читається через `container.xml` + OPF `cover-image` / metadata / guide fallback.
- MOBI cover читається через EXTH cover offset та image-record fallback.
- PDF cover має conservative JPEG/DCT image extraction fallback.
- MOBI/PDF/DjVu при відсутності підтримуваного embedded cover отримують neutral generated PNG, а не exception.
- Generated fallback cover не використовує AWT/X11: PNG формується pure-JDK (`Deflater`/CRC32), тому працює headless/CI/sidecar-safe.
- FB2/EPUB all-images gallery використовує lazy `DocumentInspectionSession`; байти не накопичуються у heap наперед.
- Corrupt/unsupported files повертають graceful warning/empty inspection замість падіння details pane.

## Безпека / межі

- Binary/XML reads bounded.
- Archive entry materialization обмежено `ArchiveSafetyLimits.MAX_ENTRY_BYTES`.
- UI не виконує destructive metadata writes під час перегляду.
- PDF/DjVu support на цьому етапі навмисно basic/fallback: повний renderer не додавався, відповідно до roadmap.
