# Existing .hlc2 collections

A modern MyHomeLib SQLite collection can be attached directly. For an original Delphi `.hlc2`, MyHomeLib opens the source read-only, creates a modern sidecar SQLite database and migrates the catalog, authors, genres, series, `LibID`, file references, library/user ratings, progress and reviews when those fields are present in the source database.

The original `.hlc2` is not rewritten. Keep a backup of it before the first migration.
