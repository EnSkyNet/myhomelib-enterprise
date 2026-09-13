# MyHomeLib Enterprise v7.1 — Architecture Upgrade

Status: release candidate source tree, 2026-08-28.  
Baseline: `myhomelib-enterprise-v7.zip`.  
Behavioral reference: `MyHomeLib-2.7.0_pre2`.  
Dataset/reference implementation: `metabib-main`; GPLv3 source is not copied into this Java codebase.

## Design constraints

v7.1 preserves existing collection databases, stable book IDs, local/downloaded books, favorites, reading state, ratings, history, credentials and the last committed Lucene index. Schema changes are additive Flyway migrations. V1–V36 are treated as immutable release history and are checked against the v7 baseline by SHA-256.

The release keeps the modular-monolith dependency direction (`shared → domain → application`, infrastructure implements application ports, UI renders application state, bootstrap composes the runtime). Long-running operation state is represented in the application layer and is not tied to JavaFX.

## Online collection architecture

Catalog-package download and book-content download remain separate use cases. `RemoteCatalogDownloadPort` handles catalog version/package retrieval; `OnlineBookDownloadPort` handles book bytes. Both share the centralized `OnlineHttpPolicy` for User-Agent, connect/read timeout, redirect behavior, cookies/session, proxy and TLS configuration.

`ConnectionScript` is implemented as a declarative scenario rather than a scripting runtime. `DownloadScenarioParser`, `DownloadMacroResolver` and `ConnectionScriptExecutor` support `GET`, `POST`, `ADD`, `CHECK`, `REDIR` and `PAUSE`. There is no shell, JavaScript, Groovy, `Runtime.exec` or arbitrary Java execution path. Macro replacement is one-pass so replacement data cannot create nested macro execution.

`collection.info` is represented by `CollectionSourceProperties` and `CollectionInfoCodec`. The legacy field order (Name, file, type, Notes, URL, Script) is preserved, including multiline script content. `CollectionPropertiesTrustPolicy` makes source-property application explicit: source properties may be applied when a trusted collection is created, while manual updates preserve local URL/script/credentials and other local configuration.

## Credentials, proxy and TLS

Collection passwords continue to use AES-GCM and are decrypted only for the active operation. `%PASS%` exists only in the in-memory scenario context. Diagnostic/network text uses `SensitiveDataSanitizer`; Authorization headers and common token/password query keys are redacted.

The network profile supports system proxy, direct mode and explicit HTTP proxy with an encrypted proxy password. SOCKS is deliberately not implemented as a direct `java.net.http.HttpClient` mode; users can use the JVM/OS system proxy instead.

TLS defaults to normal JVM certificate validation. No trust-all mode exists. A custom JKS/PKCS12 trust store can be configured explicitly; its password is AES-GCM encrypted in application settings.

## Download correctness and durable queue

Book state becomes local only after download, semantic validation, archive/entry validation when applicable, atomic move and persistence update. A failed forced refresh cannot replace a valid older local copy.

The durable queue stores collection ID, stable book ID, timestamps, status, retry information, destination, physical archive identity and resume metadata. It never stores decrypted credentials. On startup stale `IN_PROGRESS` rows are recovered as resumable `PENDING` rows.

A resumable `.part` is not appended blindly. Resume requires source identity plus ETag/Last-Modified metadata, sends `Range` with `If-Range`, and verifies `Content-Range`. Semantic-invalid partial content is removed; transport-interrupted content may be retained.

Concurrent books that live in one physical archive coordinate on one physical network download and then validate the requested archive entry independently.

An opt-in high-reliability archive mode performs a full ZIP-family scan immediately after download: case-insensitive duplicate entry names are rejected, every entry is fully read, declared uncompressed size and CRC are verified, empty/invalid FB2 entries are rejected and the requested entry must exist. The scan is not performed on every startup or unchanged source.

## INPX compatibility

