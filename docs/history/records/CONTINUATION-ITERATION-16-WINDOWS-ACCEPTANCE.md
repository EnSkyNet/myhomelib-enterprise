# Continuation brief — Iteration 16 / Windows acceptance

## Goal
Close the two remaining 7.1 Final P0 release blockers from the technical backlog:

- **MHL-011 — Windows / DPI**: real acceptance at 100/125/150/200% with screenshot evidence.
- **MHL-012 — Windows / Packaging**: installer + portable lifecycle, non-admin user, Unicode paths, user-data preservation.

This checkpoint contains the hardened WIP3 harness, but **does not claim Windows PASS**. Final PASS still requires evidence from a real Windows host/runner.

## Implemented in this checkpoint

1. `tools/windows-ui-acceptance.ps1`
   - 100/125/150/200 scale parameter.
   - 20 P4 manual checks per scale.
   - captures a PNG desktop screenshot for each P4 check.
   - a P4 check cannot be marked PASS without screenshot evidence.
   - fixed the default screenshot-path validation bug: evidence is now resolved relative to the report directory, not the repository root.
   - on a multi-monitor host, screenshot-backed `P4-01 = PASS` can resolve the `GetDpiForSystem()` diagnostic `AUTO-0 = BLOCKED`; a single-monitor DPI mismatch remains FAIL.
   - JSON evidence now records scenario, timestamp, host, OS, requested scale, observed DPI, monitor count and launcher.
   - no longer depends on `$IsWindows` or `.NET Path.GetRelativePath()`, so the manual runner does not require PowerShell 7 merely for those APIs.
2. `tools/windows-installer-acceptance.ps1`
   - install previous -> upgrade current -> repeat current -> uninstall.
   - shortcut and launcher checks.
   - preserves and hashes synthetic user-data sentinels.
   - optional `-RequireStandardUser` fail-closed guard.
   - records whether the previous package was `synthetic` or an externally supplied real previous-release MSI.
   - records host/OS, SHA-256 of both MSI packages and the exact four `msiexec` evidence logs.
   - writes Markdown + JSON report and retains msiexec logs.
3. `smoke-portable.ps1`
   - extracts and runs under Unicode + spaces in extract/home/cwd paths.
   - redirects `USERPROFILE`, `HOME`, `APPDATA`, `LOCALAPPDATA` and Java `user.home` into the synthetic profile for the launch.
   - requires the synthetic profile and working directory to remain empty; portable state must be created only beside the launcher.
   - records host/OS and SHA-256 of the portable archive.
   - writes Markdown + JSON report.
4. `tools/windows-acceptance-evidence-check.py`
   - validates installer + portable evidence.
   - `--require-standard-user` now requires both the guard to have been enabled and `isAdministrator=false` explicitly; missing fields no longer pass accidentally.
   - `--require-real-previous` distinguishes a real previous-release MSI from the synthetic CI upgrade preflight.
   - installer evidence requires distinct MSI SHA-256 values and exactly four present/non-truncated `msiexec` logs.
   - portable evidence requires the profile-environment redirect and proves no synthetic-profile/CWD write.
   - with `--dpi`, requires all four DPI reports, exact embedded scale, `AUTO-0/AUTO-1 = PASS`, exactly one of each P4 row, unique in-bundle PNG evidence for every P4 row and valid screenshot dimensions (at least 640x480).
   - rejects absolute/out-of-bundle screenshot paths, duplicate screenshot *content* under different filenames, and cross-checks Unicode **and spaces** in all portable path roles.
5. `tools/windows-final-evidence-pack.ps1`
   - runs the strict final validator with standard-user + real-previous-MSI + all-DPI requirements;
   - stages only validated reports/logs/screenshots;
   - creates `manifest.sha256`, `windows-final-acceptance-evidence.zip` and an archive SHA-256 sidecar.
6. Regression ratchet
   - `tools/windows-acceptance-evidence-check-test.py` builds synthetic evidence bundles cross-platform and proves malformed evidence fails closed.
   - PR CI runs both the structure check and validator regression test.
7. Release CI
   - keeps the synthetic MSI lifecycle as an automated packaging preflight.
   - uploads installer/portable/DPI reports, logs and screenshots when present.
