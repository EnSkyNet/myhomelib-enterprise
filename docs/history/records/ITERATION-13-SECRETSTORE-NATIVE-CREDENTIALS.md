# Iteration 13 — Native SecretStore and credential master-key migration

Date: 2026-09-06
Baseline: Iteration 12 (`myhomelib-enterprise-7.1.0-rc3-iter12-support-bundle-temp-lifecycle.zip`)
Scope: MHL-013

## 1. Backlog mapping

### MHL-013 — Add SecretStore abstraction

Acceptance addressed:

1. Installed Windows no longer needs a raw AES master key beside configuration: the key is protected with CurrentUser DPAPI and only the protected blob is stored on disk.
2. Existing `credential-key.aes256` is migrated without changing the key: native write is followed by read-back verification and only then is the raw legacy file deleted.
3. Native-store unavailability is controlled/fail-closed on installed Windows by default; Linux/macOS have a documented restricted fallback, and portable mode intentionally keeps a local restricted key for portability.
4. The abstraction is platform-neutral in `shared`; native implementations live in `infrastructure` and are loaded through `ServiceLoader`.
5. macOS uses Security.framework Keychain directly (not a CLI containing the secret in argv); Linux targets freedesktop Secret Service through `secret-tool` with the secret written on stdin.

## 2. Implementation

### Shared security boundary

Added:

- `SecretStore`
- `SecretStoreProvider`
- `SecretStoreContext`
- `SecretStoreException`
- `CredentialMasterKeyManager`

`EncryptionUtil` still owns the AES-GCM envelope/cipher operation but delegates master-key persistence/resolution to `CredentialMasterKeyManager` unless the explicit environment/system-property override is present.

Resolution order:

1. `MYHOMELIB_ENCRYPTION_KEY` or `-Dmyhomelib.encryption.key` when explicitly supplied;
2. portable mode -> restricted local key;
3. platform SecretStore provider;
4. controlled local fallback where policy allows it.

Installed Windows defaults `myhomelib.secretStore.nativeRequired=true` logically (without requiring the property to be written). Linux/macOS can be made equally strict by setting the property explicitly.

### Platform providers

- Windows: JNA `Crypt32Util.cryptProtectData/cryptUnprotectData` (CurrentUser DPAPI), atomic protected-blob replace.
- macOS: direct JNA bridge to Security.framework generic-password Keychain APIs; no secret is sent through a child-process command line.
- Linux: `secret-tool lookup/store/clear` with the secret supplied through stdin and a bounded command timeout.
- Portable mode returns no native provider by design.

JNA/JNA-platform 5.13.0 were added to dependency management and infrastructure only; the SPI itself remains dependency-free in `shared`.

## 3. Migration/failure semantics

A legacy local key takes precedence over any existing native value during first migration because existing ciphertext is cryptographically bound to that legacy key. The sequence is:

`read legacy -> validate 32-byte Base64 -> native write -> native read-back -> exact compare -> delete legacy file`.

Any failure before the final delete keeps the legacy key intact. On installed Windows, absence/failure of a native store raises `SecretStoreException` and does not create a new plaintext fallback key.

## 4. Regression barriers

PR CI now includes:

- shared `CredentialMasterKeyManagerTest` in fast-core;
- `PlatformSecretStoreProviderTest` in the infrastructure security regression gate;
- `tools/secret-store-policy-check.py`;
- dedicated `windows-latest` job with `WindowsDpapiSecretStoreIntegrationTest`.

The static ratchet checks that `EncryptionUtil` delegates to the manager, Windows remains native-required, DPAPI is still used, provider registration exists, and the raw legacy-key filename does not spread into production classes outside the controlled fallback manager.

## 5. Verification in this environment

| Gate | Result |
| --- | --- |
| Encryption envelope regression | 4/4 PASS |
| CredentialMasterKeyManager migration/fail-closed tests | 5/5 PASS |
| PlatformSecretStoreProvider tests | 2/2 PASS |
| Windows DPAPI integration test | SKIP on Linux (configured as mandatory Windows PR job) |
| Migration/security/concurrency + SecretStore regression | 33/33 PASS |
| Fast application suite | 126 PASS, 1 SKIP, 0 failures/errors |
| OPDS regression suite | 14/14 PASS |
| ArchUnit | 12/12 PASS |
| E2E journeys | 10/10 PASS |
| SecretStore static policy ratchet | PASS |
| Privacy/temp, XML/archive, executor, language, supply-chain, architecture ratchets | PASS |
| GitHub workflow YAML parse | PASS |
| Reactor `test-compile` | 13/13 modules BUILD SUCCESS |
| Reactor `package` | 13/13 modules BUILD SUCCESS after warm retry |

## 6. Platform-verification boundary

This container is Linux and cannot execute Windows DPAPI. Therefore the Windows-specific test is not falsely reported as locally passing. It is guarded with `@EnabledOnOs(OS.WINDOWS)` and is run by a dedicated `windows-latest` PR job, where it performs a real protect/read/delete round-trip and asserts that the persisted blob does not contain the plaintext secret.

The macOS Security.framework implementation is compile-verified here but likewise needs the existing macOS release matrix for a native runtime exercise. Linux Secret Service is used when `secret-tool`/the desktop Secret Service are available; this headless container has neither, so the manager's controlled Linux fallback is the locally exercised path.
