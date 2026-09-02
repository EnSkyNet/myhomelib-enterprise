# MYHOMELIB — Operations

**Version:** 7.1.0  
**Snapshot:** 31 August 2026

## 1. Runtime paths

`AppPaths` is the single path policy.

Normal installed mode:

```text
${user.home}/.myhomelibcorp/
  meta.db
  libraries/
  search-index/
  config/
  downloads/
  cache/
  logs/
  backups/
```

Portable mode is enabled by `myhomelib2.ini` beside the launcher or `-Dmyhomelib.portable=true`; data then lives in `<launch-dir>/data`.

Overrides:

- `-Dmyhomelib.launchDir=<path>` — launcher/resource base;
- `-Dmyhomelib.dataDir=<path>` — application data directory;
- `-Dmyhomelib.langDir=<path>` — external language catalogue directory.

Do not use `cache/catalog-updates` as permanent downloaded-book storage.

## 2. Collection lifecycle

A collection switch must open/migrate/validate the target first and only then replace the active datasource. If candidate initialization fails, the previous collection remains/reopens.

Collection rename/properties changes must refresh active metadata without dropping URL, `ConnectionScript`, credentials, notes or root/path settings.

Creating a new empty collection creates an actually empty catalogue and activates it; it must not silently import unrelated data.

## 3. Online collection configuration

An online collection may contain:

- source URL;
- `ConnectionScript`;
- username and encrypted password;
- local download root;
- notes and collection-specific source settings.

Manual catalogue update preserves local credentials/settings unless the user explicitly replaces them. `collection.info` can seed source properties for a newly created trusted collection.

### ConnectionScript commands

Supported commands are case-insensitive and one command is written per line:

```text
GET <url>
ADD <name> <value>
POST <url>
CHECK
REDIR
PAUSE <milliseconds>
```

`PAUSE` is bounded to 0…60,000 ms. Unknown/malformed commands fail validation, except the narrowly supported legacy bare HTTP(S) preamble used by historical MyHomeLib definitions.

Important macros include `%URL%`, `%USER%`, `%PASS%`, `%RESURL%`, `%ID%`, `%LIBID%`, `%FILE%`, `%FILENAME%`, `%FOLDER%`, `%ARCHIVE%`, `%ARCHIVEENTRY%`, `%EXT%` and `%COLLECTIONROOT%`. Expansion is one-pass; replacement values are not reinterpreted as macros.

Secrets are not persisted into generated scripts, queue records or diagnostics.

## 4. Book download semantics

A successful HTTP status is not enough. The operation is:

```text
request -> streamed .part -> semantic/archive validation -> atomic commit -> storage metadata update
```

Validation rejects empty/error/HTML payloads, malformed archives and invalid FB2 data.

For ZIP-like responses the requested member does not need to have the exact server-side filename. Resolution order is:

1. exact expected member;
2. unambiguous basename/token match;
3. unambiguous `LibID` token match;
4. if the archive contains exactly one valid FB2, that entry;
5. otherwise fail as ambiguous.

Example: catalogue metadata may request `586491.fb2` while Flibusta returns `Romanovich_Zemli-chudovishch_1_Zemli-chudovishch.586491.fb2`; the latter is accepted and persisted as the actual member.

The previous local file is not replaced until the new payload passes validation. `.part` resume requires matching source identity plus ETag/Last-Modified semantics; unsafe stale partials are restarted.

High-reliability archive validation can additionally scan entries to EOF, validate declared sizes/CRC and reject duplicate entry names.

## 5. Network, proxy and TLS

- Normal JVM TLS verification is the default.
- Optional explicit JKS/PKCS12 trust store is supported.
- System/direct/HTTP proxy modes are supported.
- Proxy/trust-store passwords are encrypted.
- Failed secret decryption must not silently erase the previously stored encrypted value.
- There is no trust-all TLS mode.

Online catalogue and book traffic share the same network policy.

## 6. Backup and restore

Before upgrades or destructive maintenance, create an application backup.

Full collection snapshots use SQLite `VACUUM INTO` to include committed WAL content consistently. Versioned user-data backup can additionally capture user state in `user-data.json`.

Portable user-data restore maps books by stable `LibID` first. Full restore stages the replacement file before active handles are closed and then reopens/migrates the collection through the normal Flyway path.

Do not manually downgrade or delete applied Flyway migrations. Roll back by restoring the pre-upgrade backup.

## 7. Upgrade rules

### v6 -> v7

Flyway adds V34–V36. Stable book IDs and user data are preserved. Back up the data directory first.

### v7 -> v7.1

v7.1 is additive. Historical V1–V36 migration content is immutable. v7.1 continues the Flyway chain with later migrations for statistics/search/manifest/metabib and subsequent runtime/schema fixes present in the current repository. The metadata database has its own migration chain.

After the first upgraded start, verify:

- collection opens normally;
- expected book count/user state remains;
- downloaded/local books resolve;
- search index is healthy or rebuilds successfully;
- online collection URL/script/credentials remain intact.

If rollback is required, restore the backup rather than attempting a schema downgrade.

## 8. Search/index recovery

Lucene is rebuildable relative to the SQLite catalogue. User data and local files are not. Keep the catalogue backup until upgrade verification completes.

A failed rebuild must not replace the previously committed healthy index. Startup should reuse a valid index marker/fingerprint instead of rebuilding unnecessarily.

## 9. OPDS operation

Default bind: `127.0.0.1:8088`.

Use **Tools -> OPDS server...** to configure bind/port, optional Basic authentication and autostart. Binding beyond loopback exposes the catalogue to the network and should be treated as an explicit security decision.

## 10. Logs and troubleshooting

Runtime logs are under `<data-dir>/logs`.

For online download failures, inspect the sequence:

```text
bookId / collection
resolved mode
request URL (sanitized)
HTTP status + final URI + content type + length
validated payload bytes
resolved archive entry
atomic storage commit
```

A `200 application/zip` followed by an archive-member mismatch means transport succeeded and the failure is in archive resolution/validation. Current 7.1 code accepts safe server renaming as described above; ambiguous ZIPs still fail intentionally.

Support bundles redact known secret keys and include bounded logs plus current release documentation where available.

## 11. Release/production checklist

Before treating a build as production-ready:

1. run offline/static gates;
2. run `./mvnw clean verify -Pproduction` on a connected machine or populated Maven cache;
3. run the Windows/Linux/macOS CI matrix;
4. create the final source/binary archive;
5. extract it into a clean directory and rerun release checks there;
6. verify checksum and executable permissions for Unix launch scripts;
7. smoke-test a real collection, online download, Reader and backup/restore.

## 12. Coordinated operation lifecycle

Collection-changing and maintenance operations use `LibraryOperationCoordinator`; incompatible operations must not overlap. User-visible long work is registered in Operation Center and should publish authoritative stage/progress rather than synthetic percentages. Online update completion order is SQLite/import → Lucene → statistics refresh → applied source version → completed UI state. Restore uses staged validation and preserves the previous database until the replacement has opened, migrated and passed integrity validation.
