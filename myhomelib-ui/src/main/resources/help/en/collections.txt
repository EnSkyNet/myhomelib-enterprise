# Collections

MyHomeLib supports multiple independent collections. A collection can be created empty, created from INPX, attached to a modern SQLite database, or attached from an original `.hlc2`. For a legacy Delphi `.hlc2`, the source file is not modified: the catalogue is migrated to a modern sidecar SQLite database while preserving `LibID`.

Collection properties include the root folder, type, book/archive addresses, INPX update address and optional login/password. Updates can be performed manually from INPX or over the network. Folder synchronization adds new files and refreshes the local state of existing ones.

Maintenance tools include integrity check, `VACUUM`, search-index rebuild, backup and restore. Books can be copied between collections.
