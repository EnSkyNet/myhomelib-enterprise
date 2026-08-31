# Upgrade from MyHomeLib Enterprise v7 to v7.1

## Compatibility rule

v7.1 is an additive upgrade. Do not edit or reorder an already applied V1–V36 migration. The release gate stores SHA-256 hashes for the v7 migration set and fails if any of those files change.

Before upgrading production data, create a normal application backup and retain the current v7 application/index until the first v7.1 start and collection verification succeed.

## Database migration

On opening an existing collection, Flyway applies:

- V37 — v7.1 statistics support;
- V38 — search fingerprint state;
- V39 — manifest compatibility keys;
- V40 — metabib provenance/relations/artifact occurrences.

The metadata database separately receives V4 (`connection_script`) and V5 (persistent download queue).

Offline migration regression starts from the real baseline migration set and verifies representative user-owned values remain after the v7.1 chain, including book data and reading/user state. The migration is designed not to rewrite stable `books.id` values.

## Cache/index behavior after first start

Old catalog manifest rows intentionally receive default compatibility values and are invalidated once under the v7.1 parser/normalization/fingerprint model. This can cause a one-time revalidation/reparse; later unchanged sources use the fast manifest path.

The search fingerprint schema is versioned. If a compatible searchable fingerprint exists, unchanged search documents can be skipped. If Lucene schema/index health requires a rebuild, v7.1 builds within a rollback-safe writer transaction and keeps the old committed index until the new rebuild succeeds.

## Online collections

Existing URL/user/encrypted-password settings are preserved. `ConnectionScript` is now a persisted collection property and survives restart/rename/edit/switch. Importing a trusted `collection.info` can seed source properties for a newly created collection; manual catalog update does not silently replace local URL/script/credentials.

The first v7.1 online book operation creates/updates a durable queue record. Interrupted `IN_PROGRESS` records from a terminated v7.1 process are recovered as `PENDING`; credentials are never stored in the queue.

Existing `.part` files created by older behavior are not blindly resumed unless the v7.1 sidecar can validate source identity and ETag/Last-Modified. Otherwise the downloader safely restarts that payload.

## Network settings

Default TLS remains the JVM trust configuration. A custom JKS/PKCS12 trust store is opt-in. Proxy and trust-store passwords are stored encrypted. There is no TLS trust-all mode.

## Rollback considerations

The database migration is forward-only. If application rollback is required, restore the pre-upgrade v7 backup rather than trying to remove V37–V40/V4–V5 manually. Do not point an older binary at a partially downgraded schema.

Lucene is disposable relative to the catalog database and can be rebuilt, but user data/local books are not disposable; keep the database/collection backup until validation completes.

## Required validation on a connected machine

Before publishing/deploying v7.1:

```bash
./mvnw clean verify -Pproduction
```

and run the GitHub Actions matrix on Ubuntu, Windows and macOS. After creating the final source ZIP, extract it into a new directory and rerun the offline checks from that extracted tree. A source ZIP that passes only in the original working directory is not sufficient release evidence.
