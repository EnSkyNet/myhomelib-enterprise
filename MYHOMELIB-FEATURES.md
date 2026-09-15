# MYHOMELIB — Features

**Version:** 8.0.0  
**Snapshot:** 12 September 2026

This document describes the current supported product behavior. Historical stage names are intentionally omitted.

## Collections and catalogue data

- Multiple SQLite collections with create, activate/switch, rename and properties editing.
- Local and online collection types.
- Candidate collection is opened/validated before replacing the current active datasource.
- Collection refresh preserves stable book identity, user-owned state and valid local/downloaded storage metadata.
- Collection cleaner/integrity operations use safe analysis/repair paths rather than destructive blind rewrites.

## Import and synchronization

- FB2/FBD, EPUB and TXT import.
- ZIP/FB2ZIP/JAR multi-book archives.
- Native CBZ/CBR comic documents; 7z through Apache Commons Compress and RAR through junrar remain general archive containers.
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
- Smart Collections with typed AND/OR rules, bounded result sets and deterministic Lucene/DocValues sorting.
- Typed custom fields (text/number/bool/date/enum) with SQLite persistence, Lucene filtering and versioned backup/restore.

## Book details and metadata

- Rich book details/annotation editing with persistence.
- Series, genres, language, year, ISBN/publisher and related metadata support.
- Cover/resource lookup is archive-aware and checks physical availability rather than trusting a stale `local` flag.
- Batch download, remove-local and delete-record actions are separated from the current row selection and show explicit batch-selection state.

## Reader

- JavaFX Canvas reader; no WebView dependency for the main reading pipeline.
- FB2/FBD, FB2-in-ZIP, EPUB, TXT, PDF and CBZ/CBR.
- CBZ/CBR Comic Reader: fit-page, fit-width, continuous scroll, dual-page spreads, manga RTL layout and thumbnails. Comic pages are naturally ordered and decoded lazily with bounded compressed-page, raster and cache limits.
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
- Custom-field definitions and values are included in portable versioned user-data transfer.


## Library health and artifact integrity

- Incremental non-destructive audit for local artifacts: existence, content size, SHA-256 and archive readability.
- Audit baselines/cache are persisted separately from catalogue artifact metadata; unchanged physical files reuse prior content hashes.
- Missing, corrupt/unreadable and content-changed artifacts are reported separately; audit does not auto-delete or auto-repair.
- Unified Library Health dashboard shows artifact integrity, duplicates, metadata gaps, SQLite/Lucene state and backup age.
- Dashboard refresh runs off the JavaFX thread, provides category drill-down/recommended actions and exports a text report.

## Export, device and actions

- Export/device profiles.
- External reader/open-file actions.
- User scripts/book actions with controlled integration.
- HTML/list/export paths and copy-between-collections flows present in the application feature set.

## OPDS and MCP

- Separate OPDS sidecar integrated into the desktop lifecycle.
- Default OPDS bind `127.0.0.1:8088`, configurable from the UI.
- HTTP is loopback-only; LAN exposure requires TLS/HTTPS with PKCS12/JKS key material.
- OPDS settings can generate/regenerate a managed self-signed certificate or import X.509 PEM + unencrypted PKCS#8 private key, display the SHA-256 fingerprint and explain self-signed trust requirements.
- Managed keystore passwords are stored only through the explicit `mhlenc:v1:` authenticated-encryption envelope; authenticated pre-envelope ciphertext is upgraded on persistence.
- Optional Basic authentication and autostart, with per-client failed-auth throttling and bounded request concurrency.
- Scoped OPDS/API Bearer tokens can be created and revoked from OPDS settings. Tokens have optional device names, separate catalog-read/download scopes, last-used metadata, one-time secret display and hash-only persistence; revoked tokens fail immediately.
- Authors/series/genres/search/book metadata and streamed local-book downloads.
- OPDS 2.0 JSON endpoints for library/search/collections/groups/favorites/continue-reading with stable pagination and OPDS 1.x compatibility retained.
- `/health` lifecycle probe with a stricter default policy when exposed beyond loopback.
- Separate read-only MCP runtime for supported library access scenarios.