8. Linux-side regression stability
   - JavaFX runtime tests preflight whether Linux `DISPLAY` is actually reachable before touching the JavaFX singleton, avoiding a Surefire hang on stale display variables.
   - removed `DISPLAY`-only JUnit gating that would otherwise disable those regression tests on Windows.
   - WIP2 completed the full offline Maven `verify -Pproduction` successfully for all 13 modules; WIP3 changes only acceptance scripts/tests/docs/workflow metadata, not Java sources.
   - WIP3 harness structure, evidence-validator regression tests and `static_release_check.py` all PASS. A repeat full Maven run in this execution window reached the UI module without test failures but was stopped by the tool time limit, so WIP3 does not claim a second complete reactor run.

## Current checkpoint validation

Completed successfully in the supplied offline Linux environment:

```text
Windows acceptance harness structure: PASS
Windows acceptance evidence validator regression tests: PASS
OFFLINE STATIC RELEASE CHECK: PASS
WIP2 baseline Maven verify -Pproduction: BUILD SUCCESS (13/13 reactor modules)
WIP3 repeat Maven verify: time-limited after reaching myhomelib-ui; no failure observed before timeout
```

`stage23-cross-platform-release-check.py` still requires the final packaged `dist` tree and remains a post-Windows-PASS gate. These Linux-side results do not replace the required real Windows evidence.

## Required next steps on Windows

### A. Automated/synthetic packaging preflight

This is useful for CI or an initial clean-VM check, but **is not the final real-upgrade acceptance**:

```powershell
.\tools\windows-installer-acceptance.ps1 -RequireStandardUser
.\package-portable.ps1
.\smoke-portable.ps1
python tools\windows-acceptance-evidence-check.py --root target --require-standard-user
```

Acceptance: install/upgrade/reinstall/uninstall PASS, shortcuts correct then removed, synthetic user data unchanged, portable writes only beside launcher, no synthetic-profile write, Unicode + spaces exercised.

### B. MHL-012 final packaging acceptance with a real previous MSI

Run from a **clean disposable standard/non-elevated Windows user profile** and supply the real previous release package:

```powershell
.\tools\windows-installer-acceptance.ps1 `
  -RequireStandardUser `
  -PreviousMsi C:\path\to\MyHomeLib-<previous>.msi `
  -PreviousVersion <previous-version>

.\package-portable.ps1
.\smoke-portable.ps1

python tools\windows-acceptance-evidence-check.py `
  --root target `
  --require-standard-user `
  --require-real-previous
```

Do not use the synthetic `7.0.99` MSI as the sole final-upgrade proof; it validates Windows Installer mechanics using the current application payload.

### C. MHL-011 manual DPI acceptance

Use a real interactive Windows VM/host. For each DPI, set Windows Display scaling for the monitor that hosts MyHomeLib, sign out/restart the session if required, launch the packaged app, and run:

```powershell
.\tools\windows-ui-acceptance.ps1 -Scale 100
.\tools\windows-ui-acceptance.ps1 -Scale 125
.\tools\windows-ui-acceptance.ps1 -Scale 150
.\tools\windows-ui-acceptance.ps1 -Scale 200
```

Before every P4 result the script asks the tester to prepare the exact UI state and captures a screenshot. Do not mark PASS unless the screenshot visibly proves the check.

Then keep all reports and PNGs under `target` and run:

```powershell
python tools\windows-acceptance-evidence-check.py `
  --root target `
  --require-standard-user `
  --require-real-previous `
  --dpi
```

After that strict validator passes, create the self-contained handoff bundle:

```powershell
.\tools\windows-final-evidence-pack.ps1 -Root target
```

Keep both `target\windows-final-acceptance-evidence.zip` and its `.sha256` sidecar as the authoritative Windows acceptance evidence package.

### D. Defect policy

- Any clipping/overlap/geometry defect on a critical screen is a failure of MHL-011.
- Any installer/update/uninstall data-loss or portable profile-write is a failure of MHL-012.
- Fix P0/P1 defects in code, repeat the affected scale/scenario, and keep fresh evidence.

## Final gates after Windows PASS

```powershell
.\mvnw.cmd -B -ntp clean verify -Pproduction
python tools\static_release_check.py
python tools\stage23-cross-platform-release-check.py --dist dist --require-checksums --require-portable --expect-installer
```

Then update backlog status/evidence links for MHL-011/MHL-012 and create a final non-WIP Iteration 16 checkpoint.
