# Online catalog revision model — Stage 6

Date: 24 August 2026

## Scope

Stage 6 adds the data model required to distinguish a remote catalogue revision from local/user changes. It does **not** add the Stage 7 Updates navigation/UI.

## Stable remote source identity

`UpdateCollectionFromNetworkUseCase` no longer lets the downloaded cache filename identify an INPX source. It passes `CatalogSourceIdentity.remoteCollection(collection.id)` through `ImportContext` and `FastImportService` into `InpxImportPipeline`.

The downloaded INPX bytes are SHA-256 fingerprinted. `catalog_sources` stores the stable source key, deterministic source ID, source fingerprint and monotonic source revision. Re-importing the same bytes keeps the same revision. A changed INPX fingerprint advances the source revision inside the same catalogue transaction.

For manual/local INPX imports the Stage 6 tracker uses a root-relative logical source key, while the old `inpx:<path>` marker is retained for book-ID compatibility.

## Per-book catalog state

Flyway `V31__catalog_update_revision_model.sql` adds:

- `catalog_sources` — logical source revision/fingerprint;
- `catalog_book_state` — current per-book catalog fingerprint, catalog storage metadata, first/last seen revision, and downloaded baseline;
- `followed_authors` — explicit author-follow state, intentionally separate from the Favorites book group;
- `catalog_update_events` — one current row per `(book, update type)` with acknowledgement state.

`catalog_book_state` keeps catalogue-owned file metadata separate from the physical/local storage columns in `books`. This is important after a downloaded book is updated in the remote catalogue: the application must remember both the local bytes currently installed and the new catalogue representation.

## Download baseline

A successful `DownloadBookUseCase` calls `CatalogUpdateTrackingPort.markDownloadedBaseline(bookId)` after the local storage update succeeds. The tracker copies the current `catalog_revision` and `catalog_fingerprint` into `downloaded_revision` / `downloaded_fingerprint` and acknowledges any pending catalog event for that successfully downloaded book.

When local bytes are explicitly removed, `RemoveLocalBookCopyUseCase` clears that baseline. Existing local books encountered during the first Stage 6 sync also receive a best-effort baseline, preventing upgrade-time false positives.

## Update classification

Two Stage 6 event types exist:

### `NEW_BY_FOLLOWED_AUTHOR`

Created only when:

1. the source already had an initial baseline;
2. the incoming book has no prior `catalog_book_state` row;
3. at least one current `book_authors` author is present in `followed_authors`.

Initial adoption of an existing catalogue never floods the event table with “new” books.

### `UPDATED_DOWNLOADED_BOOK`

Created only when:

1. a prior catalog state exists;
2. a downloaded baseline exists;
3. `books.local = 1`;
4. the incoming catalog fingerprint differs from both the prior catalog fingerprint and the downloaded fingerprint.

The event primary key is `(book_id, update_type)`. Repeating the same revision therefore does not duplicate the event. If a later catalogue revision returns to the exact downloaded fingerprint, the stale update event is removed.

## Remote UPSERT safety

`JdbcBatchWriter` still updates catalogue metadata, but when an existing row is local it preserves:

- `file_name`;
- `folder`;
- `archive_entry`;
- `file_size`;
- `collection_root`;
- `local = 1`.

User-owned `rate`, `progress`, `review` and `created_at` remain preserved as before. Bookmarks are in a separate table and are never replaced by INPX import. Missing catalogue rows are marked `deleted`, but their local flag/storage is not destroyed.

## Transaction semantics

Source revision changes, missing-row marking, book UPSERTs, author/genre links, per-book catalog state and update-event detection all execute inside the existing INPX transaction when an active collection datasource is present. Cancellation or an exception rolls the Stage 6 state back together with the catalogue mutation.

## Stage 7 boundary

Stage 6 exposes `CatalogUpdateService` / `CatalogUpdateTrackingPort` queries for pending events and explicit followed-author state. It intentionally does not add Updates navigation, badges, update-tree presentation or user acknowledgement controls; those belong to Stage 7.
