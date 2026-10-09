# MyHomeLib — Next Tasks after V25

Дата: 2026-09-29.  
Continuation source: V25 clean checkpoint.

## P0 — Windows x64 Calibre acceptance

1. Verify supplied Calibre/libmobi versions/provenance on Windows.
2. Large supplied FB2 → EPUB beyond five minutes; no 300 s timeout; output valid/openable.
3. Confirm `mhl-calibre-*` cleanup after success.
4. Cancel active Calibre: `CANCELLED`, full process-tree exit, no error dialog, no locked `input.fb2`, workspace immediately removable.
5. Close application during active conversion: no orphan process/lock.
6. Batch ≥3 books: observed external concurrency ≤2; queued work does not start after Cancel; partial failure preserves successful outputs; Reader/download/navigation coexist.

No sleeps/timeouts are to be increased without Windows failure evidence + regression model.

## P1 — Windows release/platform acceptance

- exact candidate MSI/EXE/portable lifecycle and real previous-version upgrade;
- DPI 100/125/150/200 on bound host/user/session;
- packaged launcher/runtime-tool smoke, non-ASCII paths, DPAPI;
- plugin installed lifecycle and Reader smoke;
- real device/eject, external readers, system TTS/FFmpeg where release scope requires them.

## P1 — Connected/live acceptance

- MHL-010 exact-candidate GitHub required checks;
- MHL-017 CycloneDX SBOM;
- MHL-018 dependency/SCA blocking evidence;
- MHL-019 CodeQL evidence;
- live WebDAV/provider/external OPDS-MCP interoperability only where required by release scope;
- signing/SmartScreen/distribution acceptance.

## P2 — Release closure

Only after P0/P1 evidence is green: freeze candidate, run the authoritative build/package flow on the release environment, bind all evidence to exact hashes, generate final binary/source `SHA256SUMS`, and finalize immutable reviewer evidence.

## Guardrails

- defect: red regression/evidence → minimum fix → targeted/downstream tests;
- refactor one responsibility boundary at a time;
- performance/memory only same-environment A/B;
- do not reopen green architecture/Organizer/conversion/UI contracts without concrete regression;
- keep historical descriptive files under `docs/history`, active contracts under `docs/current`, release evidence under `docs/release`;
- UI action inventory and user-function documentation mapping must remain fail-closed.
