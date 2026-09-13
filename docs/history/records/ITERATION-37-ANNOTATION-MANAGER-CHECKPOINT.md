# Iteration 37 checkpoint — MHL-204 Annotation Manager

## Delivered

The desktop now has a global Annotation Manager for durable highlights and notes. The workspace performs search/filtering as a bounded SQLite query and exposes book/type/color/tag/date filters, deterministic updated-time ordering and page navigation. Rows can be opened directly in Reader, edited, deleted with confirmation, restored by one-step undo, or exported as filtered UTF-8 CSV.

## Architecture and safety

- UI consumes only application `AnnotationManager*` contracts; it imports neither annotation-domain nor SQLite repository types.
- Search, count, facets and rows execute off the JavaFX thread through `UiBackgroundExecutor`.
- User `%`, `_` and backslash characters are escaped before SQLite LIKE queries.
- Delete undo stores the complete annotation/anchor/tags/timestamps snapshot inside an application token, then restores through the repository boundary.
- Reader navigation is one-shot: after the requested persisted anchor resolves, the target is cleared so later reflow/refresh does not repeatedly move the user.
- Artifact mismatch or failed relocation never guesses another representation; Reader reports that the target is unavailable.
- CSV export walks the filtered result in bounded 500-row pages and writes UTF-8 incrementally.

## Next dependency

MHL-205 can reuse the same query/filter model to add deterministic Markdown/JSON/HTML exporters and configurable templates without changing Annotation Manager persistence or Reader anchoring.
