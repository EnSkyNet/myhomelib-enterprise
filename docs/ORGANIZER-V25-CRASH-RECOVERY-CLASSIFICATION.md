# Organizer V25: conservative classification of crashed MOVE/RENAME operations

The authoritative SQLite journal is evidence of an earlier state, **not proof that the filesystem still matches it**. Recovery UI decisions must fail closed if the bytes and physical layout are ambiguous.

## Regression confirmed in V24

`GetLibraryOrganizationStatusUseCase.classify()` counted any `COMMITTED` journal row as recoverable without calling `rollbackIsDeterministic`. For `MOVE`, `RENAME`, and `MOVE_AND_RENAME`, `rollbackIsDeterministic` also considered the presence of either a verified source **or** a verified target sufficient. If both paths existed after a crash, `incomplete()` returned `ROLLBACK_AVAILABLE`, but `UndoLibraryOrganizationUseCase` would reject an occupied original source rather than overwrite it.

## V25 changes

- Every `COMMITTED` item is checked against durable filesystem facts before contributing to an automatic rollback decision. Unverifiable files produce `MANUAL_REVIEW`.
- MOVE/RENAME actions are deterministic only if **exactly one** of the two distinct paths holds verified original bytes: destination alone (normal committed move) or source alone (rollback completed on disk before SQLite journal was updated).
- Both copies present, source and destination the same path, tampered target, or no verified copy: `MANUAL_REVIEW`.
- Conversion/extraction/optimization continue to use their existing action-specific rules; no file is deleted and no automatic recovery is triggered by this read-only classification.

## Tests and trust boundary

Tests construct independent temporary source/destination paths and inspect `incomplete()` for `COMMITTED` and `VALIDATED` crash snapshots, tampered targets, and already-restored sources. They do not simulate physical power loss or NTFS/UNC behavior. An actual `kill -9`/restart test with a persisted SQLite file, separate process, and Windows storage fault injection remains a release gate.

This is a **recovery availability and safety fix**, not a fix for the Windows first-book I/O hang and not a globally atomic filesystem/SQLite transaction.
