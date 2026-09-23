# MyHomeLib documentation

## Active project contract

The six active project documents intentionally remain in the repository root because build/release checks and support diagnostics treat them as the canonical contract:

- `README.md` — entry point and build/run overview;
- `ARCHITECTURE.md` — architecture and dependency rules;
- `MYHOMELIB-FEATURES.md` — supported features and deliberate limits;
- `MYHOMELIB-OPERATIONS.md` — runtime/operations/backup/upgrade guidance;
- `MYHOMELIB-DEVELOPMENT.md` — development, testing and validation rules;
- `MYHOMELIB-RELEASE.md` — release policy and current release boundary.

## Current release documents

- `release/CURRENT-VALIDATION.md` — exact local validation state;
- `release/EXTERNAL-ACCEPTANCE-RUNBOOK.md` — canonical live acceptance procedure;
- `release/EXTERNAL-TEST-PLAN.md` — current six-gate external test plan;
- `release/WHAT-REMAINS-UA.md` — concise Ukrainian list of external work still required;
- `release/DEVELOPER-SOURCE-PACKAGE-UA.md` — developer-source package contents;
- machine-readable performance/baseline JSON files remain in `release/` because validation tools consume them.

## Plugin SDK

See `plugin-sdk/README.md` and `plugin-sdk/COMPATIBILITY.md`.

## Performance notes

`smart-collections-performance.md` and `performance-baseline.json` are retained as focused performance evidence/reference material.

## History

- `history/HISTORY.md` — the single canonical human-readable project history;
- `history/records/` — exact iteration/checkpoint/task evidence;
- `history/source-notes/` — original legacy notes retained for traceability.

Historical files are not active specifications. Do not duplicate new release instructions into history; update the active root/release documents instead.
