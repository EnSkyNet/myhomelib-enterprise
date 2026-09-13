# Iteration 55 — Sync conflict resolution + encryption-key rotation

Date: 2026-09-12
Status: DONE (local)

## Closed backlog

- **MHL-404** — Conflict resolution engine. Policies: furthest reading progress; deterministic latest scalar state; safe three-way annotation merge/tag union; live-vs-tombstone and unresolved same-field/group conflicts require manual review. JavaFX review UI shows both snapshots and returns an explicit side choice through application DTOs; UI does not depend on `SyncRecord`.
- **MHL-405** — Encrypt sync payload hardening. AES-256-GCM envelope v2 includes authenticated key id; key ring supports active + one migration key; SecretStore rotation blocks a second rotation until completion; legacy v1/previous-key bundles are readable only during migration; WebDAV rewrap migrates remote bundles to the active key before old-key deletion.

## Acceptance

- MHL-404 targeted: 9/9 PASS.
- MHL-405 targeted: 11/11 PASS.
- Architecture baseline: PASS (UI debt ratchet improved to 24/28 non-value domain-model users).
- Implementation completeness: PASS.
- SecretStore policy: PASS.
- XML/archive security: PASS.
- UK/EN/BG language catalogues: PASS.
- Static release check: PASS.
- Full offline reactor: 13/13 modules BUILD SUCCESS.
- Surefire aggregate: **907 tests, 0 failures, 0 errors, 12 skipped**.
- E2E: 14/14 PASS.

External MHL-010/011/012/017/018/019 remain OPEN and require real Windows/GitHub evidence.