## Localization and context help

- File-based `Lang/<code>.json` localization.
- Bundled Ukrainian, English and Bulgarian UI catalogues.
- Search, Reader, Import, OPDS and Backup programmatic UI text uses stable localization keys with synchronized UK/EN/BG values and format placeholders.
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

## JavaFX workspace lifecycle hardening — 2026-09-06

- Reloadable FXML views receive a fresh Spring-autowired controller instance on every load.
- Dashboard, statistics, search, book table and group views dispose subscriptions to long-lived application state when closed/replaced.
- Book workspace database loading is asynchronous, cancellable and protected from stale A → B / collection-switch completions.
- Group-list loading is asynchronous with loading/empty/error states and preserves a requested group until the current list load completes.

## 7.3 annotation foundation (Iteration 35)

Highlights and notes now have a durable backend model with book/artifact/chapter/text anchors, selected quote/context relocation hints, color/note/tags and timestamps. Data is restart-safe in SQLite V57 and included in portable user-data schema v4. Reader creation/rendering and the Annotation Manager are intentionally tracked separately in MHL-203/MHL-204.

## 7.3 Reader annotations (Iteration 36)

- Shift+drag text selection with visible draggable endpoint handles.
- Keyboard text selection with Shift+Left/Right.
- Selection context actions: Highlight, Add note, Copy and Clear selection; keyboard annotation shortcuts are available.
- Highlight/note creation persists through the application annotation service and returns immediately to the Reader UI through the bounded background executor.
- Persisted highlights/notes are restored when a book is reopened and rendered from source-text offsets, so ordinary font/margin/page-layout changes do not move the annotation.
- Artifact-specific anchors never render against another book representation unless explicitly rebound by a later workflow.

## 7.3 Annotation Manager (Iteration 37)

The Tools menu now exposes a global Annotation Manager for highlights and notes. It searches book titles, selected quotes, note text, chapter names and tags; filters by book/type/color/tag/date; uses bounded pagination; opens an annotation at its Reader location; edits note/color/tags; supports confirmed delete with one-step undo; and streams the filtered set to UTF-8 CSV. The workspace talks only to application annotation contracts, while SQLite query/paging remains an infrastructure adapter.

## 7.3 Rich annotation export (Iteration 38 / MHL-205)

Annotation Manager now exposes a rich export flow for Markdown, JSON and self-contained HTML. The application-layer `AnnotationExportService` streams bounded pages from `AnnotationExportQueryPort`, supports one book, multiple selected books or the whole library, writes UTF-8, preserves stable annotation ids and book/chapter metadata, and publishes through a same-directory temporary file so cancellation or failure does not expose a partial final export. Markdown and HTML have user-editable document/item templates; JSON uses the version marker `myhomelib.annotations.export/v1`. SQLite selection, deterministic ordering and tag loading remain infrastructure concerns rather than UI concerns.

## 7.3 PDF Reader and text-layer navigation (Iterations 38/40 / MHL-206, MHL-207)

PDF is a Reader-supported book format through a dedicated `myhomelib-reader` adapter. The existing Reader workflow prepares the PDF off the JavaFX thread and opens `PdfReaderView`, which provides previous/next page navigation, zoom/reset, Fit Width, Fit Page, continuous scrolling, an optional thumbnail sidebar, outline/TOC navigation, text-layer search and page bookmarks. Page rasterization is lazy, uses a bounded LRU raster cache, and is serialized on a background executor. Generation/session guards prevent stale render or text-search completion from changing a switched or closed Reader. The current page and PDF bookmarks reuse the existing `ReaderPosition` and bookmark persistence paths.

