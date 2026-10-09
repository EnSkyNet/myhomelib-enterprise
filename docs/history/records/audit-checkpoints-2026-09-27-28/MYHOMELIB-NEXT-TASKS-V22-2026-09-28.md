# MyHomeLib — Next Tasks after V22

Date: 2026-09-28  
Use `myhomelib-enterprise-optimized-v22-checkpoint-2026-09-28.zip` as the **only baseline**.

## Supplied acceptance inputs

Keep these as external test/release inputs; do not silently copy them into source control:

- `729586 Демон. Пожиратель душ #01-18.fb2.zip`  
  SHA-256: `8cbc2ea9d148df24034aea475d394414a026d5e520c6858f839735db07f302b1`
- `tools.zip`  
  SHA-256: `46340ce33280b2768896c2790e4f9a2a8253ab87202eb965c18a37ad7f320cce`
- `myhomelib.log`  
  SHA-256: `6297a7aaa34b83dbcc0d527a61366ea99e220b6880e3b68d04fd9f829243aa4a`

`tools.zip` contains the Windows Calibre bundle including `tools/converters/Calibre/ebook-convert.exe` and `Calibre/LICENSE`, plus `tools/converters/libmobi/mobitool.exe` and `libmobi/COPYING`.

## What V22 already changes

Do not reimplement these unless acceptance proves a regression:

- Calibre default timeout: 1800 s;
- exact historical `300` setting is treated as the legacy default and upgraded effectively to 1800 s;
- explicit non-300 custom timeout is preserved, bounded to 10…3600 s;
- process termination grace: 10 s;
- checkbox-selected books are used for batch conversion; empty selection falls back to current row;
- max batch concurrency: 2 external conversions;
- queued batch items do not start after Cancel;
- batch format list is the intersection of available formats across selected books.

## P0 — real Windows large-FB2 acceptance

Run on Windows x64 using the supplied `tools.zip` Calibre bundle and the exact supplied large FB2.

1. Verify the bundle first with `tools/check-windows-converter-bundle.ps1` and record the Calibre version.
2. Start V22 with a clean or known settings profile. Confirm the effective Calibre timeout is 1800 s when no custom value exists or when the stored legacy value is exactly 300.
3. Convert the supplied `Демон. Пожиратель душ #01-18` FB2 to EPUB.
4. The job must be allowed to run beyond five minutes without a 300 s timeout.
5. On success, verify the EPUB opens and the published artifact is non-empty/valid.
6. Verify the corresponding `mhl-calibre-*` workspace disappears after completion.
7. Repeat and press Cancel while Calibre is active:
   - Operation Center ends as `CANCELLED`, not `FAILED`;
   - no conversion-error dialog;
   - complete Calibre process tree exits;
   - `input.fb2` is not locked afterward;
   - temporary workspace is removable immediately after teardown.
8. Repeat once more and close the application while conversion is active. No orphan Calibre process and no locked staging/input file may remain.

If a Windows-only failure appears: **capture process tree + application log first**, then regression test/model → minimum fix → targeted tests → Windows rerun. Do not increase timeouts or sleeps blindly.

## P0 — real batch conversion acceptance

Use checkbox selection in the normal book list.

1. Select at least 3 books, including the supplied large FB2 and at least two ordinary books.
2. Invoke Convert while a row is under the cursor.
3. Confirm the dialog reports the checkbox-selection count, not merely the current row.
4. Confirm only formats supported by every selected book are offered.
5. Start the batch and confirm at most **2** external converter processes/jobs are active concurrently.
6. Confirm unrelated navigation/Reader state remains responsive.
7. Confirm each successful book publishes its own output.
8. Cancel a batch with queued items:
   - active work is cancelled;
   - queued items do not start;
   - Operation Center reports cancellation only after active process cleanup.
9. Test a partial failure (one intentionally invalid/unavailable source if practical): successful books must remain published and the summary must report successes/failures without corrupting the batch state.
10. Test empty checkbox selection: Convert must continue to operate on the current row exactly as before.

## P1 — operation coexistence on Windows

The old V20 log showed Reader/download attempts blocked while `CONVERSION` was active. V21+ contains shared-compatibility support for `CONVERSION` with reader state/download, so verify the real packaged V22 behavior:

- open another local book while conversion runs;
- download/open an unrelated remote book while conversion runs;
- navigate authors/books/Operation Center during conversion.

If a conflict still appears, record the exact requested/root `LibraryOperationType` pair before changing the compatibility matrix.

## P1 — tools/release input

Because `tools.zip` now supplies Windows Calibre and libmobi artifacts with license files:

1. verify executable versions on Windows;
2. verify provenance/licensing expectations for the intended release;
3. decide whether they remain external packaging inputs or are staged by the release process;
4. do not commit large third-party binaries into the clean source ZIP unless release policy explicitly requires it;
5. once accepted, run packaged converter smoke and Stage23 for the Windows artifact.

## P2 — final release gate after Windows acceptance

If P0/P1 are green, do not refactor further. Run only release closure:

- Maven 3.9.16 full `clean verify`;
- compare with V22 reference: **1815 tests, 0 failures, 0 errors, 17 skipped**;
- production package;
- platform package / installer;
- packaged launcher smoke;
- packaged Calibre + libmobi runtime-tool smoke where applicable;
- generate `SHA256SUMS`;
- strict Stage23;
- produce a new clean checkpoint only if Windows acceptance required source changes. If no source change was needed, V22 remains the final source baseline.

## Guardrails

- Defect: regression test first → minimum fix → targeted/downstream tests.
- Refactor: one responsibility boundary at a time; none is currently requested.
- Performance/memory: no claim or change without same-environment A/B measurement.
- Do not mask Windows process/file-lock failures by adding arbitrary retries/sleeps.
- Do not reopen completed architecture work without a concrete regression.
