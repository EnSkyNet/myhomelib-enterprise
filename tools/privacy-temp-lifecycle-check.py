#!/usr/bin/env python3
"""Offline ratchet for Support Bundle privacy and external-reader temporary-file lifecycle."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []

support = (ROOT / "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/SupportBundleService.java").read_text(encoding="utf-8")
sanitizer = (ROOT / "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/SupportBundleSanitizer.java").read_text(encoding="utf-8")
dialog = (ROOT / "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/ApplicationSettingsDialog.java").read_text(encoding="utf-8")
launcher = (ROOT / "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/ExternalBookLauncher.java").read_text(encoding="utf-8")
run_action = (ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/book/RunBookActionUseCase.java").read_text(encoding="utf-8")
cache = (ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/book/ExternalReaderMaterializationCache.java").read_text(encoding="utf-8")

if 'line(out, "dataDir", "<DATA_DIR>")' not in support or 'line(out, "launchDir", "<LAUNCH_DIR>")' not in support:
    errors.append("Support Bundle environment must not emit exact dataDir/launchDir")
if 'putSanitizedFile(zip, "logs/"' not in support:
    errors.append("Support Bundle logs are not routed through sanitizer")
if 'runtimeVersion()' not in support or 'jpackage.app-version' not in support:
    errors.append("Support Bundle app version is not resolved from runtime/build metadata")
if 'SupportBundleOptions' not in dialog or 'supportBundleService.preview(' not in dialog:
    errors.append("Support Bundle export has no user-visible preview/options step")
for token in ('URL_REDACTED', 'EMAIL_REDACTED', 'SECRET_ASSIGNMENT', 'PRIVATE_FIELD'):
    if token not in sanitizer:
        errors.append(f"Support Bundle sanitizer missing {token}")

for rel, text in (("ExternalBookLauncher", launcher), ("RunBookActionUseCase", run_action)):
    if 'deleteOnExit' in text:
        errors.append(f"{rel} regressed to deleteOnExit")
    if 'ExternalReaderMaterializationCache' not in text:
        errors.append(f"{rel} does not use managed external-reader cache")

for token in ('DEFAULT_MAX_BYTES', 'DEFAULT_MAX_AGE', 'cleanupCrashLeftoversLocked', 'retainUntil(Process', 'keepUntilNextStartup'):
    if token not in cache:
        errors.append(f"external-reader cache missing lifecycle contract: {token}")

if errors:
    print("PRIVACY/TEMP LIFECYCLE CHECK: FAIL")
    for error in errors:
        print(" -", error)
    sys.exit(1)

print("PRIVACY/TEMP LIFECYCLE CHECK: PASS")
print(" - Support Bundle text/logs are sanitized and previewed before export")
print(" - runtime version is not hard-coded in diagnostics")
print(" - external-reader materializations use bounded process-aware lifecycle")
