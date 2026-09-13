# Tasks — Iteration 53

- [x] Implement WebDAV transport over JDK HTTP boundary.
- [x] Enforce HTTPS and reject URL-embedded credentials.
- [x] Persist endpoint credentials and shared sync key through SecretStore.
- [x] Encrypt remote sync bundles with authenticated AES-256-GCM.
- [x] Publish through deterministic `.part` + WebDAV MOVE.
- [x] Retry transient 408/425/429/5xx and I/O failures.
- [x] Parse PROPFIND via SecureXmlInputFactory; reject DTD/XXE.
- [x] Reject cross-origin/path-escape href values before sending credentials.
- [x] Two-device cursor/convergence targeted test.
- [x] Targeted WebDAV tests 9/9 PASS.
- [x] Static/security/architecture checks PASS.
- [ ] Full 13-module reactor regression on exact Iteration 53 state.
- [ ] Mark MHL-402 DONE only after full regression.