MHL-207 search uses only the embedded PDF text layer and returns page + snippet results; scanned/image-only PDFs remain readable but explicitly report that searchable text is unavailable. OCR is not implicit. Outline traversal and text search are bounded and cancellable. PDFBox types remain confined to `myhomelib-reader`; UI receives only renderer-neutral PDF outline/search values. An application-level `PdfAnnotationSelectionData` contract records page-local text coordinates for future PDF text selection/annotation work without creating a second annotation persistence model or pretending page-local offsets are global Reader offsets.

## 7.3 Read Aloud, accessibility and Reader regression (Iteration 48 / MHL-209, MHL-212, MHL-213)

Reader now exposes local system Read Aloud through an application TTS provider boundary. Windows uses System.Speech, macOS uses `say`, and Linux uses `espeak-ng`/`espeak` when available. Playback is asynchronous, supports system voice selection, 0.5–2.0x rate, pause/resume/stop and current-sentence highlighting. No cloud TTS transport is enabled by this feature.

Accessibility hardening covers all bundled FXML views, keyboard-focusable buttons, semantic accessible names for icon controls, guarded theme contrast and a persisted Reduced Motion setting. Reduced Motion disables Reader auto-scroll. The Reader regression corpus covers FB2/EPUB/PDF/CBZ, malformed and Unicode cases, a multi-megabyte FB2 case and persistence of progress/bookmarks/annotations after reopen.

## 7.3 Reader dictionary and translation providers (Iteration 39 / MHL-210, MHL-211)

Reader text selection now exposes Dictionary and Translate actions through renderer-neutral callbacks. Dictionary lookup is application-provider based and defaults to an offline UTF-8 TSV provider, so a selected word can be resolved without leaving Reader or using the network. Translation uses the same provider boundary: a local exact-phrase provider is available offline, while DeepL, Google Cloud Translation and a generic custom HTTPS adapter are opt-in.

Remote translation is never triggered by selection, book open or selection-change observers. The user must explicitly choose Translate, select a provider and approve a privacy confirmation before a remote provider can be invoked. Active requests use bounded deadlines/cooperative cancellation, and Reader book-switch/dispose guards prevent stale completion from being presented in another book. Cloud credentials are configuration secrets, not result metadata, and plaintext persisted credentials are rejected.


## 7.4 Background content indexing and resource profiles (Iteration 50 / MHL-303, MHL-307)

Full-text content indexing now runs through a persistent priority queue rather than on the caller/UI thread. Unfinished work is checkpointed per collection and restored on startup; users can pause, resume or cancel work, and a newer pending task supersedes an older pending task for the same artifact. Checkpoint publication is atomic and shutdown ordering prevents stale asynchronous checkpoint writes from overwriting the final restart snapshot.

Indexing resource usage is configurable through Eco, Balanced and Fast profiles. Profiles bound worker concurrency and I/O rate, and the optional pause-on-battery policy uses cached asynchronous power-state detection so UI callers do not execute platform probes. The selected profile and battery preference persist in application settings.

## 7.4 Search Everywhere and content-index health (Iteration 51 / MHL-304, MHL-305)

Global Search now lets the user choose Metadata, Contents or Both. Full-text hits are presented with book/author/chapter context, a bounded snippet and relevance, and opening a hit projects the matching artifact into Reader and jumps to the indexed global text offset. Content queries are cancellable and run outside the JavaFX thread, so a newer search supersedes an older one without freezing the workspace.

Library Health now exposes the independent content-index schema version, document count, on-disk size and compatibility/error state. Content rebuild is a separate action from the metadata-index rebuild in Database Tools. It streams books through the existing extractor SPI and writes a candidate Lucene index; only a fully successful candidate replaces the active directory. Cancel/failure therefore leaves the previous searchable index intact, while progress and cancellation remain visible to the user.

## 7.5 Sync foundation, WebDAV and conflict/security hardening (Iterations 52–55 / MHL-401…405)

User-data sync uses stable sync IDs, schema/version metadata, UTC timestamps and tombstones across reading progress, bookmarks, annotations, ratings, groups, favorites and settings. Local-folder/Syncthing transport publishes immutable ChangeSet bundles through same-directory staging and atomic rename, ignores partial files and tracks per-device cursors instead of synchronizing a live SQLite database.

