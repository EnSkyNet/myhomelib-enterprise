# Current validation status

**Current local source candidate:** MyHomeLib 8.0.0 / FINAL AUDITED local source checkpoint  
**Date:** 2026-09-18  
**Status:** **LOCAL PASS — EXTERNAL RELEASE GATES STILL OPEN**

## Exact final-code validation

The final local code state was validated after the deep audit with one uninterrupted production reactor run:

- Maven `clean verify -Pproduction`: **17/17 modules BUILD SUCCESS**;
- Surefire/Failsafe aggregate: **400 suites / 1189 tests / 0 failures / 0 errors / 12 skipped**;
- JavaFX dependency line: exact production **21.0.12** on Linux x86-64, with no 21.0.2 override;
- desktop localization: **3352 synchronized UK/EN/BG keys**;
- Web/OPDS localization: **74 synchronized keys**;
- implementation completeness: **1125 production Java files**, **0 TODO/FIXME**, **0 unused explicit imports**, **0 unused Spring constructor dependencies**, **0 exact cross-file clones** at the controlled threshold;
- architecture, Spring constructor wiring/proxyability, managed executors, user-data consistency, privacy/temp lifecycle, XML/archive security, online-update rollback, collection/delete safety and content-index health/static gates: **PASS**.

The 12 skipped tests are expected platform- or opt-in-gated tests (for example Windows-only integration and real-data/performance probes). They are **not** treated as Windows acceptance evidence.

## Deep-audit remediation included in this checkpoint

The final audited tree includes fixes and regression coverage for, among other items:

- AI/bootstrap/plugin localization and synchronized UK/EN/BG help/catalogues;
- Spring injection ambiguity in OpenAI and import/index wiring;
- source-aware metadata semantics, including INPX `DATE` vs publication year and nullable/stable `updateDate` handling;
- crash-safe INPX export and backup generation publication with manifest/SHA-256 verification;
- backup/restore self-overwrite/alias protection and truthful partial-success reporting for derived-index rebuild failures;
- bounded local/WebDAV sync payloads, HTTP provider responses, settings/recovery/checkpoint files and certificate/secret inputs;
- safe cross-platform filenames, including Windows reserved names and UTF-8 byte-length limits;
- external-process tree cleanup, bounded diagnostics and the Calibre double-execution defect;
- transactional batch delete and consistent local-artifact state changes;
- durable/background full-text indexing, persisted `rebuildRequired` state, fail-closed stale handling, collection-switch isolation and collection-delete cleanup;
- legacy pseudo-author cleanup/migration and stable sort-key recalculation;
- PDF/Comic render queue cleanup and Reader error-boundary hardening.

## Historical evidence reference

Historical Iteration 84/85 reports, changelogs and test evidence are consolidated in `docs/history/HISTORY.md`. Exact lower-level checkpoint evidence remains under `docs/history/records/`. Older dates/counts inside history are intentionally preserved because they describe earlier snapshots, not the current validation summary.

The final 2026-09-18 `clean verify` did not rerun opt-in real-data/performance probes unless enabled by their `mhl.real.*`, `mhl.performance` or benchmark system properties. Historical performance/corpus results therefore remain evidence for the recorded snapshot, not a new exact-final-code performance claim.

## External release acceptance still required

Local Linux/offline PASS does not close the external gates. Before public Windows release, complete the current runbook/evidence flow for:

- Windows 10/11 x64 installer/app-image/EXE lifecycle, real previous-version upgrade, DPI 100/125/150/200 and DPAPI integration;
- real Calibre `ebook-convert` smoke paths;
- real WebDAV, configured AI provider, third-party Plugin SPI, LAN OPDS client and target-device export as applicable;
- exact-candidate GitHub CI/required checks, SBOM/SCA and CodeQL evidence;
- Authenticode/SmartScreen validation and public-artifact checksums;
- recommended production soak on a large real library.

The candidate-bound external gates remain open individually: `MHL-010`, `MHL-011`, `MHL-012`, `MHL-017`, `MHL-018`, and `MHL-019` are all `OPEN_EXTERNAL` until their required real evidence exists. See `docs/release/WHAT-REMAINS-UA.md`, `docs/release/EXTERNAL-TEST-PLAN.md` and `docs/release/EXTERNAL-ACCEPTANCE-RUNBOOK.md`.
