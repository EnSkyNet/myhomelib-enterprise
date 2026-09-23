# Backup and transfer user data

MyHomeLib creates a consistent SQLite snapshot with the SQLite Online Backup API, so a live WAL database is not copied as an ordinary file and no full `VACUUM INTO` rewrite is required. A normal backup contains the database snapshot and, when user-data export is enabled, the portable `user-data.json` file. Derived search indexes and caches are rebuilt separately when needed.

`user-data.json` is a portable versioned manifest containing ratings, progress and reviews, bookmarks, highlights/notes with text anchors, reading history/statistics, groups/favorites, saved searches, unified filters, global Reader settings and per-book Reader overrides. Book-scoped records are matched by stable `LibID` first; the internal book ID is only a fallback within the same collection.

Restore has two modes:

- **Full restore** — staged SQLite replacement, collection reopen and sequential Flyway migrations; compatible with legacy database-only backups.
- **User data only** — keeps the current catalogue database and book metadata, then applies `user-data.json` by `LibID`. This is the recommended mode after an update or INPX re-import.

The portable data format has its own schema version and sequential migration support for older supported manifests. Destructive collection maintenance creates a separate safety backup. Never replace active database files manually while the application is running.
