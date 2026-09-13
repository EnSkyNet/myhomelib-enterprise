# Iteration 06 — Managed OPDS TLS certificate UX and explicit encryption envelope

Date: 2026-09-06  
Baseline: `myhomelib-enterprise-7.1.0-rc3-iter05-opds-transport-guards.zip`

## Backlog items completed

| ID | Result |
|---|---|
| MHL-015 | DONE — OPDS HTTPS certificate lifecycle is available from the UI/application layer: self-signed generation, PEM certificate + PKCS#8 private-key import, SHA-256 fingerprint/subject/validity inspection and certificate regeneration. Managed PKCS12 settings can be persisted and used to start HTTPS without manual JVM/environment secret injection. Invalid certificate/key input produces a user-facing validation error. |
| MHL-031 | DONE — credential encryption now uses an explicit `mhlenc:v1:<base64>` envelope. Authenticated pre-envelope ciphertext remains readable and migrates on save; arbitrary Base64 plaintext is no longer treated as ciphertext; current-envelope tampering fails closed; migration paths are idempotent. |

## MHL-031 — explicit versioned encryption envelope

`EncryptionUtil` no longer determines ciphertext ownership using only a Base64/length/version-byte heuristic.

Current format:

- prefix: `mhlenc:v1:`;
- payload: Base64-encoded version byte + 12-byte AES-GCM nonce + ciphertext/tag;
- algorithm remains AES-256-GCM;
- malformed or tampered values carrying the current envelope fail closed with `SecurityException`;
- `isEncrypted()` recognizes the current envelope directly and recognizes legacy ciphertext only when it successfully authenticates with this installation's key;
- arbitrary Base64 plaintext that merely resembles the old binary layout is not classified as encrypted;
- `encrypt()` leaves current-envelope values unchanged;
- authenticated legacy ciphertext is decrypted and re-encrypted into the current envelope when it passes through a normal save path.

### Repository migration

`SqliteCollectionRepository` was adjusted so legacy authenticated credentials do not remain indefinitely in the old format merely because they can already be decrypted.

On read/save migration:

1. legacy plaintext is encrypted into the current envelope;
2. authenticated legacy ciphertext is normalized to `mhlenc:v1:`;
3. already-current envelopes are not rewritten;
4. metadata remains unchanged;
5. a second migration pass is idempotent.

This preserves backward compatibility while removing the ambiguity that motivated MHL-031.

## MHL-015 — managed OPDS TLS certificate lifecycle

A new application-facing certificate abstraction was added:

- `OpdsCertificateManager`;
- `OpdsCertificateInfo`;
- managed certificate result containing both effective `OpdsTlsSettings` and inspectable certificate metadata.

The OPDS implementation adds `JdkOpdsCertificateManager` with the following behavior.

### Self-signed generation

- generates an RSA key pair inside the JVM;
- creates an X.509 v3 self-signed certificate without an external `keytool` dependency;
- signs using `SHA256withRSA`;
- creates a random serial number;
- includes SAN entries for `localhost`, `127.0.0.1`, the effective configured host and discovered non-loopback local addresses where available;
- uses a managed PKCS12 keystore under the MyHomeLib config directory;
- generates a high-entropy random keystore password;
- restricts filesystem permissions where the platform supports POSIX permissions;
- replaces the managed keystore atomically where supported.

Removing the earlier `keytool` dependency is important for packaged `jlink`/`jpackage` runtimes where JDK command-line tools are not guaranteed to exist.

### Certificate/key import

The manager accepts:

- X.509 certificate PEM, including a certificate chain;
- unencrypted PKCS#8 private-key PEM.

Before replacing the managed keystore it verifies:

- certificate parsing;
- certificate validity window;
- private-key parsing;
- private key matches the leaf certificate public key.

Invalid certificate/key input fails before the managed keystore is replaced and surfaces a concise validation message.

### Inspection and fingerprint

The manager can reopen the effective keystore and expose:

- SHA-256 certificate fingerprint;
- subject;
- issuer;
- validity start/end;
- serial number.

The fingerprint is displayed in the OPDS settings UI so the user can verify trust out of band before accepting a self-signed certificate on a client.

## TLS secret persistence

Iteration 05 deliberately required the OPDS keystore password to be supplied only at runtime. Iteration 06 adds a managed-certificate path that can persist the generated/imported keystore password safely enough for normal application restart/autostart flow without manual `-D...` or environment configuration.

`OpdsSettingsService` now:

- stores the TLS keystore password only through `EncryptionUtil`;
- therefore persists it as `mhlenc:v1:...`;
- decrypts it only when constructing effective runtime settings;
- rejects an unprotected persisted TLS password;
- preserves the current protected password when the same keystore path is saved without a new plaintext password;
- migrates authenticated legacy ciphertext to the current envelope when settings are loaded/saved.

The Basic Auth password continues to use the existing one-way hash flow and is not changed to reversible encryption.

MHL-013 (`SecretStore` abstraction / OS credential-store integration) is **not** claimed by this iteration; the managed TLS secret currently relies on the existing MyHomeLib AES-GCM key-management mechanism plus the new explicit envelope.

## OPDS settings UI

`OpdsUiService` now exposes certificate-management controls in the OPDS settings screen:

