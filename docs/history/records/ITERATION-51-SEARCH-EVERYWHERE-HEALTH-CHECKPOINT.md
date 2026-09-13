# Iteration 51 — Search Everywhere + Content Index Health

**Date:** 2026-09-12  
**Scope:** MHL-304, MHL-305; final combined validation of MHL-303/304/305/307  
**Status:** DONE (local technical closure)

## Delivered

- Global Search Workspace modes: Metadata / Contents / Both.
- Full-text content hits include bounded snippets, chapter metadata, relevance score and stable global text offset.
- Content search runs cancellably off the JavaFX thread; a newer query cancels the previous request.
- Double-click / Enter on a content hit opens the selected artifact in Reader and jumps to the indexed offset.
- Dedicated full-text content-index health surface in Library Health: schema version, document count, disk size, compatibility/error state.
- Separate content-index rebuild action with visible progress and cancellation.
- Content rebuild is streamed from the catalog and does not require materializing the whole corpus in memory.
- Rebuild writes a sibling candidate Lucene directory and swaps it into place only after success; cancellation/failure keeps the previous active index unchanged.
- Metadata-index rebuild remains a separate Database Tools action, preserving the independent lifecycle required by MHL-302/MHL-305.

## Validation

- Targeted queue/throttling/search/rebuild/UI acceptance: **15/15 PASS**.
- Iteration 51 static contract: **25/25 PASS**.
- Architecture: **PASS**.
- Implementation completeness: **PASS**.
- Language catalogues: **PASS** (UK/EN/BG, 844 UI keys each).
- XML/archive security + supply chain + static release: **PASS**.
- Full offline Maven reactor: **13/13 modules BUILD SUCCESS**.
- Full Surefire inventory: **871 tests; 0 failures; 0 errors; 12 skipped**.
- UI module: **85/85 PASS**.
- Infrastructure module: **396 tests; 0 failures/errors; 7 skipped**.
- E2E: **14/14 PASS**.
- `git diff --check`: **PASS**.

## 7.4 local status after Iteration 51

MHL-301, MHL-302, MHL-303, MHL-304, MHL-305, MHL-306 and MHL-307 are locally DONE. Search Everywhere 7.4 is therefore locally complete.

External release acceptance MHL-010/011/012/017/018/019 remains OPEN and is not closed by this local iteration.
