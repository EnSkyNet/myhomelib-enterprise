# MyHomeLib — Remaining External Acceptance after V21

Date: 2026-09-28  
Use `myhomelib-enterprise-optimized-v21-checkpoint-2026-09-28.zip` as the only baseline for any further work.

## Status

The code-controlled V20 audit backlog is closed in V21. The named architecture debt group is resolved, full Maven 3.9.16 verification is green, and Linux production/package/portable/checksum/Stage23 gates are green.

Do not continue proactive refactoring. Further source changes require a concrete reproducible defect from the remaining external acceptance gates or a newly reported independent defect.

## P0 — Windows x64 + real Calibre acceptance

Run V21 on a real Windows x64 environment with a supported Calibre installation. Capture logs and the process tree before changing source for any failure.

Acceptance requirements:

- long FB2 → EPUB conversion stays responsive;
- unrelated navigation, Reader state and online download stay usable during conversion;
- Operation Center Cancel signals cancellation exactly once;
- operation remains non-terminal until worker/process cleanup completes;
- terminal outcome is `CANCELLED`, not `FAILED`, with no cancellation error dialog;
- timeout/cancel terminates the complete Calibre child process tree;
- temporary conversion workspaces are removable immediately after termination;
- no stale `CONVERSION` lifecycle lease remains;
- application shutdown leaves no locked input/staging file and no orphan Calibre process;
- Windows destination replace/move/cleanup succeeds after cancellation and timeout;
- packaged Windows launcher/installer and portable acceptance succeed.

If a Windows-only failure is reproduced: regression model first where practical → smallest production fix → targeted tests → full relevant module → Maven 3.9.16 reactor → Windows acceptance rerun.

## P0 — verified libmobi 0.12 release input

Supply the verified licensed libmobi 0.12 source/runtime input expected at `tools/converters/libmobi/build-src`, including the required license file.

Then execute the existing strict release path without weakening it:

1. strict runtime-tool preparation with `MHL_REQUIRE_LIBMOBI=1`;
2. platform package build;
3. packaged runtime-tool smoke;
4. portable build and smoke;
5. generate `SHA256SUMS`;
6. Stage23 with `--require-checksums --require-portable`;
7. applicable Windows installer/portable acceptance.

Configured/system `mobitool` remains a runtime fallback for intermediate use, not a substitute for the verified bundled full-release input.

## After both P0 gates

If both external gates pass without defects, no further audit iteration is required: promote the verified V21 source state/artifact set according to the release process.

If either gate exposes a defect, create the next checkpoint only after the defect follows the established RED regression → minimal fix → targeted/downstream/full acceptance sequence.

## Performance/memory

No performance or memory change without a reproducible baseline and A/B measurement on the same workload and environment. Do not use Maven timing, test count or source-size differences as performance evidence.