- generate self-signed certificate;
- regenerate the managed certificate;
- import certificate PEM + private key PEM;
- show SHA-256 fingerprint;
- show certificate subject and validity information;
- display explicit trust guidance for LAN/self-signed use;
- show clear errors when certificate generation/import/inspection fails.

A successful generate/import operation updates the in-memory TLS settings and certificate metadata used by the dialog. Persisted settings can subsequently start the HTTPS server without manual runtime secret injection.

The OPDS help/operations documentation was updated in Ukrainian, English and Bulgarian help resources where applicable.

## Tests and verification

### Crypto/settings/repository migration security

Focused migration/security run completed successfully:

- `EncryptionUtilTest`: **4 passed**;
- `OpdsSettingsServiceTest`: **8 passed**;
- `SqliteCollectionRepositoryCredentialsV7Test`: **3 passed**;
- `OnlineHttpPolicyTest` regression guard included in the same infrastructure selection: **3 passed**;
- focused total: **18 passed, 0 failures, 0 errors**.

The dedicated `EncryptionUtilTest` covers:

- new envelope roundtrip;
- crafted Base64 plaintext not classified as ciphertext;
- authenticated legacy ciphertext remains readable and migrates on save;
- current-envelope tampering fails closed.

The collection-repository test additionally verifies authenticated legacy ciphertext migration is idempotent.

### Application + OPDS integration

Final changed-module reactor run:

- application: **120 tests — 119 passed, 1 skipped, 0 failures/errors**;
- OPDS: **14 passed, 0 failures/errors**;
- Maven result: **BUILD SUCCESS**.

OPDS coverage includes:

- `JdkOpdsServerTest`: **9 passed**;
- `JdkOpdsCertificateManagerTest`: **4 passed**;
- `OpdsManagedTlsIntegrationTest`: **1 passed**.

The managed TLS integration test exercises the complete acceptance path:

1. generate a managed self-signed certificate;
2. save TLS settings;
3. verify the keystore password is persisted as protected application data, not plaintext;
4. reload settings;
5. start OPDS HTTPS without manually setting `myhomelib.opds.tls.keyStorePassword` or `MYHOMELIB_OPDS_TLS_KEYSTORE_PASSWORD`;
6. perform a real HTTPS request and receive HTTP 200.

The certificate-manager tests verify inspectable SHA-256 fingerprint/SAN data, valid PEM import, invalid private-key rejection and invalid-certificate rejection.

### Architecture

`LayerArchitectureTest`: **12 passed, 0 failures, 0 errors** — `BUILD SUCCESS`.

### Reactor package

`mvn -DskipTests package`: **BUILD SUCCESS across all 13 Maven modules**.

### UI test note

The UI/reader reactor test run progressed through the non-headless reader/UI suites with **0 failures/errors** in all completed suites, then stalled at the existing JavaFX/headless `MainToolbarWrapFxTest` boundary until the execution environment terminated the command by external timeout.

This timeout is not represented as a successful full UI test run. The authoritative gates for this iteration are the changed-module integration suite, architecture suite, UI compilation and full 13-module package build above.

## Main files added/changed in this iteration

Shared crypto:

- `myhomelib-shared/src/main/java/com/myhomelibcorp/shared/util/EncryptionUtil.java`
- `myhomelib-shared/src/test/java/com/myhomelibcorp/shared/util/EncryptionUtilTest.java` (new)

Application:

- `myhomelib-application/src/main/java/com/myhomelibcorp/application/opds/OpdsCertificateInfo.java` (new)
- `myhomelib-application/src/main/java/com/myhomelibcorp/application/opds/OpdsCertificateManager.java` (new)
- `myhomelib-application/src/main/java/com/myhomelibcorp/application/opds/OpdsSettingsService.java`
- `myhomelib-application/src/test/java/com/myhomelibcorp/application/opds/OpdsSettingsServiceTest.java`

Infrastructure migration:

- `myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteCollectionRepository.java`
- `myhomelib-infrastructure/src/test/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteCollectionRepositoryCredentialsV7Test.java`

OPDS:

- `myhomelib-opds/src/main/java/com/myhomelibcorp/opds/JdkOpdsCertificateManager.java` (new)
- `myhomelib-opds/src/test/java/com/myhomelibcorp/opds/JdkOpdsCertificateManagerTest.java` (new)
- `myhomelib-opds/src/test/java/com/myhomelibcorp/opds/OpdsManagedTlsIntegrationTest.java` (new)
- `myhomelib-opds/pom.xml`

UI/help/docs:

- `myhomelib-ui/src/main/java/com/myhomelibcorp/ui/opds/OpdsUiService.java`
- `myhomelib-ui/src/main/resources/help/opds.md`
- `myhomelib-ui/src/main/resources/help/en/opds.md`
- `myhomelib-ui/src/main/resources/help/bg/opds.md`
- `ARCHITECTURE.md`
- `MYHOMELIB-FEATURES.md`
- `MYHOMELIB-OPERATIONS.md`

## Scope intentionally left for later iterations

This iteration does not claim MHL-013 (`SecretStore` abstraction / OS-native secret storage). It also does not replace the JDK embedded HTTP(S) engine or add OS/browser trust-store automation for self-signed certificates.

The next backlog iteration should be selected from the remaining P1 items based on dependency/risk grouping rather than extending certificate scope further.
