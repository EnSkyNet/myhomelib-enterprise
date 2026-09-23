# MCP server

`myhomelib-mcp` is a separate read-only server that runs without the JavaFX graphical interface. It opens a collection database read-only and exposes `search_books`, `list_authors`, `list_series`, `list_genres`, `book_info`, `book_toc`, `book_text` and `search_inside_book` tools.

Example: `java -jar myhomelib-mcp-8.0.0.jar --db /path/library.db`.
You can also use `--collection "Name"` when `meta.db` is available. The `MYHOMELIB_DB` environment variable is supported as well.