WebDAV transport reuses the same ChangeSet model. Remote bundles are protected with authenticated AES-256-GCM before upload; endpoint credentials and the shared sync key are stored through `SecretStore`. HTTPS is required for non-loopback endpoints. Upload publication uses `.part` followed by WebDAV `MOVE`, transient network/HTTP failures are retried idempotently, PROPFIND XML is parsed with the secure XML boundary, and cross-origin/path-escape `href` values are rejected before credentials can be sent.

Conflict resolution is now type-aware and no-loss. Reading progress keeps the furthest position; scalar user state uses a deterministic latest policy; independent annotation edits are merged with tag union; live-vs-tombstone, simultaneous edits of the same annotation field and group conflicts require explicit review. Manual conflicts are projected through application DTOs to a JavaFX review dialog that shows local and remote snapshots and requires an explicit LOCAL/REMOTE choice without exposing domain records to the UI layer.

Sync encryption envelopes are now v2 and carry a stable key id authenticated by AES-256-GCM AAD. `SecretStore` keeps the active key and, during rotation only, one previous key. Legacy v1 and previous-key bundles can be authenticated/decrypted during migration; WebDAV can rewrap all remote bundles with the active key before the previous key is removed. A second rotation is rejected until the current migration is completed.


## 7.5 Remote catalogue and browser reading (Iterations 56–59 / MHL-406…409)

OPDS 2.0 JSON exposes library/search/collections/groups/favorites/continue-reading while retaining OPDS 1.x compatibility. Scoped Bearer tokens use one-time high-entropy secrets, hash-only persistence, optional device labels, catalog/download scopes, last-used metadata and immediate revocation.

The authenticated responsive Web Library is served from `/web/` by the existing HTTPS sidecar. It provides bounded catalogue/search pagination, book details, Continue Reading and scope-protected downloads; web catalogue data is never anonymous.

The basic Web Reader supports EPUB and FB2 through the existing `ContentExtractionService` adapters. It renders a chapter TOC, responsive reading surface, light/sepia/dark themes and browser-persisted font size. Progress is stored in the same `reading_progress` record used by the desktop Reader using a `ReaderPosition`-compatible anchor, so reopen/resume and subsequent sync operate on shared state. Unsupported formats offer the protected download action instead of an incomplete browser renderer.


Continue Reading is now a cross-device shelf shared by desktop and web. It is backed by the same `reading_progress` rows used by both readers, shows current percent, chapter, last device and update time, orders by recency, and excludes deleted or completed books. Resolved remote `READING_PROGRESS` records are projected into this same store, so synced progress becomes visible immediately in both surfaces; progress at 100% leaves the active shelf by rule.

## 8.0 Plugin SPI core (Iteration 61 / MHL-501)

MyHomeLib now has a dedicated `myhomelib-plugin-api` module for third-party extension contracts. The stable surface covers metadata, cover, import, metadata extraction, content extraction, export, translation, dictionary, device and optional AI providers. Existing mature application SPIs are exposed through facade interfaces rather than moved, preserving compatibility while giving plugin authors a single API module.

Every plugin declares its ID/version, supported Plugin API range, provided services and any intentional core-service overrides. `PluginLoader` validates compatibility and service types before activation; a plugin cannot silently replace a core service. Java `ServiceLoader` discovery is part of the contract and is covered by a sample plugin acceptance test.

## 8.0 Plugin permissions and isolation (Iteration 63 / MHL-502)

Plugin API 1.1 adds explicit `NETWORK_ACCESS`, `FILESYSTEM_READ` and `FILESYSTEM_WRITE` capability declarations. The host can inspect requested permissions before enable, and every approval is bound to the exact plugin id/version and must match the manifest. Trust is host-owned: `UNTRUSTED` plugins cannot execute in-process, while a new plugin version returns to the untrusted preview state instead of inheriting stale approval.

