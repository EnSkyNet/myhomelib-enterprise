# MyHomeLib — Next Tasks after V24

Date: 2026-09-29  
Use the V24 clean source checkpoint as the continuation source.

## P0 — Windows x64 conversion acceptance

Use the supplied `tools.zip` and exact supplied large FB2.

1. Verify actual Calibre/libmobi versions with the Windows bundle checker.
2. Confirm effective timeout 1800 s for default/legacy-300 and preservation of explicit non-300 custom settings within 10…3600 s.
3. Convert the supplied large FB2 to EPUB and allow execution beyond five minutes.
4. Validate the output opens, is non-empty, and the `mhl-calibre-*` workspace is removed.
5. Cancel active conversion: final state `CANCELLED`, no error dialog, full process tree exits, no locked `input.fb2`, workspace removable.
6. Close MyHomeLib during active conversion: no orphan process or locked staging/input file.

On failure: capture process tree + application log first; then regression test/model → minimum fix → targeted/downstream tests → Windows rerun. Do not add arbitrary sleeps/timeouts.

## P0 — real Windows batch conversion acceptance

- select at least 3 books with checkboxes, including the supplied large FB2;
- dialog count must follow checkbox selection, not cursor row;
- offered formats must be the common intersection;
- observe maximum 2 external conversions active;
- verify navigation/Reader/Operation Center coexistence;
- verify each successful output is published;
- cancel with queued work and confirm queued items never start;
- exercise one partial failure without corrupting successful outputs/batch summary;
- verify current-row fallback when checkbox selection is empty.

## P1 — interactive/platform user-function acceptance

Use `docs/release/USER-FUNCTIONS-V24-UA.md` as the checklist and record LIVE/WIN evidence for environment-dependent paths:

- Windows DPI 100/125/150/200;
- installer/MSI/EXE/portable lifecycle and upgrade;
- external reader command execution;
- real send-to-device/eject;
- system TTS voices and FFmpeg-dependent audio behavior;
- real WebDAV endpoint/conflict workflow;
- real online metadata/translation/AI provider credentials;
- representative external OPDS and MCP clients.

Do not turn an unavailable live dependency into a synthetic PASS.

## P1 — connected release gates

Collect exact-candidate evidence for MHL-010/017/018/019 and Windows-bound MHL-011/012, then finalize the immutable reviewer evidence bundle.

## P2 — release closure

Only after P0/P1 are green:

- full Maven 3.9.16 `clean verify`; compare against V24 reference **1817 tests / 0 failures / 0 errors / 17 skipped / 20 modules** unless legitimate new tests change the count;
- production/Windows packages;
- packaged launcher and runtime-tool smoke;
- `SHA256SUMS`;
- strict Stage23 artifact validation;
- exact-candidate connected acceptance and final evidence bundle.

## Guardrails

- defect: regression evidence/test first → minimum fix → targeted/downstream tests;
- refactor: one responsibility boundary at a time;
- performance/memory: only same-environment A/B measurement;
- do not add shallow tests solely to make direct-controller mapping percentages look like 100%; test observable behavior and risks;
- keep LIVE/WIN status explicit until real evidence exists;
- do not reopen stable V22/V23 conversion or architecture behavior without a concrete regression.
