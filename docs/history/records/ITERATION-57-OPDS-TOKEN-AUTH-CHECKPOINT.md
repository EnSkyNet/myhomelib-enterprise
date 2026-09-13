# Iteration 57 — OPDS scoped token authentication checkpoint

Date: 2026-09-12
Status: DONE locally
Backlog item: MHL-407

## Delivered

- High-entropy `mhl_...` Bearer tokens with separate `CATALOG_READ` and `DOWNLOAD` scopes.
- Optional device name and public token metadata: created, last-used and revoked timestamps.
- Only SHA-256 token hashes and non-secret metadata are persisted; the raw token is returned only from create and is shown once in the OPDS settings UI.
- OPDS settings can create, list/refresh and revoke tokens; revocation requires explicit confirmation.
- Revoked tokens fail on the next request and insufficient scope returns Bearer `insufficient_scope` instead of falling back to Basic authentication.
- Existing Basic authentication remains compatible.
- Token UI is localized in UK/EN/BG and bundled catalogues remain byte-synchronized with root language files.

## Validation

- `OpdsAccessTokenServiceTest`: 4/4 PASS.
- `JdkOpdsServerTest`: 11/11 PASS, including Bearer authentication, scope enforcement, last-used and immediate revocation.
- `OpdsTokenUiContractTest`: 2/2 PASS; repeated after the final UI robustness patch: 2/2 PASS.
- `architecture-check.py`: PASS.
- `implementation-completeness-check.py`: PASS.
- `secret-store-policy-check.py`: PASS.
- `check-critical-ui-localization.py`: PASS.
- `git diff --check`: PASS.

## Boundary

MHL-407 is locally DONE. A full 13-module reactor was intentionally not run in this short cycle to avoid a long blocking response; the latest full-reactor baseline remains Iteration 55 (13/13 modules, 907 tests). MHL-408/409/410 remain OPEN. External MHL-010/011/012/017/018/019 remain OPEN pending real Windows/GitHub evidence.
