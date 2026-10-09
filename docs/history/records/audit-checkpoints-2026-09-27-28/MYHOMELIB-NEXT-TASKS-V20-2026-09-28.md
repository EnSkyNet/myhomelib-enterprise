# MyHomeLib — Next Tasks after V20

Date: 2026-09-28  
Use `myhomelib-enterprise-optimized-v20-checkpoint-2026-09-28.zip` as the only baseline for the next session.

## P0 — Windows x64 / real Calibre acceptance

Linux modeling now covers descendant-wait semantics and V20 removes the stale plugin-local process-tree implementation. This does **not** replace Windows acceptance.

Validate V20 on Windows x64 with real Calibre installed. Capture process tree and logs before changing code for any failure.

Required acceptance:

- long FB2 → EPUB conversion remains responsive;
- unrelated navigation, Reader state and online download remain usable during conversion;
- Operation Center Cancel signals cancellation exactly once;
- operation remains non-terminal until worker/process cleanup completes;
- terminal outcome is `CANCELLED`, not `FAILED`, and no conversion error dialog is shown;
- timeout/cancel terminates the complete Calibre child process tree;
- temporary `mhl-calibre-*` / conversion workspaces are removable immediately after termination;
- no stale `CONVERSION` lifecycle lease remains;
- application shutdown during conversion leaves no locked input/staging file and no orphan Calibre process.

For a Windows-only failure: deterministic regression model first where possible, then smallest production fix, then targeted + Windows acceptance rerun.

## P0 — formal release libmobi input

Formal full desktop release still requires verified bundled libmobi 0.12 / `mobitool` plus its license.

Current V20 source intentionally lacks `tools/converters/libmobi/build-src`; strict runtime-tool preparation therefore exits fail-closed.

Next action:

1. restore/supply the verified licensed libmobi release input;
2. run strict runtime-tool preparation;
3. build platform package;
4. run packaged runtime-tool smoke;
5. build/run portable smoke;
6. generate `SHA256SUMS`;
7. run Stage23 with `--require-checksums --require-portable`;
8. run applicable Windows installer acceptance.

Do not weaken the strict script to make this gate green without the required artifact/license input.

## P1 — remaining correctness audit

V20 deterministic characterization is green for collection-switch barriers, cancellation outcome propagation and current file-publication race tests. Continue defect-first in this order:

1. real Windows destination replace/move/cleanup behavior after cancellation and timeout;
2. shutdown barriers around external-process operations, especially process-tree exit before temporary workspace cleanup;
3. exceptional Operation Center/cancellation-action cleanup only where a stale row/action can be reproduced;
4. queued import/switch interaction only if a user-visible race can be reproduced beyond the existing expected-collection fail-safe.

Do not broaden lifecycle/coordinator semantics merely because shared operation aggregation could be modeled differently; require a concrete incorrect observable result first.

## P2 — architecture debt

Triage only after P0/P1 correctness acceptance:

- `DeferredMainFeatureActions`;
- `ReaderBookMapCoordinator`;
- `ReaderBookmarkCoordinator`.

For each proposed change: characterization → one responsibility boundary → targeted tests → full relevant module → architecture/static check. No class-size-only refactor and no ratchet gaming.

## P2 — packaging/release follow-through

Policy remains unchanged:

- publishable release artifacts require `SHA256SUMS` and strict Stage23;
- intermediate app-image validation may run without bundled libmobi;
- formal full release requires verified bundled libmobi plus license;
- configured/system `mobitool` is a runtime fallback, not a substitute for formal release input.

## P3 — maintenance tooling

Run standalone tools only when relevant to an active gate. Repair only reproducible failures with a current purpose; do not launch a broad old-tool cleanup campaign.

## P3 — performance/memory

No performance or memory change without a reproducible baseline and A/B measurement on the same workload/environment. Functional test counts, Maven timings and code-size changes are not performance evidence.