`PluginManager` owns enabled/disabled/quarantined state and provides a managed invocation boundary. Ordinary plugin exceptions, linkage failures and assertion failures are converted to failed invocation results and quarantine the plugin instead of escaping into the host. Calls may additionally require specific approved capabilities and fail closed when approval is insufficient. This boundary is deliberately not described as an OS sandbox for trusted Java code; untrusted execution would require a future out-of-process host.

## 8.0 Plugin SDK and samples (Iteration 64 / MHL-503)

Plugin API 1.2 adds `PluginTestHarness`, a developer/CI helper that reuses host structural validation for API compatibility, exact service bindings, declared core overrides and permission manifests. The harness creates a synthetic exact-version trusted approval only for the contract test; passing it never grants runtime trust in the application.

The buildable `myhomelib-plugin-samples` reactor module contains three independent reference entrypoints: an offline dictionary plugin with no permissions, a metadata plugin declaring `NETWORK_ACCESS`, and an export plugin declaring `FILESYSTEM_READ` + `FILESYSTEM_WRITE`. A real `META-INF/services` descriptor is discovered in tests and all sample manifests are validated through the public harness. Authoring and compatibility guidance lives under `docs/plugin-sdk/`. MHL-506+ remain the next ecosystem work.

## 8.0 Device profiles (Iteration 65 / MHL-504)

Application-owned device profiles describe a safe removable-reader target without storing absolute drive letters. Built-in profiles cover a generic folder, Kindle USB, Kobo, PocketBook and Android storage; custom profiles persist their display name, ordered preferred formats and a relative destination subfolder through `ApplicationSettingsPort`. Best-effort detection uses only well-known marker folders and falls back to the generic profile when the target is unknown. Destination resolution rejects absolute paths and `.`/`..` segments so a profile cannot escape the user-selected mount root.

Export profiles now persist an optional device-profile id. During send/export, `ExportToDeviceUseCase` keeps a manually requested format first and then follows the device profile preference order. It prefers an already registered local `BookArtifact` in a compatible format, otherwise uses an available converter, and finally preserves the legacy direct-source fallback when the source itself already matches an allowed format. This avoids needless conversion while keeping existing export/collision/crash-safety behavior intact. Iteration 66 / MHL-505 builds on this pipeline rather than replacing it.

## 8.0 Send to Device completion safety (Iteration 66 / MHL-505)

The existing `ExportToDeviceUseCase` already owns batch send, progress reporting, cancellation, collision policies and crash-safe staged publication. MHL-505 keeps those semantics and adds an explicit completion contract. Legacy callers default to `VERIFY_READABLE`; the desktop Send to Device flow requests `EJECT_SAFE`, which forces the committed target file through `FileChannel.force(true)` before the item is counted as exported. A parent-directory metadata flush is attempted after the required file flush but remains best-effort because directory channels are not portable across all Windows/removable-device filesystems.

A failed required file flush is reported as an export failure rather than a successful eject-safe completion. The UI does not claim that Java can make physical removal safe on its own: after a successful `EJECT_SAFE` batch it instructs the user to invoke the operating system’s safe-removal/eject action before disconnecting the device. Default collision behavior remains rename-without-overwrite, ASK can resolve to skip, and cancellation leaves no staging-file residue. MHL-506 (BookConverter SPI/capabilities) is the next planned device/export task.



## 8.0 Optional calibre CLI adapter (Iteration 68 / MHL-507)

The conversion SPI now includes an optional calibre provider implemented around `ebook-convert`. Calibre is not bundled and is not required for normal startup: each calibre target reports unavailable when the executable cannot be resolved, so built-in converters and all non-conversion functionality continue to operate unchanged. Users may set `converter.calibre.executable` to an explicit executable path/name; otherwise the adapter searches `PATH` and the standard Windows Calibre2 install location.

