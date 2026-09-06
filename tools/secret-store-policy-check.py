#!/usr/bin/env python3
"""Static ratchet for MHL-013 platform SecretStore integration."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
errors = []

def require(path: str, needle: str, message: str):
    text = (ROOT / path).read_text(encoding="utf-8")
    if needle not in text:
        errors.append(message)

require(
    "myhomelib-shared/src/main/java/com/myhomelibcorp/shared/security/CredentialMasterKeyManager.java",
    "context.isWindows() && !portableMode",
    "installed Windows mode must require a native SecretStore by default",
)
require(
    "myhomelib-shared/src/main/java/com/myhomelibcorp/shared/util/EncryptionUtil.java",
    "CredentialMasterKeyManager.loadOrCreateDefault",
    "EncryptionUtil must resolve its master key through CredentialMasterKeyManager",
)
require(
    "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/security/WindowsDpapiSecretStore.java",
    "Crypt32Util.cryptProtectData",
    "Windows SecretStore must protect data with DPAPI",
)
require(
    "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/security/LinuxSecretServiceStore.java",
    "secret-tool",
    "Linux SecretStore adapter must target Secret Service",
)
require(
    "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/security/MacKeychainSecretStore.java",
    'Native.load("Security"',
    "macOS SecretStore adapter must target Security.framework Keychain without CLI secret exposure",
)
service = ROOT / "myhomelib-infrastructure/src/main/resources/META-INF/services/com.myhomelibcorp.shared.security.SecretStoreProvider"
if not service.is_file() or "PlatformSecretStoreProvider" not in service.read_text(encoding="utf-8"):
    errors.append("SecretStoreProvider ServiceLoader registration is missing")
require(
    ".github/workflows/ci-pr.yml",
    "Windows DPAPI secret-store gate",
    "PR CI must contain the Windows DPAPI integration gate",
)

# The raw local key is intentionally allowed only inside the shared fallback manager.
for path in ROOT.glob("myhomelib-*/src/main/java/**/*.java"):
    if path.name == "CredentialMasterKeyManager.java":
        continue
    text = path.read_text(encoding="utf-8", errors="ignore")
    if "credential-key.aes256" in text:
        errors.append(f"raw credential-key filename escaped fallback manager: {path.relative_to(ROOT)}")

if errors:
    print("SecretStore policy: FAIL")
    for error in errors:
        print(f" - {error}")
    sys.exit(1)
print("SecretStore policy: PASS")
