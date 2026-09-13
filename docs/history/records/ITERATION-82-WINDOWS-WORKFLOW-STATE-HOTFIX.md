# Iteration 82 — Windows workflow/state hotfix

Date: 2026-09-13

## Real-host defects addressed

1. Windows TTS voice names could still arrive as mojibake because PowerShell redirected stdout used a legacy console/OEM code page. Voice discovery now uses ASCII-safe UTF-8 Base64 transport and Java-side decoding; malformed rows are discarded.
2. Successful mass export of remote/not-yet-local books did not update the visible local/downloaded state and did not clear consumed checkbox selection. Successful completion now clears only the exported selection and refreshes the active workspace; Author Workspace preserves author context and restores the selected book when still visible. Failed/cancelled exports keep selection for retry.
3. The global Annotations/Notes command could bypass active Reader cleanup. Navigation now releases the Reader before opening Annotation Manager and the order is regression-tested.

## Validation

- Full exhaustive split: **1,048 tests, 0 failures, 0 errors, 12 skipped**.
- Application: 287/0/0/1; Infrastructure: 436/0/0/7; UI: 98/98; Reader: 77/0/0/1.
- Bootstrap 18/18; OPDS 20/20; E2E 14/14; Architecture 14/14; full Spring context 1/1 PASS.
- TTS targeted provider regressions: 6/6 PASS.
- Mass-export completion + annotation-navigation targeted regressions: 6/6 PASS.

## External boundary

Production source changed. Iteration 81 candidate-bound evidence is invalid for final acceptance. MHL-010/011/012/017/018/019 remain OPEN_EXTERNAL and must be rerun on the exact Iteration 82 candidate SHA.
