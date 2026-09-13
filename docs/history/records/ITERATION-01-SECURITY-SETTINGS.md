# Iteration 01 — OPDS credentials and online transport security

Implemented backlog items:
- MHL-002 — OPDS passwords are persisted as versioned PBKDF2-HMAC-SHA256 hashes with unique salt.
- MHL-003 — legacy plaintext `opds.password` is migrated on load using an atomic namespace replacement.
- MHL-004 — OPDS settings are saved through one `replaceByPrefix("opds.", ...)` operation.
- MHL-033 — Basic Auth credentials for online collections are blocked on plaintext HTTP before a network request.

Compatibility notes:
- Runtime OPDS settings may still accept a plaintext password supplied directly by code/tests; persisted settings are migrated/hashed.
- The OPDS UI no longer displays the stored password representation. Leaving the password field blank preserves an existing hash while authentication remains enabled.

Verification:
- Reactor compile for application/infrastructure/opds/ui and dependencies: PASS.
- `OpdsSettingsServiceTest`: PASS.
- `CredentialTransportPolicyTest`: PASS.
- `HttpOnlineBookDownloadAdapterTest`: PASS.
- `HttpRemoteCatalogDownloadAdapterTest`: PASS.
- `JdkOpdsServerTest`: PASS.
