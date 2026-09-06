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

Default bind: `127.0.0.1:8088`. Loopback may use HTTP. A bind beyond loopback is rejected unless TLS is enabled, so LAN exposure is HTTPS-only.

Use **Tools -> OPDS server...** to configure bind/port, optional Basic authentication, autostart and TLS. The same dialog can:

- create or regenerate a managed self-signed PKCS12 certificate;
- import an X.509 PEM certificate/chain plus a matching unencrypted PKCS#8 PEM private key;
- show the SHA-256 certificate fingerprint, subject and validity window;
- warn that self-signed certificates are not automatically trusted on client devices.

Managed certificate material is stored under the application configuration directory. Its generated keystore password is persisted only as authenticated ciphertext using the explicit `mhlenc:v1:` envelope; plaintext is not written to application settings. Authenticated pre-envelope ciphertext is upgraded to the current envelope on persistence/read-migration without changing the secret.

For a manually managed external PKCS12/JKS keystore, the existing runtime password fallback remains available through `-Dmyhomelib.opds.tls.keyStorePassword=...` or `MYHOMELIB_OPDS_TLS_KEYSTORE_PASSWORD`. Imported encrypted PKCS#8, PKCS#1 and SEC1 private-key files are intentionally rejected; convert them to unencrypted PKCS#8 before import.

Default sidecar limits are 64 concurrently executing requests, listen backlog 64, 8 failed authentication attempts per 60 seconds and a 120-second per-client block. They can be tuned under `opds.limits.*`. `/health` remains public on loopback; when OPDS is exposed beyond loopback it requires Basic Auth by default and otherwise returns 403.

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
2. confirm protected branches require the PR CI `Fast gate` status check in GitHub branch protection/rulesets;
3. run `./mvnw clean verify -Pproduction` on a connected machine or populated Maven cache;
4. run the Windows/Linux/macOS CI matrix;
5. create the final source/binary archive;
6. extract it into a clean directory and rerun release checks there;
7. verify checksum and executable permissions for Unix launch scripts;
8. smoke-test a real collection, online download, Reader and backup/restore.

## 12. Coordinated operation lifecycle

Collection-changing and maintenance operations use `LibraryOperationCoordinator`; incompatible operations must not overlap. User-visible long work is registered in Operation Center and should publish authoritative stage/progress rather than synthetic percentages. Online update completion order is SQLite/import → Lucene → statistics refresh → applied source version → completed UI state. Restore uses staged validation and preserves the previous database until the replacement has opened, migrated and passed integrity validation.

## 13. Executor saturation and lifecycle

Managed backend executor thread prefixes are `app-task-`, `app-io-`, `app-import-`, and `app-search-`; UI background threads use `ui-bg-`. Queues are bounded. When a queue is full the task is rejected instead of running on the submitting thread; logs include executor role, queue depth, active threads and pool size. Repeated rejections indicate sustained overload and should be investigated rather than hidden by increasing queues without measurement.

`FolderSyncService` runs asynchronous scans through the managed I/O executor. Cancelling its returned future also raises the service cancellation flag so long scans stop cooperatively. `MemoryMonitor` uses a daemon scheduler and can be stopped/restarted safely. During application shutdown `AsyncConfig` stops the shared backend pools before the collection/database context is closed.

## 14. JavaFX workspace lifecycle diagnostics

Reloadable workspaces now use per-load controller instances and explicit disposal of long-lived listeners. Repeated navigation between Dashboard/Search/Groups/Book views should not multiply callbacks or retain stale workspace state.

Book-details and group-list database reads run on the bounded UI background executor. During normal operation the UI may briefly show `Завантаження…`; empty/not-found and load-error states are explicit. Rapid A → B navigation or a collection switch must not allow a late A result to overwrite B. If UI background saturation occurs, investigate the bounded executor/queue rather than moving work back onto the FX thread.

## Search recovery after metadata edit

SQLite remains authoritative for Classic metadata edits. If Lucene selective synchronization and fallback rebuild both fail after a committed edit, the edit remains committed and the collection search index stays marked dirty. The normal search-index recovery/rebuild path must run before the index is treated as reusable.\n\n## 15. Support bundle privacy and external-reader cache\n\nBefore exporting a support bundle, the settings dialog shows the planned contents and lets the user include/exclude sanitized logs, the thread dump and release/architecture documents. Mandatory environment/settings entries remain sanitized. Known credentials, URLs, e-mail addresses, book/author fields and user-home/application paths are redacted line-by-line; exact `dataDir` and `launchDir` are not written to `environment.txt`. Oversized logs remain excluded by the existing per-file/total bundle limits. The version shown in diagnostics comes from packaged runtime/build metadata rather than a hard-coded release string.\n\nTemporary books opened by an external reader live under the managed external-reader cache, not in unbounded `deleteOnExit` files. The cache enforces age/size limits and deletes stale crash leftovers on the next application startup. Files associated with a tracked detached process remain available until that process exits. `Desktop.open` does not provide a portable process handle, so those files intentionally survive the current MyHomeLib session and are reclaimed on the next startup. If the cache repeatedly reaches its size limit, close stale external readers first; do not disable the bound.\n

## 16. Localization diagnostics

Built-in Ukrainian, English and Bulgarian catalogues are synchronized between root `Lang/` files and packaged UI resources. Critical Search/Reader/Import/OPDS/Backup programmatic text resolves stable keys rather than Ukrainian source text. If a critical label renders as a raw `ui.*` key, treat it as a missing/invalid catalogue entry and run `python3 tools/check-critical-ui-localization.py` plus `python3 tools/validate-language-catalogs.py`. External compatible language catalogues remain discoverable, but built-in release acceptance is defined for UK/EN/BG.



## 17. Startup recovery and degraded mode

Desktop startup executes recovery, collection migration/activation, search-index policy, backup-staging cleanup and optional OPDS autostart in that order. Recovery and migration are mandatory. If either fails, startup stops and the error identifies the failed startup task.

Search rebuild, stale backup-staging cleanup and OPDS autostart are best-effort. Failure in one of these phases allows the application to continue in degraded mode and is written to the logs/startup report. A dirty or non-reusable search index is rebuilt asynchronously; do not treat a failed rebuild as a reason to rewrite or discard authoritative SQLite data.

Interrupted backup staging is limited to `.snapshot.tmp` cleanup during startup; a full automatic backup is not performed on every launch.
