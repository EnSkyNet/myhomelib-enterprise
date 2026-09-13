# MyHomeLib 1.0.0 — parity audit

Snapshot: 2026-08-23

This file records what is already present in the Java rewrite and what still has to be completed or proven before calling the build a production FINAL release.

## Baseline
The comparison baseline is the current original MyHomeLib feature set: multiple collection types, INPX/.hlc2, manual/network updates, folder synchronization, DB maintenance, copy between collections, INPX export, classic search syntax/presets, user-data exchange by LibID, online download, external readers, send-to-device/converters/scripts, HTML list export, portable mode, contextual help and a read-only MCP server.

## Present in this branch
- Multiple SQLite collections and instant switching.
- Local/online, FB2/generic and external INPX collection types.
- Collection wizard, INPX import/update and .hlc2/SQLite attach/migration path.
- Online book download and cancellation with .part cleanup.
- ZIP/CBZ/7z/RAR/CBR/TAR/TGZ/TBZ2/TXZ/CPIO archive reading pipeline.
- FB2/FBD, EPUB and TXT reading/import; generic file cataloguing for external readers.
- Author/series/genre navigation and Cyrillic/Latin alphabet toolbar.
- Lucene advanced search, classic aliases, %contains%, ="exact", OR and numeric/date comparisons; saved searches.
- Groups/favorites, user rating, reading progress and reviews.
- User-data import/export keyed by LibID with local fallback.
- Book copy between collections.
- INPX export.
- External reader commands by file type.
- Send-to-device/export with external FB2→EPUB/PDF/MOBI/LRF commands, templates and post-command placeholders.
- Book and author description editing.
- HTML/TXT/RTF list export.
- Portable mode (myhomelib2.ini).
- Ukrainian/English UI plus bundled Bulgarian and signed external language catalog support.
- Context help framework.
- Read-only standalone MCP module with catalog search and bounded content extraction.
- Built-in low-memory Reader: FB2/FBD/EPUB/TXT, pagination, themes, search, TOC, bookmarks, notes/positions, rich text and streaming resources.

## Must be completed before FINAL

### P0 — build/release blockers
1. Run a real full Maven build with all external dependencies (`mvn clean verify`) on a machine with Maven/network/cache. Static XML checks are not a substitute for Java compilation.
2. Compile and execute the 7z/RAR/TAR adapters against the exact Commons Compress and junrar versions in the POM. Exercise real archives, not only static API inspection.
3. Run JavaFX startup and every FXML workspace at runtime. FXML handler names are statically valid, but constructor injection, Spring bean wiring and CSS/resource loading still require runtime verification.
4. Run all 29 migrations through Flyway on a file-backed SQLite DB, then open/close/reopen the DB from the real application.
5. Build the Spring Boot executable JAR, MCP shaded JAR and the desktop jpackage image/installer; verify launch on a clean Windows 10/11 machine.
6. Add Maven Wrapper (`mvnw`, `mvnw.cmd`) so the release is reproducible without a preinstalled Maven.

### P0 — known functional defect
7. Fix folder synchronization of changed ordinary files. `FolderSyncService.updateBook()` currently updates only INPX/INP paths and returns `false` for changed FB2/EPUB/TXT/generic files. It must re-read metadata and update the existing catalog row while preserving LibID/user state.

### P1 — collection and INPX hardening
8. Add automated INPX compatibility fixtures covering multiple `.inp`, missing/custom `structure.info`, `archives.info`, DEL flags, duplicate LibID, non-UTF encodings and archive names with spaces/non-ASCII characters.
9. Verify repeated INPX update is idempotent and preserves rating/progress/review/groups/bookmarks/reader position for real large catalogs.
10. Test original Delphi `.hlc2` migration with several real schema generations. The adapter is implemented, but needs sample databases from old MyHomeLib versions.
11. Add transactional recovery for interrupted INPX update: old catalog must remain usable if import fails halfway.
12. Decide/update semantics for records removed from a new INPX: mark deleted vs physically remove, while preserving user data for possible reappearance.
13. Make folder sync memory-bounded for million-book libraries: it still builds a full `List<Path>` and a full `Map<String,Book>` in memory. Replace with streaming walk + batched DB lookups/temp index.
14. Re-import changed archive containers correctly and reconcile entries added/removed from ZIP/7z/RAR/TAR without losing user state.

### P1 — online library hardening
15. Prevent duplicate concurrent downloads of the same physical archive when two books inside it are requested simultaneously. Coordinate by target archive path, not only book id.
16. Add retry/backoff policy for transient HTTP 429/5xx/network failures and explicit user-friendly handling for 401/403/404.
17. Optional but strongly recommended: HTTP Range resume for large partially downloaded archives instead of always deleting `.part` after interruption.
18. Add checksum/content validation where the online catalog supplies size/hash; at minimum validate that the requested archive entry exists after download before marking the book local.
19. Add “remove local copy / keep catalog entry” for online collections so a downloaded archive can be freed without deleting the book from the catalog.
20. Test Basic Auth, redirects, URL templates `{archive}/{file}/{entry}`, Unicode URLs and servers without `Content-Length`.

### P1 — archive robustness
21. Real corpus tests for ZIP/7z/RAR4/RAR5/CBR/TAR.GZ/TAR.BZ2/TAR.XZ/CPIO, Unicode entry names, large entries and corrupted archives.
22. Graceful reporting for encrypted/password-protected archives and unsupported multi-volume RAR/7z instead of generic import failure.
23. Make archive indexing resumable/cancellable at entry level for very large archives.
24. Ensure archive-bomb limits are consistent between import, Reader, cover extraction, export and MCP.

