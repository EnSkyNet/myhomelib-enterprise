# Iteration 50 — Background Content Indexing Queue + Resource Throttling

**Date:** 2026-09-12  
**Scope:** MHL-303, MHL-307  
**Status:** DONE (local technical closure)

## Delivered

- Persistent priority queue for content-index work with HIGH/NORMAL/LOW ordering.
- Non-blocking enqueue/control API; extraction/index writes execute on daemon workers.
- Durable per-collection checkpoint with restart restore of unfinished tasks.
- Pause/resume/cancel and superseding of older pending work for the same artifact.
- Startup restoration for the active collection after migration/search startup.
- Eco / Balanced / Fast resource profiles with bounded worker count and I/O rate.
- Optional pause-on-battery policy with cached asynchronous power-state detection.
- Persisted indexing profile and battery preference exposed in Application Settings.
- Bounded/rate-limited file input for content extraction; cancellation checked during I/O throttling.
- Shutdown/cancel race hardening: final checkpoint cannot be overwritten by an older async write; successful cancellation cannot spuriously report false.

## Acceptance / validation

- Iteration 50 static contract: **11/11 PASS**.
- Targeted MHL-303/MHL-307 tests: **9/9 PASS** (application + infrastructure) plus startup/orchestrator acceptance.
- Queue race repetition: **12 consecutive ContentIndexingQueueServiceTest runs PASS**.
- Balanced benchmark: **300/300 tasks completed**, worker limit **2**, observed max active **2**, p95 enqueue **167 µs**, total **389 ms**.
- Full offline Maven reactor: **13/13 modules BUILD SUCCESS**.
- Full test inventory: **864 tests**, **0 failures**, **0 errors**, **12 skipped**.
- Static/architecture/completeness/localization/supply-chain/XML-security/release gates: **PASS**.
- `git diff --check`: **PASS**.

## Remaining 7.4 work

- MHL-304 — Search inside all books UI (Metadata / Contents / Both, snippets, Reader jump, cancellation).
- MHL-305 — Content-index health/rebuild UI.

External release acceptance MHL-010/011/012/017/018/019 remains OPEN and is not closed by this local iteration.
