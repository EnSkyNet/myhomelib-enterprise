# MYHOMELIB — Features

**Version:** 7.1.0  
**Snapshot:** 31 August 2026

This document describes the current supported product behavior. Historical stage names are intentionally omitted.

## Collections and catalogue data

- Multiple SQLite collections with create, activate/switch, rename and properties editing.
- Local and online collection types.
- Candidate collection is opened/validated before replacing the current active datasource.
- Collection refresh preserves stable book identity, user-owned state and valid local/downloaded storage metadata.
- Collection cleaner/integrity operations use safe analysis/repair paths rather than destructive blind rewrites.

## Import and synchronization

- FB2/FBD, EPUB and TXT import.
- ZIP/FB2ZIP/CBZ/JAR archives.
- 7z through Apache Commons Compress and RAR through junrar.
- INPX with multiple `.inp` parts plus `structure.info` and `archives.info` compatibility.
- Source-neutral catalogue import foundation, including metabib dataset support.
- Bounded/streaming import paths suitable for large catalogues.
- Idempotent online catalogue refresh with source/book fingerprints, revision state and update classification.
- Full snapshot vs delta semantics are explicit; invalid remote payloads fail before catalogue/index mutation.

Nested archives are not recursively expanded by default.

## Online libraries and book download

- MyHomeLib-compatible declarative `ConnectionScript`: `GET`, `POST`, `ADD`, `CHECK`, `REDIR`, `PAUSE`.
- Deterministic one-pass macro expansion including collection credentials and book/LibID/path fields.
- Legacy bare HTTP(S) URL preamble compatibility where used by historical MyHomeLib collection definitions.
- Shared cookie/session, redirect, timeout, proxy and TLS policy.
- Encrypted collection/proxy/custom-trust-store secrets; no trust-all TLS mode.
- Persistent credential-free download queue and validator-bound `.part` resume.
- Semantic payload validation, archive safety validation and atomic final replace.
- Force refresh keeps the previous valid local file when replacement validation fails.
- Server-renamed ZIP members are supported safely: exact archive member first, then unambiguous basename/LibID matching, then a single-FB2 fallback. Ambiguous multi-FB2 archives are rejected rather than guessed.
- The actual resolved archive member is persisted and reused by open/Reader/cover/export logic.
- Existing older downloads whose database member name differs from a uniquely identifiable ZIP member can still be resolved without re-downloading.

## Search and navigation

- Lucene full-text search with application-level contracts and Infrastructure implementation.
- Incremental/selective index updates and rollback-safe rebuild behavior.
- Navigation by Authors, Series, Genres, Years, Languages, Archives, Keywords, Groups, Reviews, Already Read, History and All Books.
- Recent books and Back/Forward workspace navigation.
- Bounded database-side facets and paginated book lists.
- Unified filter state, quick filters, saved searches and table profiles.

## Book details and metadata

- Rich book details/annotation editing with persistence.
- Series, genres, language, year, ISBN/publisher and related metadata support.
- Cover/resource lookup is archive-aware and checks physical availability rather than trusting a stale `local` flag.
- Batch download, remove-local and delete-record actions are separated from the current row selection and show explicit batch-selection state.

## Reader

- JavaFX Canvas reader; no WebView dependency for the main reading pipeline.
- FB2/FBD, FB2-in-ZIP, EPUB and TXT.
- Streaming/incremental parsing and bounded page/resource caches.
- Rich inline text, paragraph layout, justify, hyphenation and multi-page navigation.
- TOC, search, bookmarks, text selection/copy and reading-position persistence.
- Global defaults plus per-book settings.
- Presets, custom fonts/colors, day/night themes, one/two-page mode, automatic landscape behavior, configurable tap zones, gestures, swipe navigation, pinch text resizing and autoscroll.
- Explicit navigation by page/percentage/chapter/start/end where supported.
- Position autosave with retry on persistence failure and final flush on normal close.
- Ukrainian/English/Bulgarian/Russian hyphenation dictionaries with conservative fallback.

Desktop Reader behavior is intentionally not a claim of complete Android/iOS or AlReaderX feature parity.

## User data and backup

- Ratings, progress, reviews, bookmarks, reading history/statistics, groups/favorites, saved searches, filters and Reader preferences.
- Versioned backup/restore.
- WAL-safe SQLite snapshot through `VACUUM INTO`.
- Portable `user-data.json` transfer keyed by stable `LibID` first.
- Legacy database-only backups remain on the normal Flyway upgrade path.

## Export, device and actions

- Export/device profiles.
- External reader/open-file actions.
- User scripts/book actions with controlled integration.
- HTML/list/export paths and copy-between-collections flows present in the application feature set.

## OPDS and MCP

- Separate OPDS sidecar integrated into the desktop lifecycle.
- Default OPDS bind `127.0.0.1:8088`, configurable from the UI.
- Optional Basic authentication and autostart.
- Authors/series/genres/search/book metadata and streamed local-book downloads.
- `/health` lifecycle probe.
- Separate read-only MCP runtime for supported library access scenarios.

## Localization and context help

- File-based `Lang/<code>.json` localization.
- Bundled Ukrainian, English and Bulgarian UI catalogues.
- Compatible external language files are discovered without recompilation.
- Stable genre codes are independent of translated labels.
- Context-sensitive F1 help through a central topic registry.

## Deliberate limits

- SQLite is the production database target for 7.1.
- Nested archives are not recursively unpacked by default.
- Ambiguous multi-book ZIP member resolution is rejected rather than guessed.
- Runtime TLS verification is never disabled globally.
- Android/iOS are future platform targets; this repository is the desktop JavaFX source product.

## Stabilization features completed on 2026-09-02

- Operation Center for long-running import/update/maintenance work.
- Followed-author overview with unread update acknowledgement.
- Saved export state and bounded large-library search/author navigation.
- Explicit local-file availability (`available`, `missing`, `remote-only`) without conflating it with online DEL/tombstones.
- Reader layout-based page index and reflow-safe semantic position.
- Explicit stale statistics and guarded Lucene rebuild/search lifecycle.

## UI theme and responsive toolbar hardening — 2026-09-05

- Whole-application Light / Dark / AMOLED presets with immediate persisted switching from the main toolbar; Reader theme remains independent.
- Reader theme preset cycling cannot be visually masked by stale explicit foreground/background CSS overrides.
- Main action toolbar wraps into two rows at the supported 800 px minimum desktop width instead of clipping actions outside the client area.
- `Book -> Open in Reader` and `Book -> Open in external reader` follow the canonical selected-book state across classic, Search and Author workspaces.
