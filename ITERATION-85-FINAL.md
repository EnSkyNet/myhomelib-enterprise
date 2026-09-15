# MyHomeLib 8.0.0 — Iteration 85 FINAL

Iteration 85 closes the local Finish/Polish roadmap that remained after Iteration 84.

## Scope closed

- Book-list activity badges/counters for notes, highlights and bookmarks.
- Library filter by annotation presence: notes, highlights, or either.
- Consistent SQLite and Lucene semantics for annotation-presence filtering.
- Bulk page activity loading without N+1 queries.
- Selective Lucene refresh after annotation changes and restore flows.
- Lucene schema bump for derived activity flags with rebuild of stale indexes.
- Expanded display-capable JavaFX acceptance for Reader sidebar, book activity column and filter dialog.
- Reproducible Reader JFR/heap profiling for 20/50/100 MB FB2 and EPUB fixtures.
- Final regression on the user-supplied 707,154-record INPX and two large FB2 archives.

## Post-audit hardening — 2026-09-15

The final local candidate additionally closes high-impact findings from an independent source audit:

- native-process stdout/stderr are drained concurrently with bounded capture and process-tree timeout termination;
- supported-platform power-state probe failures fail conservatively instead of enabling heavy indexing;
- long-running JavaFX actions moved off the FX event thread with immutable/safe UI snapshots where needed;
- non-loopback OPDS is authenticated by default and browser-facing responses include hardening headers;
- direct and ConnectionScript downloads enforce byte limits and free-space guards;
- Reader temporary-resource cleanup no longer relies on per-file `deleteOnExit()` registration;
- MCP validates required SQLite schema shape before serving requests;
- GitHub Actions are pinned to immutable commit SHAs and Dependabot tracks Maven/Actions updates;
- build launchers enforce JDK 21+ and Maven 3.9.6+;
- large orchestration classes were reduced to remain within the project ratchets (`MainController` 674/680, `LuceneSearchService` 439/460).

## Local final status

- Maven `clean verify`: **1097 tests, 0 failures, 0 errors, 12 skipped**.
- JavaFX/Xvfb: **8/8 tests, 6/6 suites, 0 skipped**.
- Static/release gates: **PASS**.
- Reader JFR/heap 20/50/100 MB: **PASS**.
- Real INPX: **707154/707154, 0 errors**.
- Real FB2 corpus: **2/2 PASS**.
- Linux portable package, extracted smoke, checksums and Stage 23 artifact validation: **PASS**.

The original locally executable Iteration 83 audit roadmap is now closed. Windows-native and live-service release evidence remains external by design; see `docs/release/ITERATION-85-TEST-REPORT.md`.
