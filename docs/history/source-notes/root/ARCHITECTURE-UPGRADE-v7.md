# MyHomeLib Enterprise v7 — Architecture Upgrade

## Scope and design rules

Version 7 modernizes catalog ingestion and online synchronization while preserving the existing modular Maven architecture, domain/application/infrastructure separation, SQLite + Flyway persistence, Lucene search, stable book identifiers, collection lifecycle, and user-owned reading data. The implementation deliberately does not copy metabib GPLv3 source code; it implements compatible concepts and the published `metabib.dataset/1` data shape independently.

## What changed

### Neutral catalog import

Application code now has a source-neutral streaming model (`CatalogRecord`, `CatalogReader`, `CatalogReadSession`, `CatalogDatasetInfo`) and a persistence port (`CatalogImportPort`). INPX remains a fast compatible path, while metabib is imported natively without converting through INPX.

`MetabibCatalogReader` validates the dataset header/schema and streams `.jsonl`, `.jsonl.gz`, `.jsonl.zst`, and ZIP files containing JSONL. ZIP detection is content-aware so Flibusta INPX update ZIPs are not accidentally claimed by the metabib reader.

The JDBC neutral importer writes in batches and never builds a complete 500k–1M record catalog list in memory. Full snapshots use a temporary SQLite `seen` table to mark only source-owned records absent from the snapshot as deleted. Delta imports modify only records present in the delta plus explicit delete markers.

### Author identity

Author names are no longer globally unique identities. Migration V34 removes the unique `(first_name,last_name)` index and adds `author_identities` keyed by `(source_id, scheme, external_id)`. A non-unique exact lookup index remains for sources that do not provide person identity.

Legacy delimiter-based `first|middle|last` keys were removed. Name-only fallback uses a structured key and exact indexed lookup of first/middle/last names. Source identities are authoritative when available, so same-name people can remain distinct.

### Language and ISBN

`LanguageResolver` normalizes common aliases and malformed variants (`ua`/`ukr` -> `uk`, `rus`/`Russian` -> `ru`, `eng`/`English` -> `en`) and returns `und` for unknown or invalid values. Import and UI code no longer invent Ukrainian as the language of unknown books.

ISBN parsing is safe at persistence boundaries. Blank and invalid values are ignored/diagnosed instead of throwing through a page query. ISBN-10 and ISBN-13 checksums are validated.

### Catalog source profiles and durable state

Flibusta/MyHomeLib endpoints and filenames are centralized in `CatalogSourceProfile`. The profile was checked against MyHomeLib 2.7 semantics:

- baseline: `flibusta_online_fb2.inpx`
- full version: `flibusta_online_fb2.info`
- full package: `flibusta_online_fb2.zip`
- incremental version: `extra_flibusta_online_fb2.info`
- incremental package: `extra_flibusta_online_fb2.zip`

Migration V35 moves synchronization state into `catalog_sources`: profile, applied/remote version, ETag, Last-Modified, SHA-256, schema, timestamps and last error. Application settings are no longer the authority for catalog version state.

The update state machine records `applied_version` only after all required packages have imported and Lucene finalization has succeeded. A failed download, import, or index update leaves the previous applied version intact.

### Full versus delta semantics

A full snapshot may upsert existing records, insert new records and mark source-owned records absent from the snapshot as deleted. An incremental update never treats absence as deletion; it changes only records present in the delta and honors explicit delete markers.

`ImportChangeSet` carries inserted/updated/deleted stable book IDs. This enables incremental search indexing without a full Lucene rebuild.

### Downloader hardening

The HTTP downloader uses persistent `.part` files, Range resume, 206/Content-Range validation, safe fallback when the server ignores Range and returns 200, retry with exponential backoff, cancellation, timeouts, ETag, Last-Modified, Content-Length verification, SHA-256 and atomic final move.

Semantic validation runs before import. HTML/error pages, malformed ZIPs and ZIPs without INP content are rejected. A semantically invalid completed payload is removed before retry; a transport interruption can retain `.part` for resume.

### Lucene atomicity and incremental updates

Delta synchronization performs an atomic logical index section: establish rollback point, delete/update/add affected stable IDs, then publish one final commit. On failure, uncommitted changes are rolled back and the writer is reopened on the previous committed index.

