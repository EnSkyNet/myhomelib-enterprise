# Stage 10 — Rich Book Details / Annotation — Changelog

Дата: 2026-08-25

## Реалізовано

- `BookDetailsController` переведено на асинхронне завантаження повного `BookDto` за ID; details більше не залежать від урізаного DTO рядка таблиці.
- `BookDto` / `BookMapper` розширено повними author/genre DTO, translators, city, source URL, LibID та library rate.
- Details pane перебудовано як scrollable structured metadata panel.
- Authors / series / genres / keywords / publisher стали deep links у відповідні navigation/search flows.
- Додано publication metadata: publisher, year, ISBN, language, source language, translators.
- Додано reading/user-data section: local state, rating, reading progress, review, groups/source.
- Reader inspection API повертає bounded TOC preview, character count, word count, chapter count та image descriptors.
- Word count виконується chunk-wise без materialization усього тексту книги через `getFullText()`.
- FB2 source language читається secure/bounded XML scan.
- Image gallery є lazy/paged: список ресурсів формується одразу, байти зображення відкриваються лише для поточної сторінки.
- Async details refresh захищено generation token, тому повільна попередня книга не може перезаписати новіший selection.
- Archive entry materialization перенесено в application-level `ResolveBookContentUseCase`; UI більше не залежить напряму від storage/archive output port.
- Тимчасово materialized archive entry видаляється при закритті details session.

## Поведінка metadata

Catalog/user data лишаються authoritative. File inspection використовується як read-only fallback для порожніх publisher/ISBN/annotation/language/year та не перезаписує rating/progress/review/catalog relations.
