# Iteration 05 — OPDS TLS transport, abuse guards and lifecycle hardening

Date: 2026-09-06  
Baseline: `myhomelib-enterprise-7.1.0-rc3-iter04-local-copy-cache-safety.zip`

## Backlog items completed

| ID | Result |
|---|---|
| MHL-001 | DONE — embedded OPDS now permits plaintext HTTP only on loopback; any non-loopback bind fails closed unless TLS is enabled, in which case the server uses JDK `HttpsServer` with PKCS12/JKS. |
| MHL-014 | DONE for the backlog acceptance surface — configurable listen backlog and concurrent-request cap, per-client authentication throttling/blocking with `429`/`Retry-After`, overload `503`, bounded limiter state and security logging. No unsupported JDK-internal socket-timeout property was introduced. |
| MHL-039 | DONE — start/stop/failure paths now reset active runtime state deterministically, failed starts clean local server/executor resources, and `/health` follows an explicit loopback-vs-exposed policy. |

Additionally, this iteration repairs a stale migration-test expectation discovered by the wider reactor run after V49 had been added in Iteration 02. The production migration logic was not changed.

## MHL-001 — LAN HTTPS enforcement

The embedded OPDS server now resolves its bind address before publishing runtime state and classifies the started instance as loopback-only or network-exposed.

Policy:

- loopback (`127.0.0.1`, `::1`, `localhost`) may run over HTTP for local-reader compatibility;
- any non-loopback bind is rejected if TLS is disabled;
- when TLS is enabled, `HttpsServer` is created with the configured listen backlog;
- TLS is restricted to supported `TLSv1.3` / `TLSv1.2` protocols;
- keystore type may be `PKCS12` or `JKS`;
- keystore path/type/enabled metadata may be persisted, but the keystore password is deliberately **not persisted** by `OpdsSettingsService`;
- the runtime secret can be supplied directly in in-memory settings, with a JVM property or environment variable fallback;
- password `char[]` is zeroed after SSL context initialization;
- OPDS status and health URLs now preserve the actual `http`/`https` scheme.

Runtime password sources supported by the transport:

- `-Dmyhomelib.opds.tls.keyStorePassword=...`
- `MYHOMELIB_OPDS_TLS_KEYSTORE_PASSWORD`

The operator-facing help and operations documentation were updated. Certificate generation/import/fingerprint management remains intentionally isolated to MHL-015 so transport security can be reviewed independently from certificate-management UI.

## MHL-014 — abuse/back-pressure guards

New `OpdsSecurityLimits` centralizes configurable safe bounds:

- `maxConcurrentRequests` — default 64, clamped to 1..1024;
- `listenBacklog` — default 64, clamped to 1..1024;
- `authFailuresPerWindow` — default 8, clamped to 1..1000;
- `authWindowSeconds` — default 60, clamped to 1..3600;
- `authBlockSeconds` — default 120, clamped to 1..86400;
- `healthRequiresAuthWhenExposed` — default true.

`OpdsRequestLimiter` now provides:

1. a fair semaphore limiting concurrently executing requests;
2. immediate `503 Service Unavailable` with `Retry-After: 1` when the request budget is exhausted;
3. a per-client-IP failed-authentication window;
4. `429 Too Many Requests` plus `Retry-After` once the failure threshold is reached;
5. successful-auth reset of that client's failure state;
6. a hard cap of 4096 tracked client states with pruning to avoid attacker-driven unbounded memory growth;
7. warning-level logging for failed and throttled authentication attempts.

The JDK `HttpServer` API does not expose a supported per-instance socket/header timeout setting. This iteration therefore avoids undocumented `sun.net.httpserver.*` global knobs and enforces the acceptance surface with bounded backlog, bounded concurrent execution, immediate request-body close for GET-only OPDS endpoints, and explicit overload responses. A future server-engine replacement can add independently testable transport-level header/read deadlines without relying on JDK internals.

## MHL-039 — lifecycle and health policy

Lifecycle hardening includes:

- `start()` begins from a clean `stop()` state;
- server and executor are created into local variables and cleaned if startup fails before publication;
- runtime settings/limiter/exposure state are published only after a valid bind/transport setup;
- failed startup resets active settings, limiter and exposure state;
- `stop()` stops the HTTP(S) server, shuts down its executor and restores default runtime state;
- exposure is calculated once from the resolved bind address and stored for the life of the started server, so later DNS changes cannot silently alter `/health` security behavior;
- loopback `/health` stays public;
- exposed `/health` requires authentication by default when Basic Auth is enabled;
- exposed `/health` returns `403` if the private-health policy is enabled but no Basic Auth credential is configured;
- the policy can be explicitly disabled with `healthRequiresAuthWhenExposed=false`.

