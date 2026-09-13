# Iteration 83 — Windows TTS discovery + Annotation Manager FXML hotfix

Date: 2026-09-13

## Real-host evidence

- Windows TTS voice chooser displayed PowerShell parser diagnostics/script fragments instead of only installed voices.
- Annotations/Notes failed to load `annotation-manager-workspace.fxml` because JavaFX 21 could not coerce `CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN` from a string to `Callback`.

## Fixes

- TTS voice discovery uses PowerShell `-EncodedCommand`; valid rows are strict `MHLVOICE|<UTF-8 Base64>|<locale>` records.
- stderr is no longer merged into stdout; non-zero discovery exit fails closed.
- Annotation Manager table resize policy is assigned programmatically, not through an invalid FXML string.
- Added TTS diagnostic-noise/non-zero-exit regressions and Annotation Manager FXML contracts.

## Acceptance boundary

This is a production-source change. Iteration 82 candidate-bound evidence is invalid for final acceptance. MHL-010/011/012/017/018/019 remain OPEN_EXTERNAL.
