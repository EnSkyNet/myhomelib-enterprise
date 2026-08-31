# MYHOMELIB — History: Fixes and Regressions

This file summarizes important historical repair passes. It is not a substitute for the current contracts in the root documentation. Original notes are retained under `docs/archive/source-notes/root/`.

## Reader repair line

Early Reader fixes corrected the blank-Canvas wiring defect, page-dimension propagation, long-paragraph continuation, reverse navigation and rich inline rendering. Later work added/verified presets, colors, two-page behavior, gestures, autoscroll, exact EPUB navigation anchors, ZIP handling, FB2 whitespace around inline tags and reliable position persistence.

The AlReaderX comparison was used as a gap audit, not as a promise of complete platform parity. Desktop-relevant controls were implemented where appropriate; Android-only/device-specific features remained outside the desktop contract.

Historical sources: `READER_FIXES.md`, `READER-ALREADERX-GAP-v7.1.md`.

## Collection/catalogue lifecycle repairs

Collection fixes addressed metadata loss on activation, unsafe switch order, concurrent-switch ambiguity and stale active metadata after rename/properties edits. Startup transaction fixes separated metadata-database operations from an as-yet-unselected collection database.

Historical sources: `CATALOG-FIXES-2026-08-26.md`, `STARTUP-TRANSACTION-FIX-v6.1.md`.

## INPX and large-import repairs

Large Flibusta/INPX imports exposed indexed author lookup and batching problems. Fixes restored index-friendly predicates, bounded import behavior, safer handling of author text edge cases and measured batch/index-maintenance choices. Performance choices were subsequently re-measured rather than permanently freezing the first tuning value.

Historical source: `INPX-IMPORT-PERFORMANCE-FIXES-2026-08-26.md`.

## Flibusta catalogue update repairs

The online update path originally treated an INPX server root as a direct archive URL, so HTML could be saved as `.inpx` and an invalid import could appear as zero books. The corrected path distinguishes server/baseline/delta semantics and validates remote archives before DB/index mutation.

Historical source: `FLIBUSTA-ONLINE-UPDATE-FIXES-2026-08-26.md`.

## Runtime fixes 9–11

Observed Windows runtime issues led to three targeted repair passes:

- legacy bare HTTP(S) `ConnectionScript` preamble compatibility;
- permanent download-root use instead of transient catalogue-update cache paths;
- avoiding startup Lucene invalidation/full-catalog derived rewrites;
- removal of catalogue-wide startup root normalization and unnecessary series synchronization;
- lazy normalization of old online-book storage metadata;
- restoration of upstream per-book archive layout;
- recovery of effective collection URL from a legacy script preamble;
- visible failure when online source configuration is missing;
- SQLite `busy_timeout` for pooled connections.

Historical sources: `RUNTIME-FIX-9-2026-08-30.md`, `RUNTIME-FIX-10-2026-08-30.md`, `RUNTIME-FIX-11-2026-08-30.md`.

## RC audit fixes after runtime testing

Later RC work corrected issues that stage/static checks alone did not catch reliably:

- unified download/open/Reader routing through `BookDownloadCoordinator` and resource resolution;
- refreshed full DB book metadata before download to avoid lightweight DTO loss (`libId`, paths, online fields);
- removed startup statistics full-scan behavior;
- removed duplicate UI/download lookups and a future-completion race around storage metadata;
- fixed invalid `navigateToAuthor(null)` navigation;
- clarified current-row vs batch checkbox selection and added batch remove/delete flows;
- protected collection/proxy/TLS encrypted secrets from accidental clearing when decryption fails;
- made OPDS/details use physical file availability rather than only `Book.local`;
- kept failed Reader position saves dirty for retry;
- hardened shared-archive deletion and actual archive-entry checks;
- removed unnecessary repeated ZIP validation in one download operation.

## Flibusta server-renamed ZIP member regression

A stricter RC validator temporarily required exact `archiveEntry` equality. Real Flibusta downloads returned valid ZIPs with a server-generated filename such as:

```text
requested: 586491.fb2
actual: Romanovich_Zemli-chudovishch_1_Zemli-chudovishch.586491.fb2
```

HTTP download succeeded, but validation rejected the archive. The correction restored compatibility without reverting to unsafe guessing: exact match first, then unambiguous basename/LibID token resolution, then a single-FB2 fallback. The actual resolved member is propagated through the download result and persisted so Reader/open/cover/resource lookup use the real ZIP structure. The matching algorithm is centralized in `ArchiveEntryNameSupport` to prevent validator/resolver drift.

This behavior is now part of the active contract in `MYHOMELIB-FEATURES.md` and `MYHOMELIB-OPERATIONS.md`.