## Maintenance fix found by full-reactor validation

`DatabaseMigrationAdapterLegacyGuardIntegrationTest` still hard-coded migration counts/version values that predated V49. The same test was reproduced as failing on the untouched Iteration 04 baseline, confirming this was not caused by OPDS changes.

The test now derives the number of pending Flyway migrations before migration and verifies that no migrations remain pending afterward. This makes the guard test resilient to legitimate future migration additions while preserving its actual data-integrity assertions.

Focused result: **2 passed, 0 failed, 0 errors**.

## Tests and verification

### Focused OPDS/settings security suite

- `OpdsSettingsServiceTest`: **6 passed**
- `JdkOpdsServerTest`: **9 passed**
- focused total: **15 passed, 0 failed, 0 errors**

Covered scenarios include:

- loopback HTTP remains functional;
- non-loopback plaintext startup is rejected;
- exposed HTTPS starts with a PKCS12 keystore and serves OPDS successfully;
- HTTPS status/base/health URLs use the correct scheme;
- TLS keystore password is not stored in application settings;
- Basic Auth continues to use the existing hashed credential storage;
- loopback health remains public;
- exposed health is authenticated by default;
- exposed private health without configured auth fails closed with `403`;
- repeated bad credentials cross the configured threshold and return `429` + `Retry-After`;
- correct credentials remain blocked during the active throttle period;
- concurrent-request saturation returns `503` + `Retry-After`;
- a failed TLS startup with a missing keystore releases resources and allows an immediate restart on the same port.

### Application + OPDS module tests

A wider changed-module run completed successfully:

- application: **118 tests, 117 passed, 1 skipped, 0 failures/errors**;
- OPDS: **9 passed, 0 failures/errors**;
- total: **127 tests, 126 passed, 1 skipped, 0 failures/errors**.

### Migration regression

`DatabaseMigrationAdapterLegacyGuardIntegrationTest`: **2 passed, 0 failed, 0 errors** after removing stale hard-coded migration counts.

### Architecture

`LayerArchitectureTest`: **12 passed, 0 failed, 0 errors**.

### Reactor compilation/package

- `test-compile`: previously completed successfully for **all 13 Maven modules** after the Iteration 05 changes.
- `mvn -DskipTests package`: **BUILD SUCCESS for all 13 Maven modules**.

### Full reactor test note

A full `mvn test` was run with the execution environment's external time limit. Before the environment terminated the command, these modules completed with no failures/errors:

- shared: **3 passed**;
- domain: **7 passed**;
- application: **118 tests — 117 passed, 1 skipped**;
- infrastructure: **258 tests — 253 passed, 5 skipped**.

The external timeout occurred after Maven entered `myhomelib-reader`, not because Surefire reported a failed test. Therefore this is not represented as a successful full-reactor test run; the completed module suites and focused/architecture gates above are the authoritative results.

## Main files added/changed in this iteration

Application:

- `myhomelib-application/.../opds/OpdsTlsSettings.java` (new)
- `myhomelib-application/.../opds/OpdsSecurityLimits.java` (new)
- `myhomelib-application/.../opds/OpdsServerSettings.java`
- `myhomelib-application/.../opds/OpdsServerStatus.java`
- `myhomelib-application/.../opds/OpdsSettingsService.java`
- `myhomelib-application/.../opds/OpdsSettingsServiceTest.java`

OPDS:

- `myhomelib-opds/.../JdkOpdsServer.java`
- `myhomelib-opds/.../OpdsRequestLimiter.java` (new)
- `myhomelib-opds/.../JdkOpdsServerTest.java`
- `myhomelib-opds/src/test/resources/tls/opds-test.p12` (test-only certificate)

UI/help/operations:

- `myhomelib-ui/.../opds/OpdsUiService.java`
- `myhomelib-ui/src/main/resources/help/opds.md`
- `myhomelib-ui/src/main/resources/help/en/opds.md`
- `myhomelib-ui/src/main/resources/help/bg/opds.md`
- `MYHOMELIB-OPERATIONS.md`
- `MYHOMELIB-FEATURES.md`
- `ARCHITECTURE.md`

Maintenance test:

- `myhomelib-infrastructure/.../DatabaseMigrationAdapterLegacyGuardIntegrationTest.java`

## Scope intentionally left for the next iteration

MHL-015 (TLS certificate management UI: self-signed generation/import/fingerprint/regeneration) was not mixed into this transport/security iteration. It builds directly on the now-completed MHL-001 TLS transport and is a natural candidate for Iteration 06 together with closely related secret/certificate handling work, subject to dependency and risk review against the backlog.