Only catalog `.inp` members are passed to the book parser; `version.info`, `collection.info`, `structure.info` and unrelated members are excluded from record parsing. `extra.inp` follows upstream semantics: it is included for online collections and skipped for ordinary/offline collection import. Full snapshots may mark source-owned missing books deleted; deltas do not infer deletion from absence.

## Metabib structural model

`MetabibCatalogReader` validates dataset/record schemas, declared versus actual record counts, EOF/trailing records, observation IDs and references, archive descriptors/ranges, artifact occurrences and ignored/dummy ranges. Ambiguous DB-author metadata participates in identity resolution.

V40 adds source/dataset provenance foundations: dataset header metadata, versioned raw record/observations/claims/identities/artifacts, generic source relations, artifact metadata and artifact occurrences. The normalized `books` projection remains the operational model while source facts are retained for diagnostics/re-resolution. Extended fields without a first-class domain field are preserved in versioned structured metadata instead of being silently discarded.

## Manifest and fingerprint compatibility

V39 extends `catalog_manifests` with compatibility keys: manifest schema, importer version, source format, normalization version, fingerprint model/version, processing flags, enabled features and dataset normalization model.

Startup/import uses a fast path: path/size/mtime plus compatibility keys are checked first. A strong SHA-256 is calculated only when metadata changes or compatibility is uncertain. Parser/normalization/fingerprint model changes invalidate old cache rows.

Search metadata uses a separate versioned fingerprint (`book_search_state`). A full catalog snapshot no longer implies reindexing every stable book when searchable metadata and Lucene schema are unchanged.

## Lucene architecture

Full traversal is keyset-based and bounded; author/genre enrichment is batched instead of per-book N+1 queries. Rebuild no longer materializes hundreds of thousands of futures or a complete 700k-book list.

The search implementation is split into `LuceneSearchService`, `LuceneSearchExecutor`, `LuceneIndexWriterFactory`, `LuceneDocumentMapper` and `LuceneIndexMetrics`. Full rebuild keeps the last committed index usable until the new transaction succeeds; cancellation/failure rolls back uncommitted writer state.

`SearchIndexPerformanceReport` captures processed/expected docs, total duration, docs/s, DB-read, document-build, Lucene-write, merge-wait, commit, peak heap, GC delta, index size and segment count.

## Progress and statistics

`OperationProgress` is an immutable application-layer telemetry record. Catalog update reports server check, download, validation, catalog read/import, deletions, search-index update and finalization; real counters include processed/total, byte progress, inserted/updated/deleted/skipped/duplicates/warnings/errors and current FULL/DELTA package. JavaFX renders coalesced snapshots (about every 180 ms) and supports cooperative cancellation.

Statistics no longer convert exceptions into plausible zero values. UI states distinguish loading, real zero and unavailable/error, and statistics queries execute off the JavaFX thread. Collection data is read from the active collection database. Duplicate and missing-cover counters are real aggregations rather than constants. Online download/local removal invalidates the one-row aggregate cache in O(1); the next background statistics read rebuilds it, avoiding large COUNT/GROUP BY work for every individual book mutation.

## v7.1 migrations

Collection DB additions:

- V37 — extended/statistics columns;
- V38 — versioned search fingerprint state;
- V39 — manifest compatibility keys;
- V40 — metabib provenance, relations and artifact occurrences.

Metadata DB additions:

- V4 — persisted `ConnectionScript`;
- V5 — durable online-book download queue.

Offline release checks verify that V1–V36 match the baseline hashes and that a v7 database can receive the v7.1 migrations while retaining representative user-owned state.

## Validation boundary

The local environment has JDK 21 but no cached Maven distribution/dependencies and cannot resolve `repo.maven.apache.org`. Therefore `./mvnw clean verify -Pproduction`, JavaFX runtime smoke tests and real GitHub Actions results are not claimed here. `tools/build-check-v7.py`, the static/architecture/stage guards, SQLite migration checks, XML/FXML parsing and shell/YAML checks are the available local evidence. See `GITHUB-CI-v7.1.md` and `PERFORMANCE-v7.1.md`.
