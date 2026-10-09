# MyHomeLib — Next Tasks after V23

Date: 2026-09-29  
Use the new V23 clean source checkpoint as the continuation source. The original V22 checkpoint remains the only baseline from which V23 was derived.

## P0 — Windows x64 large-FB2 Calibre acceptance

Use the supplied `tools.zip` and exact supplied `Демон. Пожиратель душ #01-18.fb2.zip`.

1. Run `tools/check-windows-converter-bundle.ps1`; record the real Calibre and libmobi versions.
2. Start the V23 source build/package with a clean or known settings profile.
3. Confirm effective Calibre timeout is 1800 s for no custom value and stored legacy `300`; preserve any explicit non-300 custom value within 10…3600 s.
4. Convert the supplied large FB2 to EPUB and allow it to run beyond five minutes; no 300 s timeout may occur.
5. Validate the published EPUB is non-empty and opens correctly.
6. Confirm the corresponding `mhl-calibre-*` workspace is removed after success.
7. Cancel while Calibre is active:
   - Operation Center ends `CANCELLED`, not `FAILED`;
   - no conversion-error dialog;
   - complete Calibre process tree exits;
   - `input.fb2` is not locked;
   - workspace is immediately removable after teardown.
8. Repeat and close MyHomeLib while conversion is active; no orphan Calibre process or locked staging/input file may remain.

If Windows exposes a failure, first capture process tree + application log, then apply: regression test/model → minimum fix → targeted/downstream tests → Windows rerun. Do not add arbitrary sleeps/timeouts.

## P0 — real Windows batch conversion acceptance

1. Checkbox-select at least 3 books: the supplied large FB2 plus at least two ordinary books.
2. Invoke Convert while another/current row is under the cursor; dialog count must reflect checkbox selection.
3. Offered target formats must be the intersection supported by every selected book.
4. During execution, confirm no more than **2** external converter processes/jobs are active.
5. Navigate authors/books/Operation Center and open an unrelated local book while conversion runs.
6. Verify every successful book publishes its own output.
7. Cancel with queued items: active work is cancelled, queued items never start, final cancellation is reported only after active cleanup.
8. Exercise one partial failure; successful outputs remain published and batch summary reports success/failure counts consistently.
9. With no checkbox selection, Convert must still operate on the current row exactly as before.

## P1 — Windows operation coexistence

While conversion is active:

- open another local book;
- download/open an unrelated remote book;
- navigate authors/books/Operation Center.

If a conflict occurs, record the exact requested/root `LibraryOperationType` pair before changing the compatibility matrix.

## P1 — release input / provenance

- verify real Windows executable versions from the supplied Calibre/libmobi bundle;
- confirm intended provenance/licensing/re-distribution policy for the release;
- keep large third-party binaries outside the clean source ZIP unless release policy explicitly requires otherwise;
- after Windows acceptance, run packaged converter/runtime smoke on the exact Windows artifact.

## P1 — audit/CI environment contract

For Linux CI/audit runs:

- use a UTF-8 native/JNU locale (`C.UTF-8` or equivalent), not POSIX/ASCII;
- provide Xvfb/display for JavaFX tests;
- keep `github-connected-acceptance-test.py` freshness fixtures relative to test execution time; do not reintroduce fixed dates for a live-age policy.

## P2 — final release closure only after P0/P1 are green

- Maven 3.9.16 full `clean verify` and compare with V23 local reference: **1815 tests / 0 failures / 0 errors / 17 skipped / 20 modules**;
- production package;
- Windows platform package/installer;
- packaged launcher smoke;
- packaged Calibre + libmobi runtime-tool smoke;
- generate release `SHA256SUMS`;
- strict Stage23 artifact validation;
- run exact-candidate GitHub connected acceptance and collect MHL-010/017/018/019 evidence;
- complete MHL-011/012 on the bound Windows host/session;
- finalize the immutable reviewer evidence bundle.

## Guardrails

- Defect: regression test/evidence first → minimum fix → targeted/downstream tests.
- Refactor: one responsibility boundary at a time; no production refactor is currently justified.
- Performance/memory: no change or claim without same-environment A/B measurement.
- Do not mask Windows process/file-lock failures with retries or arbitrary sleeps.
- Do not reopen V22 conversion semantics or completed architecture work without a concrete regression.
- Keep audit/change/task checkpoint documents under `docs/history/records/`, not repository root.
- Validate the final source ZIP itself for unsafe paths and forbidden generated directories; do not assume a pre-check clean staging tree stays clean after Python gates.
