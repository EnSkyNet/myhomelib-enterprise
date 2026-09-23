# MCP сървър

`myhomelib-mcp` е отделен read-only сървър, който работи без JavaFX графичен интерфейс. Той отваря базата на колекцията само за четене и предоставя `search_books`, `list_authors`, `list_series`, `list_genres`, `book_info`, `book_toc`, `book_text` и `search_inside_book`.

Пример: `java -jar myhomelib-mcp-8.0.0.jar --db /path/library.db`.
Може да използвате и `--collection "Име"`, когато има `meta.db`. Поддържа се и environment variable `MYHOMELIB_DB`.
