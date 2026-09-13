# Upgrade from MyHomeLib Enterprise v6 to v7

## Before upgrading

1. Exit MyHomeLib completely.
2. Back up the application data directory, especially collection SQLite databases and downloaded-book directories.
3. Keep the v6 application available until the first successful v7 startup and catalog check.

## Upgrade

Replace the application/project files with v7 and start normally. Flyway automatically applies new migrations V34–V36 to existing databases; do not edit or re-run old migration files manually.

The upgrade preserves stable book IDs and existing user-owned book data. V34 changes author identity rules without deleting book-author links. V35 moves catalog synchronization state into database-backed source state. V36 adds manifest, external book identity and artifact tables.

## Credentials

Existing encrypted credentials continue to be decrypted with the configured master key. Legacy plaintext credentials are accepted only for migration compatibility and are rewritten as AES-256-GCM ciphertext when the collection repository accesses/saves them. If no environment/system master key is configured, v7 creates a persistent random key in the application config directory. Back up that config together with the database.

## First catalog synchronization

The first v7 online check may populate durable source metadata (remote/applied version, ETag, Last-Modified, SHA-256 and timestamps). The applied version is committed only after catalog import and search-index finalization succeed.

A full snapshot can mark source-owned records absent from the snapshot as deleted. An incremental/extra update never deletes records merely because they are absent from the delta.

## Metabib datasets

v7 can import `metabib.dataset/1` directly from JSONL, JSONL.GZ, JSONL.ZST, or ZIP containing JSONL. Do not convert metabib datasets to INPX first if you need external identities, provenance and artifact metadata.

## Verification

On Windows run:

```text
BUILD-CHECK-FIXES.cmd
```

On macOS/Linux or any environment with Python 3:

```text
python tools/build-check-v7.py
./mvnw clean verify
```

If the build machine cannot access Maven repositories and has no cached Maven distribution/dependencies, the offline checker can still validate migrations/XML/source invariants, but it does not replace Maven compilation/tests.

## Rollback

If startup or migration fails, do not repeatedly modify the affected database. Restore the backed-up v6 data directory and use the v6 application. Flyway migrations are forward migrations; rollback should be done by restoring the backup, not by manually deleting v7 columns/tables.
