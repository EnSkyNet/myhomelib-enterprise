# Сервер MCP

`myhomelib-mcp` — окремий сервер лише для читання, який працює без графічного інтерфейсу JavaFX. Він відкриває БД колекції в режимі лише для читання та надає інструменти `search_books`, `list_authors`, `list_series`, `list_genres`, `book_info`, `book_toc`, `book_text`, `search_inside_book`.

Приклад запуску: `java -jar myhomelib-mcp-8.0.0.jar --db /path/library.db`.
Також можна використати `--collection "Назва"`, коли доступна `meta.db`. Підтримується й змінна середовища `MYHOMELIB_DB`.
