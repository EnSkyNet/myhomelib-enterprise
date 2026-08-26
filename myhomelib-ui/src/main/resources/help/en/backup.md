# Backup and transfer user data

MyHomeLib Enterprise creates a consistent SQLite snapshot with `VACUUM INTO`; it does not copy a live WAL database as an ordinary file. A normal backup may contain the database, search index, covers and `user-data.json`.

`user-data.json` is a portable versioned manifest containing ratings, progress and reviews, bookmarks, reading history/statistics, groups/favorites, saved searches, unified filters, global Reader settings and per-book Reader overrides. Book-scoped rows are matched by stable `LibID` first; the internal book ID is only a same-catalogue fallback.

Restore has two modes:

- **Full restore** — staged SQLite replacement, collection reopen and sequential Flyway migrations; compatible with older database-only backups.
- **User data only** — keeps current catalogue metadata/database and applies `user-data.json` by `LibID`. This is the preferred mode after refreshing or re-importing an INPX catalogue.

Stage 22 uses manifest schema version 2 and sequentially migrates the previous v1 format. Destructive collection maintenance creates a separate safety backup. Never replace an active database file manually while the application is running.
