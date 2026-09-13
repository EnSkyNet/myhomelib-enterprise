# Startup transaction fix — v6.1

## Symptom

On a clean profile, startup failed while creating the first/default collection:

`CannotCreateTransactionException: Could not open JDBC Connection for transaction`

caused by:

`java.sql.SQLException: Поточна колекція не вибрана`

The failure happened in `SqliteCollectionRepository.save()` before any collection database had been selected.

## Root cause

The application has two distinct database scopes:

- metadata DB (`meta.db`) — collection definitions and metadata;
- active collection DB — books/authors/groups/etc.

`CollectionTransactionConfig` explicitly defined `collectionTransactionManager`. Because a `PlatformTransactionManager` bean already existed, Spring Boot did not auto-create a separate default transaction manager for the primary metadata DataSource. `SqliteCollectionRepository.save()` used an unqualified `@Transactional`, so startup attempted to begin that transaction through the collection-scoped DataSource, which intentionally refuses connections when no collection is active.

## Fix

- Added explicit primary `metadataTransactionManager` bound to `metadataDataSource`.
- Added `metadataTransactionTemplate` for future metadata operations.
- Bound `SqliteCollectionRepository.save()` explicitly to `metadataTransactionManager`.
- Bound all current collection-scoped `@Transactional` methods explicitly to `collectionTransactionManager` (`SqliteAuthorRepository`, `AddToGroupBatchUseCase`; existing book batch use cases were already explicit).
- Added `tools/startup-transaction-check.py`.
- Extended `BUILD-CHECK-FIXES.cmd` to verify transaction-manager separation before Maven verification.

This allows the very first collection record to be created in `meta.db` before an active collection DB exists, while preserving correct transaction routing after a collection is selected.
