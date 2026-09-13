# Iteration 30 — local bulk metadata + shared undo checkpoint

**Date:** 2026-09-08  
**Scope:** MHL-111 local 10k+ batch metadata operations and MHL-112 shared durable operation history / undo.

## Implemented

- Local bulk editor works on books selected by checkboxes, with a hard safety limit of 100,000 unique books.
- Preview is bounded to the first 25 selected books; execution processes IDs in chunks of 250 inside the collection transaction.
- Supported bibliographic fields: title, series, publisher, tags/keywords, annotation, language, genres and year.
- Supported actions: set, clear; text fields additionally support regex replace, trim and capitalize.
- Rules are prepared/validated before execution; regex patterns are compiled once by the engine plan.
- Cooperative cancellation and collection-generation guards are preserved in the JavaFX workflow.
- Bulk history snapshots are written in the same transaction as book updates. Failure/cancel before commit cannot leave a completed reversible operation detached from the mutation.
- SQLite migration V53 adds `operation_history` and `bulk_metadata_changes` and backfills existing merge journal entries.
- Shared undo spans local bulk metadata and logical-book merge. Undo is strict LIFO across reversible operations.
- UI confirmation is bound to the reviewed `operationId`; if a newer reversible operation appears before execution, undo is refused.
- Bulk undo refuses to overwrite a book whose editable metadata no longer equals the recorded post-operation snapshot.
- Undo restores only the bibliographic fields in the recorded snapshot; unrelated local book state and artifacts are preserved by rebuilding from the current book.
- After successful commit/undo the search index is synchronized through the existing `SearchIndexSynchronizer` contract.
- History retention is configurable through `myhomelib.bulk-edit.history-retention` and is clamped to 1..200 entries (default 20).

## UI

`MainView.fxml` exposes:

- local batch metadata editing;
- undo of the latest reversible library operation.

The workflow is implemented in `BulkMetadataEditUiService`, routed through `MainBookCommandCoordinator`, and localized for uk/en/bg resource sets.

## Verification performed in this environment

- OpenJDK: 21.0.11.
- Maven wrapper: 3.9.6.
- Baseline static scripts: **76 / 76 PASS**.
- Java 21 parser probe: **1040 / 1040 compilation units PASS**.
- FXML/resource contracts: **206 / 206 handlers resolved**; localization/resource probe PASS.
- SQLite V53 migration smoke: **PASS**, including merge backfill and cascade behavior.
- Pure-JDK bulk transformation smoke: **10,000 records PASS** (`82 ms` in this one measured run; not a performance guarantee).
- Previously JDK-21-blocked stage scripts now execute; the OPDS verification file list was corrected to include the current security/TLS helper classes.

## Remaining gates

Full Maven reactor is **not marked PASS**. Offline POM resolution currently stops before compilation because the local repository does not contain:

- `org.springframework.boot:spring-boot-dependencies:pom:3.5.0`;
- `org.openjfx:javafx:pom:21.0.2`.

The environment cannot fetch Maven Central, so JUnit/test-compile for the complete reactor remains externally blocked by dependency availability, not by the JDK version.

Windows DPI/portable/installer smoke and live GitHub CI/security acceptance also remain external gates.

## Status interpretation

MHL-111 and MHL-112 remain **In progress** in the audit workbook until full reactor and UI/runtime acceptance are available. This checkpoint demonstrates implemented source and targeted/static verification; it does not claim release acceptance.
