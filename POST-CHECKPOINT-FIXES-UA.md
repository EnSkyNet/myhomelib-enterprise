# POST-CHECKPOINT FIXES / FINDINGS

Це перелік змін, які були знайдені та в значній частині вже тестувались **після** останнього збереженого повного ZIP.
Через reset середовища їх треба відновити у коді поверх checkpoint і повторно прогнати тести.

## P0 — data integrity / security / lifecycle

### 1. Native SecretStore був однослотовим
Проблема:
- Windows DPAPI, Linux Secret Service та macOS Keychain ігнорували logical `key`.
- різні secrets (`username`, `password`, `sync-key`, AI secrets, master key) могли перезаписувати один одного.

Потрібно відновити:
- окремий native slot на кожен logical key;
- legacy master-key slot залишити сумісним;
- regression `PlatformSecretStoreKeyIsolationTest`.

Раніше targeted result: PASS.

### 2. WebDAV credential save/delete — compensating rollback
Проблема:
- послідовний save кількох secrets міг залишити неповний набір credentials при failure посередині.

Потрібно:
- rollback до попереднього консистентного набору;
- аналогічно для delete.

Раніше targeted result разом із SecretStore: PASS.

### 3. WebDAV PROPFIND href path boundary
Проблема:
- encoded traversal типу `%2e%2e`;
- nested href поза direct-child contract.

Потрібно:
- same-origin;
- decoded dot-segment rejection;
- direct-child path enforcement;
- не відправляти GET/credentials до небезпечного href.

### 4. WebDAV duplicate cursor conflict
Проблема:
- однакові `device + sequence + changeSetId`, але різний payload не вважались конфліктом.

Потрібно:
- порівнювати весь `ChangeSet`, а не тільки ID.
- аналогічний fix у LocalFolderSyncAdapter.

Раніше WebDAV suite: PASS.
Local folder sync: PASS.

### 5. Network catalog download size limits
Проблема:
- `HttpRemoteCatalogDownloadAdapter` не мав еквівалентного hard limit, який є для book download.

Потрібно:
- reject oversized `Content-Length`;
- limit фактично streamed bytes;
- free disk check;
- overflow-safe counters;
- oversized failure non-retryable;
- safe cleanup downloaded package.

Раніше targeted result: 12/12 PASS.

### 6. Download cleanup path traversal / symlink
Проблема:
- cleanup перевіряв `startsWith(cacheDir)` до normalization.

Потрібно:
- absolute normalized path;
- must stay under cache root;
- NOFOLLOW regular-file validation;
- refuse symlink.

### 7. Content-index checkpoint version
Проблема:
- unknown future version трактувався як empty/missing checkpoint.

Потрібно:
- fail-closed `IllegalStateException`;
- не перезаписувати невідомий checkpoint.

Раніше test: PASS.

### 8. Recovery marker parent symlink
Проблема:
- `CatalogUpdateRecoveryFiles` / `RestoreRecoveryFiles` захищали marker, але не всі parent components.

Потрібно:
- no symlink/reparse у всьому parent path;
- safe cleanup.

Раніше recovery tests: PASS.

### 9. Collection DB path boundary
Проблема:
- нову SQLite БД можна було відкрити через symlink/junction у parent path.

Потрібно:
- no-link parent validation перед Hikari/open;
- existing DB must be safe regular file.

### 10. Collection lifecycle rollback failure
Проблема:
- failure відкриття нової collection + failure rollback попередньої міг залишати невизначений runtime state.

Потрібно:
- fail-closed;
- rollback failure suppressed/attached;
- закрити current datasource/index якщо state не можна відновити.

### 11. Delete collection registry consistency
Проблема:
- registry record не можна видаляти, якщо фізичну SQLite DB не вдалося видалити.

Потрібно:
- physical delete success before registry commit/remove.

### 12. Lucene DIRTY marker
Проблема:
- `markCurrentIndexDirty()` був best-effort перед catalog mutation.

Потрібно:
- DIRTY write strict/fail-closed;
- CLEAN після successful sync може залишатися best-effort.

Раніше regression: 9/9 PASS.

## P0 — active collection races

Було знайдено системний клас race:
background task бере BookId/DTO у collection A, користувач switch -> B, task пізніше пише у repositories, які використовують current dynamic SQLite datasource.

### Відновити LibraryOperationCoordinator patchset

Нові lifecycle operation types:
- DOWNLOAD
- EDIT
- ANNOTATION
- CONVERSION
- COPY
- EXPORT
- READER_STATE

Механіка:
- `acquireDetachedShared(...)` для explicitly shareable operations;
- DOWNLOAD parallel with DOWNLOAD;
- EDIT parallel with EDIT;
- ANNOTATION parallel with ANNOTATION;
- CONVERSION parallel where allowed;
- READER_STATE parallel;
- EXPORT / CONVERSION compatibility where export legitimately invokes conversion;
- SWITCH/RESTORE/DELETE/UPDATE та інші destructive lifecycle operations повинні конфліктувати.

### UiBackgroundExecutor
Потрібно відновити:
`submitCollectionScoped(operationType, ...)`