Full rebuild no longer publishes intermediate commits every 50k records. This prevents a failed rebuild from making a partially built catalog index visible. Full rebuild remains appropriate for full snapshots, index corruption, explicit maintenance, and index schema migrations.

### Credentials

Collection passwords are stored with AES-256-GCM. `MYHOMELIB_ENCRYPTION_KEY` / `myhomelib.encryption.key` remain preferred; otherwise a stable local random key is created in the application config directory. Persistence fails closed if a usable key cannot be obtained. Legacy plaintext credentials remain readable for compatibility and are migrated to ciphertext on repository access/save. Re-saving ciphertext does not double-encrypt it.

### Manifest/cache and artifacts

Migration V36 adds:

- `catalog_manifests` for source path/size/mtime/fingerprint/parser-version/record-count cache state;
- `book_identities` for source-scoped external book identities;
- `book_artifacts` for occurrence/artifact metadata and SHA-256/content fingerprints.

The neutral importer can skip parsing an unchanged source by metadata/fingerprint while keeping the schema extensible for richer occurrence modeling later.

## Migrations

v7 adds only new Flyway migrations; old applied migrations are not rewritten:

- `V34__author_external_identity.sql`
- `V35__catalog_source_sync_state.sql`
- `V36__catalog_manifests_and_artifacts.sql`

Offline validation applies V1 through V36 on a new SQLite database and also tests an upgrade from V33 through V36 while preserving book-author links and user-owned fields such as rating, progress, review and local download state.

## Performance changes

- streaming readers and batch persistence (default batch approximately 5000);
- exact indexed author lookup without `COALESCE(column)` in hot predicates;
- prepared/batch JDBC operations;
- full-snapshot absence detection via temporary SQLite table rather than a million-ID Java list;
- no full SQLite index rebuild for delta;
- incremental Lucene add/update/delete for complete delta change sets;
- manifest cache avoids reparsing unchanged local datasets.

`EXPLAIN QUERY PLAN` in the offline release gate verifies use of `idx_authors_name_lookup`.

## Collection and user-data safety

Stable `books.id` remains the central identity; v7 does not switch to unstable AUTOINCREMENT identity. Catalog UPSERTs preserve user-owned data including rating, progress, review and local/download fields. Existing collection lifecycle behavior is retained, and regression checks cover activation/switch rollback, metadata changes, credentials, online-only collections and source import.

## Build and validation

`tools/build-check-v7.py` is the cross-platform offline release gate. It validates migration sequencing and upgrade safety, metadata migrations, XML/FXML, indexed author lookup and critical source invariants. `BUILD-CHECK-FIXES.cmd` runs this pre-check before `mvnw.cmd clean verify`.

The supplied Linux `mvnw` was invalid PowerShell-like content and has been replaced with a normal shell launcher for the existing Maven Wrapper JAR.

### Environment limitation for this release build

The current execution environment contains Java 21 but no system Maven, and the Maven distribution is not cached. The repaired wrapper reaches Maven Wrapper but cannot resolve `repo.maven.apache.org` (`UnknownHostException`). Therefore `mvn clean verify` cannot honestly be reported as completed in this environment. Offline SQLite/XML/source/architecture/regression checks are run instead; a normal connected development machine should run `./mvnw clean verify` (or `mvnw.cmd clean verify`) before publishing binaries/installers.

## Known limitations / next migration path

- The schema now has a foundation for multiple book artifacts/occurrences, but the domain is not fully rewritten around a mandatory `BookArtifact` aggregate. That larger migration is intentionally deferred to avoid destabilizing existing user databases.
- A staging-database atomic switch for every full catalog refresh is not introduced universally. Database import uses transactions and Lucene publication is atomic; a future version can add a complete shadow-database/switch workflow where collection layout permits it.
- Rich metabib claims that have no current MyHomeLib domain equivalent are retained only where mapped into source metadata/identities/artifacts; expanding the domain for every metabib claim is intentionally deferred.
- A connected Maven verification and JavaFX runtime smoke test remain required in a normal build environment because dependencies cannot be downloaded here.
