# Catalog / collection fixes — 2026-08-26

## Scope

Reviewed and corrected collection creation, activation/switching, rename/properties updates, and online INPX updates.

## Fixed

1. **Activation lost collection metadata.** `CollectionWorkspaceController` reconstructed a `Collection` from `CollectionDto`, dropping URL, username, encrypted password, and notes. Activation now switches by ID and `SwitchCollectionUseCase` reloads the authoritative metadata record.
2. **Failed switching could close the current collection.** `CollectionManager` now opens and validates a candidate datasource before replacing the current one. `CollectionLifecycleService` also restores the previous collection if migrations/index initialization fail.
3. **Concurrent switch was silently ignored.** Lifecycle initialization now throws a clear error instead of returning success while another switch is running.
4. **Rename/properties left stale active metadata.** The lifecycle port can now refresh the current collection descriptor without reopening SQLite. Rename and properties updates use it.
5. **Properties could corrupt collection type 3/4.** The properties dialog now uses the full `CollectionType` enum instead of a three-item index-based combobox.
6. **Online collections incorrectly required a local source file.** Remote collection types can now be created from URL only. Local source remains required for explicit `INPX_ARCHIVE`.
7. **Creation wizard ignored source/import/index flags.** When `importOnCreate` is enabled and a source file is selected, the collection is initialized, imported, and optionally indexed. Lifecycle initialization is performed off the JavaFX thread in the wizard flow.
8. **Repository returned plaintext password object after UPDATE.** `SqliteCollectionRepository.save()` now rereads and returns the persisted representation after an update, so the active descriptor receives the encrypted password form.
9. **Online update could target a non-active collection.** `UpdateCollectionFromNetworkUseCase` now requires the requested collection to be active and uses the active persisted descriptor for credentials/root folder.
10. **Cancellation after import could be reported as success.** The network update checks the cancellation flag again before rebuilding the index and returning success.
11. **Remote downloader URL validation.** Only HTTP/HTTPS URLs are accepted; null cancellation/progress callbacks are handled safely.
12. **Active rename UI state.** Workspace/presenter now refresh `ApplicationState` when the active collection is renamed.

## Added regression coverage

- `SwitchCollectionUseCaseTest`
- extended `UpdateCollectionFromNetworkUseCaseStage6Test`
- `tools/catalog-lifecycle-regression-check.py`

## Validation performed

All project offline `tools/*check.py` checks pass, including architecture, online-update Stage 6/7, collection maintenance, reader, OPDS, performance, refactor, navigation/filter/history, and static release validation.

A full Maven `clean verify` could not be executed in the sandbox because Maven itself/dependencies were not available locally and the sandbox could not resolve Maven Central. The repository's offline static release check explicitly passed and reports 38 XML files valid, 25 FXML workspaces with 163 handlers and 0 missing handlers, 33 SQLite migrations valid, and 0 shell-script static issues.

## Follow-up compile fix

- Fixed `SqliteBookQueryRepository.StreamingBookIterator`: the repository API exposes `findPage(BookQuery)`, so streaming now reads `findPage(query).content()` instead of calling the removed `find(BookQuery)` method.
- A local `myhomelib-infrastructure/.../persistence/postgres/PostgresBookRepository.java` is a stale source from an older tree. It is not part of this distribution and the current project has no combined `BookRepository` contract. `cleanup-stale-sources.cmd` removes that leftover file when this package is extracted over an old working directory.
- Safest upgrade path: extract this archive into a new empty directory rather than overlaying it onto an older source tree.

## Compile follow-up v4

Updated infrastructure tests to the current `BookQueryRepository` pagination API:
- `DatabaseTest`: `find(query)` -> `findPage(query).content()`
- `SqliteBookQueryRepositoryTest`: `find(query)` -> `findPage(query).content()`

`BUILD-CHECK-FIXES.cmd` now also fails early if stale `.find(query)` calls remain in infrastructure tests.