Критично:
lease береться **до постановки worker у executor queue**, а не всередині worker.
Це закриває queue-gap:
click in A -> task queued -> switch to B -> task starts -> writes B.

### Download path
Відновити:
- `BookDownloadCoordinator` outer DOWNLOAD lease;
- `DownloadBookUseCase` application boundary lease;
- parallel downloads share root DOWNLOAD;
- switch blocked until DB/storage mutation finished;
- export preflight also under download-stable collection scope.

Раніше tests:
- Download lifecycle end-to-end PASS;
- coordinator PASS.

### Remove local copy
Відновити:
- exclusive lifecycle guard;
- capture expected collection id;
- before mutation re-check active collection;
- reload latest `BookDto` before delete mutation.

### Annotations
Відновити:
- application-boundary ANNOTATION lease;
- UI collection-scoped submit before queue.

Раніше annotation regression: PASS.

### Batch book state
Відновити EDIT barrier для:
- rate;
- progress;
- mark read;
- add to group;
- deleteBooks / BookSaver mutation.

Раніше batch/edit suites: PASS.

### Custom fields
Відновити:
- `CustomFieldService` EDIT lease;
- UI pre-queue collection scoped submit.

### Duplicate subsystem
Відновити:
- ExactDuplicateScanner long scan lifecycle barrier;
- merge/undo EDIT barrier;
- collection-id guard before showing/applying result;
- terminal status визначати через actual `result.cancelled()`, не mutable cancel flag.

### Catalog follow/update
Відновити:
- `CatalogUpdateService` EDIT boundary;
- Updates workspace;
- followed-author actions;
- AuthorWorkspace follow toggle;
- pre-queue collection scoped submit.

### Preferred artifact
Відновити:
- `SelectPreferredBookArtifactUseCase` EDIT lease;
- BookDetails UI pre-queue lease.

### Cross-collection copy
Відновити:
- operation type COPY;
- exclusive lease;
- capture expected source collection id before queuing;
- reject if source collection changed before worker starts.

Раніше tests: PASS.

### Conversion
Відновити:
- CONVERSION lease at UI pre-queue boundary;
- nested application boundary in `ConvertBookUseCase`;
- switch blocked through final artifact DB publication.

### Export
Відновити:
- EXPORT operation type;
- capture `collectionId` in ExportRequest / InpxExportRequest;
- pre-queue EXPORT lease;
- application check active collection identity before DB reads;
- EXPORT <-> CONVERSION compatibility.

Раніше export identity regression PASS.

### Reader state
Відновити:
- READER_STATE operation type;
- NewReaderPersistenceService write barriers;
- ReaderPositionAutosaver / AudioPositionAutosaver snapshots store `collectionId + bookId`;
- bookmark save/delete collection identity;
- stale reader from A must never write progress/bookmark to active B.

### ReadingSessionService
Відновити:
- session captures originating collection id at start;
- finish verifies same current collection under READER_STATE lease;
- otherwise drop stats rather than cross-write.

Раніше tests PASS.

## P1 — TTS / process lifecycle

### TtsPlaybackService
Проблема:
- кілька playback могли одночасно володіти одним native provider/process.

Потрібно:
- single active playback;
- starting new playback stops previous;
- clear active on terminal;
- safe executor rejection cleanup.

Раніше TTS tests: 4/4 PASS.

### Process tree timeout
Regression:
- timeout must terminate descendants, not only parent process.
- `ProcessExecutionSupportTest` descendant process tree test.

Раніше PASS.

## P1 — plugin safety

### Plugin JAR TOCTOU
Проблема:
- JAR hash перевірявся під час scan/install, але не прямо перед URLClassLoader.

Потрібно:
- recompute SHA-256 immediately before loading executable code;
- if mismatch, invalidate approval / refuse load.

Раніше `DesktopPluginRuntimeBridgeTest`: 5/5 PASS.

## P1 — settings / backup / privacy

Відновити:
- corrupt application settings must fail-closed; save не має перезаписувати corrupted source default-ами;
- corrupt reader preferences likewise;
- per-book reader preference migration refuse symlink;
- backup legacy `user-data.json` refuse symlink;
- SQLite snapshot/checkpoint safe regular file + NOFOLLOW;
- startup cleanup не заходить у symlinked backup subdirs;
- SupportBundle redacts standalone Bearer/Basic credentials while preserving `<REDACTED>` marker format.

## Regression evidence before environment reset

Останні широкі результати, отримані до reset:

- Application reactor: **376 tests, 0 failures, 0 errors, 1 skipped, BUILD SUCCESS**
- Reader: **92 tests, 0 failures, 0 errors, 1 skipped**
- UI under Xvfb: **158 tests, 0 failures, 0 errors, BUILD SUCCESS**
- Export identity/lifecycle targeted: PASS
- SecretStore/WebDAV targeted: PASS
- OPDS managed TLS integration: PASS
- static_release_check: PASS
- supply-chain-policy-check: PASS

Ці результати треба повторити після відновлення patchset у новому дереві.
