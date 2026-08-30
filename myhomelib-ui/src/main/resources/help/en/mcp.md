# MCP server

myhomelib-mcp is a standalone read-only Model Context Protocol server. It opens a collection database without JavaFX and exposes: search_books, list_authors, list_series, list_genres, book_info, book_toc, book_text and search_inside_book.

Run: java -jar myhomelib-mcp-7.1.0.jar --db /path/library.db
Or use --collection <name|id> when the MyHomeLib metadata database is available. MYHOMELIB_DB is also supported.
