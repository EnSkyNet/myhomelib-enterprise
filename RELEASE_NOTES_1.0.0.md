# MyHomeLib 1.0.0 release notes

This release consolidates the MyHomeLib desktop application and the low-memory Canvas reader into one source tree.

Highlights:

- fixed reader Canvas wiring that caused blank pages;
- streaming FB2 parsing and bounded resource/page caches;
- long-paragraph pagination, reverse navigation and rich inline text;
- real justify, themes, font/layout controls, tap/swipe navigation and autoscroll;
- search, table of contents, bookmarks and reading-position persistence;
- SQLite/Flyway collection storage and Lucene search;
- FB2/FBD, EPUB and TXT catalogue import;
- ZIP/FB2ZIP/CBZ/JAR, 7z and RAR archive import/opening;
- multi-part INPX import with `structure.info` / `archives.info` handling;
- collection creation from a real INPX source instead of creating an empty database only;
- online-library download pipeline with progress, cancellation and `.part` cleanup;
- archive-aware Reader/export/cover resource access;
- bounded batch import and streaming directory traversal;
- persistent import batch-size setting;
- tree-view export and batch “mark as read” actions;
- delete-from-catalog action in book workspace;
- ZIP export stream lifetime fix;
- version normalized to 1.0.0 and build/run/package scripts included.

Archive safety limits are enforced and recursive nested-archive expansion is intentionally disabled.

The release targets SQLite. A full Maven build was not executed in the packaging container because Maven/dependency cache is unavailable there.

## Final parity hardening

The final source pass also includes:

- changed-file and changed-archive folder synchronization with user-state preservation;
- transactional INPX refresh/recovery plus non-UTF legacy catalogue decoding;
- online download de-duplication by physical archive, retry/backoff, HTTP Range resume and archive-entry validation;
- unified archive-bomb safety limits across import/Reader/export/cover/MCP paths;
- EPUB Reader and MCP navigation based on OPF spine and EPUB3 nav / EPUB2 NCX;
- byte-accurate MCP stdio framing and read-only WAL-safe SQLite access;
- safe argument-level command-template expansion and command test buttons with stdout/stderr diagnostics;
- export collision policies (overwrite/skip/rename), per-book progress and cooperative cancellation;
- rolling file logs and a privacy-aware diagnostic support ZIP;
- Maven Wrapper plus release/package/checksum scripts.

External build/platform/corpus gates that cannot run in the packaging container are intentionally documented as skipped in `RELEASE_VALIDATION.txt`; they are not represented as passed.
