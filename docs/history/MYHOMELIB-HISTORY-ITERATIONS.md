# MYHOMELIB — History: Iterations 1–83

This is the canonical chronological index for iteration-level work. Detailed historical records are stored in `docs/history/records/`; older pre-iteration source notes are stored in `docs/history/source-notes/`. The current product contract remains the active root documentation.

| Iteration | Scope | Historical status |
|---:|---|---|
| 01 | Security / settings hardening | DONE |
| 02 | Formats and paths | DONE |
| 03 | Data integrity / Lucene | DONE |
| 04 | Local-copy / cache safety | DONE |
| 05 | OPDS transport guards | DONE |
| 06 | TLS / certificate / encryption envelope | DONE |
| 07 | E2E / PR CI | DONE locally; external CI evidence tracked separately |
| 08 | Supply-chain / source packaging | DONE locally; external evidence tracked separately |
| 09 | Concurrency / executor lifecycle | DONE |
| 10 | JavaFX lifecycle / async loads | DONE |
| 11 | Transactional edit / domain immutability | DONE |
| 12 | Support bundle / temp lifecycle | DONE |
| 13 | SecretStore native credentials | DONE |
| 14 | Critical UI i18n | DONE |
| 15 | Startup orchestration | DONE |
| 16 | Windows acceptance harness | WIP external acceptance |
| 17 | GitHub + Windows acceptance harness | WIP external acceptance |
| 18 | Candidate-bound external acceptance | WIP external acceptance |
| 19 | Release-evidence integrity ratchet | WIP external acceptance |
| 20 | Final operator flow hardening | WIP external acceptance |
| 21 | Candidate-bound acceptance harness | WIP external acceptance |
| 22 | Windows host/session binding | WIP external acceptance |
| 23 | Multi-artifact duplicate foundation | DONE foundation |
| 24 | Duplicate review / merge | DONE |
| 25 | Metadata provider SPI | DONE |
| 26 | Open Library provider | DONE |
| 27 | Google Books provider | DONE |
| 28 | Metadata merge preview | DONE |
| 29 | Batch metadata editor | DONE |
| 30 | Bulk metadata undo | DONE |
| 31 | Folder watcher | DONE |
| 32 | Smart collections | DONE |
| 33 | Custom fields | DONE |
| 34 | Artifact integrity / library health | DONE |
| 35 | Annotation foundation | DONE |
| 36 | Reader annotations | DONE |
| 37 | Annotation manager | DONE |
| 38 | Annotation export + PDF Reader | DONE |
| 39 | Reader text providers | DONE |
| 40 | PDF closure | DONE |
| 41 | Undo / transaction / offline acceptance | DONE |
| 42 | External acceptance hardening | DONE locally; six external MHL gates remain OPEN |
| 43 | Runtime log reliability | DONE |
| 44 | Reader transition efficiency | DONE |
| 45 | Archive traversal efficiency | DONE |
| 46 | Archive streaming materialization | DONE |
| 47 | Comic Reader | DONE |
| 48 | TTS / accessibility / Reader regression | DONE |
| 49 | ContentExtractor / content index / annotation search | DONE |
| 50 | Indexing queue / resource throttling | DONE |
| 51 | Search Everywhere / index health | DONE |
| 52 | Sync foundation / local-folder transport | DONE |
| 53 | WebDAV Sync | DONE locally after final full regression |
| 54 | Repository history/root cleanup + WebDAV closure | DONE locally; 13/13 reactor, 894 tests |
| 55 | Sync conflict resolution / encryption-key rotation | DONE |
| 56 | OPDS 2.0 JSON feeds | DONE locally; module 16/16 + OPDS E2E 2/2 |
| 57 | OPDS scoped token authentication | DONE locally; targeted security/UI 17/17 |
| 58 | Web Library v1 | DONE locally; targeted Web/HTTP 16/16 + architecture/completeness PASS |
| 59 | Web Reader basic EPUB/FB2 | DONE locally; targeted/integration 21/21 + static gates PASS |
| 60 | Continue Reading across devices | DONE locally; targeted 24/24 + backup 9/9 + architecture 13/13 |
| 61 | Plugin SPI core | DONE locally; plugin-api 9/9 + architecture 14/14 |
| 62 | Annotation FTS performance / stable-rowid hardening | DONE locally; infrastructure 423 tests + architecture/completeness PASS |
| 63 | Plugin permissions / isolation | DONE locally; plugin-api 22/22 + architecture/completeness PASS |
| 64 | Plugin SDK / samples / test harness | DONE locally; plugin-api 25/25 + sample module 2/2 + architecture 14/14 + 16-project test-compile |
| 65 | Device profiles / preferred artifact selection | DONE locally; targeted MHL-504 9/9 + Application 247 tests + architecture/completeness PASS + 16-project test-compile |
| 66 | Send to Device completion safety | DONE locally; targeted MHL-504/MHL-505 14/14 + Application 255 tests + architecture 14/14 + static gates PASS + 16-project test-compile |
| 67 | BookConverter SPI / conversion capabilities | DONE locally; targeted conversion/device 20/20 + Application 265 tests + SQLite persistence 2/2 + architecture 14/14 + static gates + 16-project test-compile |
| 68 | Optional calibre CLI adapter | DONE locally; calibre adapter 7/7 + architecture 14/14 + static gates + 16-project test-compile |
| 69 | Audiobook support v1 | DONE locally; new audiobook 16/16 + affected 21/21 + Application 268 + architecture 14/14 |
| 70 | Obsidian/Joplin-compatible Markdown integration | DONE locally; knowledge exporter 9/9 + UI 2/2 + Application 277 + architecture 14/14 |
| 71 | Provider-neutral AI extension point | DONE locally; AI host 7/7 + plugin AI 3/3 + Plugin API 28/28 + Application 284 + architecture 14/14 |
| 72 | 8.0 release hardening / split full-regression consolidation | DONE locally; 1,035 tests split baseline, no new monolithic claim |
| 73 | External acceptance handoff readiness / canonical six-gate runbook | DONE locally; six external gates remain OPEN |
| 74 | Release-candidate source/dist integrity binding | DONE locally; six external gates remain OPEN |
| 75 | Final reviewer evidence self-containment | DONE locally; six external gates remain OPEN |
| 76 | Reviewer Markdown/JSON consistency hardening | DONE locally; six external gates remain OPEN |
| 77 | Versioned external-evidence contract registry | DONE locally; six external gates remain OPEN |
| 78 | Final local preflight / release-candidate preparation | DONE locally; 1,035-test split baseline + Linux packaging smoke + 84 local checks PASS; six external gates remain OPEN |
| 79 | Windows Spring wiring startup hotfix | DONE locally; real-host startup blocker fixed; Application 285 + Bootstrap 17 + Architecture 14 PASS; external gates remain OPEN |