### P1 — Reader completion
25. Full regression tests for FB2 footnotes/internal links, EPUB spine order/TOC, embedded images, very long paragraphs, selection/copy and position restoration after font/viewport changes.
26. EPUB TOC/navigation should use OPF/nav/NCX rather than only document order where applicable.
27. Add proper language-aware hyphenation dictionaries (UA/RU/EN at minimum) instead of lightweight heuristic-only behavior.
28. Add configurable tap zones/actions and selection handles suitable for touch devices; desktop behavior exists but mobile UX still needs platform work.
29. Add crash-safe periodic reader-position persistence, not only normal close/navigation checkpoints.
30. Add reader performance tests for 100+ MB FB2/EPUB and thousands of chapters/images.

### P1 — search/data correctness
31. Add automated tests for every classic search operator: `%text%`, `="exact"`, `<`, `>`, `<=`, `>=`, `<>`, `OR`, field aliases and combinations with language/library rating/date-added.
32. Verify Lucene rebuild and incremental indexing produce identical results after INPX update, metadata edits and delete/restore operations.
33. Normalize language/year/publisher/translator values consistently between FB2, EPUB, INPX and legacy `.hlc2` imports.

### P1 — user data and collections
34. Conflict policy for importing user-data JSON into a library where the same LibID maps to multiple rows or where a local book has no LibID.
35. Add schema/version field and backward-compatible migration for `.mhluserdata.json`.
36. Test copy-between-collections for loose files and every archive format; avoid copying the same big archive repeatedly when many selected books share it.
37. Preserve all user-state fields when copying/re-importing and document which state is collection-local vs book-global.

### P1 — device/export/external readers
38. Validate command-template quoting on Windows paths containing spaces/quotes/non-ASCII characters.
39. Add “test command” buttons for external reader and each converter, with captured stdout/stderr and timeout diagnostics.
40. External converter binaries (fb2mobi/fb2epub/fb2lrf/fb2pdf) are not bundled; either package supported tools where licensing permits or provide a first-run configuration wizard.
41. Test post-send placeholders and prevent accidental shell-injection from metadata inserted into command templates.
42. Add collision policies (overwrite/skip/rename) and batch progress/cancel for send-to-device.

### P1 — help/localization parity
43. Replace the current compact text help (11 topics) with the original-style full contextual help set. The original ships a ~55-page Ukrainian HTML help and F1 opens the exact context page.
44. Ensure every dialog created programmatically is localized; the current map-based translator covers much of the UI but needs a missing-string audit.
45. Keep Ukrainian/English as guaranteed built-ins; verify signed external language catalogs end-to-end. Bundled Bulgarian is an extension, not required for original parity.

### P1 — MCP parity
46. Compile/package and run the MCP server against a real collection with a real MCP client.
47. Add protocol conformance tests for initialize, notifications, tools/list, tools/call, parse errors and client disconnects.
48. `book_toc` currently returns real TOC only for FB2/FBD. Add EPUB nav/NCX TOC so MCP matches the built-in EPUB reader.
49. EPUB text extraction should follow the package spine, not alphabetical XHTML filename order.
50. Add tests for books stored inside ZIP/7z/RAR/TAR and enforce the same archive-size limits as the GUI.
51. Verify read-only mode on WAL collections that are open/being modified by the desktop app.

### P2 — quality/release polish
52. Add end-to-end tests: create collection → import → search → open Reader → bookmark/progress → restart → restore position.
53. Add end-to-end online test with a local HTTP fixture server: INPX update → download archive → cancel/retry → read book.
54. Add large-library benchmark with 100k/500k/1M catalog rows and record startup/search/import memory budgets.
55. Add structured error report/log bundle export for users.
56. Add installer upgrade/uninstall tests and preserve collections/settings across application upgrades.
57. Add release signing/checksums and a clean-machine smoke checklist.

## Mobile / multiplatform work (after desktop 1.0)
The JavaFX desktop build is not itself Android/iOS. The reader core is moving in the right direction, but a true mobile release still needs platform adapters:
- Android UI/storage/share intents/background download/lifecycle and Canvas/Compose renderer.
- Replace/abstract APIs unavailable on Android (notably StAX choices if needed by target runtime).
- iOS requires a KMP/native or Swift bridge; JavaFX/Spring modules cannot be reused directly.
- Mobile settings, file picker/SAF, notifications, sleep/brightness controls, touch selection and app-store packaging.

## Current static validation
- POM/FXML XML parsed: 36, errors: 0.
- FXML event handlers checked: 140, issues: 0.
- SQLite migrations V1–V29 apply to a clean SQLite DB.
- Active TODO/FIXME/not-implemented markers in Java/FXML/SQL: 0.
- Bash release scripts pass `bash -n`.
- Full Maven/JavaFX compilation was not executed in this environment because Maven/dependency cache is unavailable.

## Final local completion status — 2026-08-23

The source-level items that can be completed and checked in this container were implemented in the final tree. This includes the Maven Wrapper, changed-file/archive folder sync, transactional INPX recovery and legacy encodings, online archive coordination/retry/resume/validation, consistent archive limits, EPUB/TXT Reader completion, classic-search coverage, user-data conflict/version handling, safer command templates, command diagnostics, export collision/progress/cancel, MCP EPUB/protocol/WAL fixes, and diagnostic log-bundle export.

Per the user's instruction, gates that require unavailable external dependencies, real historical/corrupt/encrypted archive corpora, clean Windows/jpackage execution, third-party converter binaries, or real signing credentials are intentionally skipped rather than blocking packaging. They remain environment/corpus validation tasks and are not claimed as executed. `RELEASE_VALIDATION.txt` is the authoritative list of checks that actually ran.
