# Iteration 81 — Windows export/TTS usability hotfix

Date: 2026-09-13

## Trigger

Real Windows testing after Iteration 80 exposed two user-visible defects:

1. export logged `format FB2_ZIP` but committed a plain `.fb2` because the resolver searched all direct fallback artifacts before attempting conversion for the explicitly selected format;
2. the Reader voice chooser displayed provider implementation-object identities instead of readable voice names.

## Fix

- Export resolution now walks preferred formats in order and resolves each format as: direct artifact -> converter -> legacy raw fallback.
- `FB2_ZIP` therefore invokes its converter before a later `FB2` fallback and commits `.fb2.zip`.
- The Reader voice chooser uses explicit display-name/language strings, with deterministic suffixes for duplicate labels, and no longer depends on provider-object `toString()`.

## Regression evidence

- Application: 287 tests, 0 failures, 0 errors, 1 skipped.
- Infrastructure exhaustive split: 433 tests, 0 failures, 0 errors, 7 skipped.
- UI: 95/95.
- E2E: 14/14.
- Production ZIP converter + System TTS targeted tests: 4/4.
- Full Spring context: 1/1.
- Full exhaustive split total: 1,042 tests, 0 failures, 0 errors, 12 skipped.
- Architecture/completeness/localization/static-release/supply-chain/Spring-wiring/proxyability gates: PASS.

## External boundary

Production source changed, so Iteration 80 candidate evidence is invalidated. MHL-010/011/012/017/018/019 remain OPEN_EXTERNAL and must be rerun against the exact Iteration 81 SHA.