Infrastructure registers independent calibre targets for EPUB, MOBI, AZW3, PDF, FB2 and TXT over a curated set of common source formats. The adapter never builds a shell command string: `ProcessBuilder` receives the executable, source and target as separate arguments. Input bytes are first copied into the application-owned `cache/calibre` sandbox with a normalized source extension; the application-owned staged target is then handed to `ebook-convert`. Child output is drained with a bounded capture, non-zero exit diagnostics are bounded, timeout is clamped, cancellation forcibly terminates the child process, and the temporary source is removed on every exit path. The MHL-506 use case still owns final validation, publication, hashing and `BookArtifact` registration.

## 8.0 Audiobook support v1 (Iteration 69 / MHL-508)

MP3 and M4B are first-class library/import formats and open inside the desktop Reader rather than through an external application. The audiobook workspace supports play/pause, seek, chapter navigation, playback speed from 0.5× to 2.0×, sleep timer, exact-position bookmarks and resumable progress. M4B embedded chapters are read through `ffprobe`; files without embedded chapter metadata receive one synthetic chapter so the same session model remains valid.

Playback uses the optional system FFmpeg tools (`ffprobe` + `ffplay`) behind `AudioPlaybackBackend`. The application remains buildable without bundling FFmpeg; when the backend is unavailable the Reader fails closed with a localized message instead of falling back to an unsafe shell command. Probe output is fully drained while only a bounded diagnostic prefix is retained, and child processes are terminated on timeout/close.

Multi-file audiobooks require explicit `audiobookGroup` metadata; `trackNumber` controls ordering. This prevents an MP3 and M4B copy of the same title from being mistaken for two sequential tracks. Exact progress and bookmarks use `audio:<millis>:<track>:<chapter>`. The anchor is stored in the existing reading-progress/annotation-compatible persistence path and survives the current cross-device sync projector unchanged, so no parallel progress database is introduced.



## 8.0 Knowledge-management Markdown integration (Iteration 70 / MHL-509)

Annotation Manager can export annotations into an Obsidian/Joplin-compatible folder tree with exactly one Markdown file per logical book. Folder, file-name and annotation-body templates are configurable; exports are UTF-8 and deterministic, with optional YAML frontmatter and optional links back to the exact MyHomeLib annotation.

Re-export is deliberately conservative. `REPLACE_MANAGED` replaces only a file carrying MyHomeLib's ownership marker for the same book id; an unrelated user note at the deterministic path causes a visible failure instead of data loss. `SKIP_EXISTING` leaves existing notes untouched, and `FAIL_IF_EXISTS` provides a strict mode. No duplicate-suffix files are generated automatically. The flow reuses MHL-205 bounded annotation paging and stable ordering, including Unicode metadata/tags, and publishes each file atomically after complete generation.


## 8.0 Optional provider-neutral AI extension point (Iteration 71 / MHL-510)

Plugin API 1.3 adds the tenth public service identifier, `AI_PROVIDER`, backed by the provider-neutral `AiProvider` contract. The core application does not ship or enable an AI provider by default. Providers declare supported operations (`SUMMARY`, `QUESTION_ANSWER`), whether network access is required and the exact secret names they need. `PluginLoader` rejects a network-backed AI provider unless its manifest explicitly requests `NETWORK_ACCESS`; the normal exact id/version approval and trusted in-process rules still apply.

AI execution is mediated by `AiExtensionService`, not by a provider-owned consent decision. A book is opted out by default and must be explicitly enabled through persistent per-book settings before any provider call. Requests that include book text additionally require explicit content-sharing consent, and a network-backed provider additionally requires explicit network consent for each invocation. Prompt, book-content and response sizes are bounded, cancellation/deadline checks are host-owned, and missing/unsupported providers fail closed.

Provider code does not receive the host `SecretStore` and cannot construct an execution context. The host namespaces provider credentials under `myhomelib.ai.<providerId>.<secretName>` and exposes only provider-declared secret names through `AiProviderContext`. Credential values are written/read/deleted only through the existing `SecretStore` abstraction; if a provider requires a secret and no secure store is available, execution is rejected instead of persisting plaintext configuration. No network vendor, model, endpoint or credential is built into the core application.