| 80 | Full Spring runtime wiring hardening | DONE locally; full Spring context PASS + 1,038-test split baseline; external gates remain OPEN |
| 81 | Windows export/TTS usability hotfix | DONE locally; real FB2_ZIP + TTS display regressions fixed; 1,042-test split baseline; external gates remain OPEN |
| 82 | Windows workflow/state hotfix | DONE locally; TTS Unicode transport + mass-export refresh/selection + Reader-safe Annotations/Notes fixed; 1,048-test split baseline; external gates remain OPEN |
| 83 | Windows TTS discovery + Annotation Manager FXML hotfix | DONE locally; fail-closed framed PowerShell voice discovery + JavaFX 21 FXML callback fix; 1,049-test split baseline; external gates remain OPEN |

## Current boundary

As of 2026-09-13, Iterations 47–83 are locally closed. Iteration 54 records repository-history cleanup and the final full-regression closure of WebDAV Sync; Iteration 55 adds no-loss conflict handling and sync-key rotation/migration; Iteration 56 adds OPDS 2.0 JSON feeds while preserving OPDS 1.x compatibility; Iteration 57 adds scoped OPDS/API Bearer tokens with one-time secrets, hash-only persistence, last-used metadata and immediate revocation; Iteration 58 adds the authenticated responsive Web Library v1 over the existing HTTPS sidecar; Iteration 59 adds the EPUB/FB2 Web Reader with TOC, theme/font controls and shared desktop/web reading progress; Iteration 60 turns that shared progress into a cross-device Continue Reading shelf with progress, last device/time, sync projection and automatic completion removal; Iteration 61 introduces the dedicated versioned plugin-api module with nine stable extension contracts and explicit core-service override declarations; Iteration 62 hardens annotation full-text indexing by replacing repeated scans of the `UNINDEXED` annotation id with a stable integer rowid mapping, including upgrade and `VACUUM` regression coverage; Iteration 63 adds host-owned plugin permissions/trust, exact approval binding, disable/quarantine lifecycle and managed failure containment; Iteration 64 adds the public plugin CI harness, buildable metadata/dictionary/export samples and authoring/compatibility documentation; Iteration 65 adds application-owned device profiles and preferred existing-artifact selection for removable-device export; Iteration 66 preserves the established send pipeline while adding required committed-file durability flush for `EJECT_SAFE` completion plus explicit OS safe-removal guidance; Iteration 67 adds the provider-neutral BookConverter capability contract and application-owned bounded/cancellable conversion lifecycle with atomic artifact registration; Iteration 68 adds an optional no-shell calibre `ebook-convert` adapter with safe discovery, bounded diagnostics, timeout/cancellation and sandboxed temporary input. Iteration 69 adds MP3/M4B import and an internal audiobook Reader with explicit multi-track grouping, chapters, speed/sleep controls, exact bookmarks and shared cross-device `audio:` progress anchors. Iteration 70 adds deterministic one-file-per-book Markdown export for Obsidian/Joplin over the existing bounded annotation projection, with optional frontmatter/backlinks and ownership-checked re-export that never silently overwrites unrelated notes. Iteration 71 adds Plugin API 1.3 with an optional provider-neutral AI extension point, host-owned per-book/content/network consent checks and SecretStore-only provider credential access; no AI provider is enabled by default. Iteration 72 is a release-hardening checkpoint: it restores the missing Reader golden ZIP, makes PDF raster tests explicitly headless, refreshes stale UI/E2E fixtures after audiobook/Continue Reading changes, and establishes a 1,035-test split full-regression baseline without claiming a newer monolithic reactor PASS. Iteration 73 hardens the external-acceptance handoff by refreshing the current validation state, adding a canonical six-gate runbook and a PR-CI readiness ratchet that remains fail-closed on missing harness/workflow contracts while never converting offline readiness into external PASS. Iteration 74 adds deterministic release-candidate integrity records that bind exact Git SHA + source-tree policy state + complete checksummed platform payload, and makes GitHub connected acceptance require the Windows integrity record before candidate staging. Iteration 75 carries that exact Windows integrity JSON, sidecar and release checksum manifest through digest-verified ingest into the immutable final reviewer ZIP, where they are independently revalidated against the final candidate evidence. Iteration 76 makes the reviewer-facing GitHub/final Markdown fail-closed canonical projections of the embedded JSON so human-readable status cannot diverge from machine-readable acceptance evidence. Iteration 77 centralizes exact evidence scenario/schema compatibility in one fail-closed registry that is itself candidate-bound, preventing producer/verifier schema drift and silent legacy/future evidence acceptance. Iteration 78 performs the final local preflight, revalidates the 1,035-test split baseline after bounded content-index executor hardening, closes the locally runnable check sweep, and proves the Linux production/portable packaging path while leaving the six live GitHub/Windows gates untouched. Iteration 79 responds to real Windows startup evidence: it explicitly binds Spring to the intended `ContentIndexingQueueService` production constructor after Iteration 78 introduced a second constructor for deterministic worker-pool testing, and adds a context-level regression so the missing-no-arg failure cannot recur unnoticed. Iteration 80 responds to the next real Windows startup trace by explicitly wiring `OpdsAccessTokenService`, adding an eager full-Spring-context smoke, removing `final` from two Spring-proxied repositories, and adding constructor/proxyability policy guards so runtime bean-creation defects are caught locally before another Windows handoff. Iteration 81 responds to subsequent real Windows use by enforcing per-format export resolution so `FB2_ZIP` cannot silently fall back to plain `FB2`, and by making the Reader TTS voice chooser render explicit human-readable labels instead of provider implementation-object identities. Iteration 82 responds to further real Windows workflow evidence by making voice discovery code-page independent via UTF-8 Base64 transport, treating successful mass export as a state-refresh/selection-consumption transaction that preserves Author Workspace context, and routing Annotations/Notes through Reader-safe cleanup before workspace loading. Iteration 83 responds to the next real Windows trace by making TTS discovery fail closed with encoded PowerShell commands, strict `MHLVOICE|` framing and separated stderr, and by moving the Annotation Manager table resize callback out of FXML into Java after JavaFX 21 rejected the symbolic callback string. The external acceptance items MHL-010/011/012/017/018/019 remain OPEN and require real Windows/GitHub evidence.

Historical checkpoint/task/continuation files were moved out of the repository root on 2026-09-12 to keep the source root code-focused. Their original filenames are preserved under `docs/history/records/` so cross-references inside the historical record remain readable.
