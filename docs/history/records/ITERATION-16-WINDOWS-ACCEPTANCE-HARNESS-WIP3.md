# Iteration 16 — Windows acceptance evidence hardening (WIP3)

Date: 2026-09-06  
Baseline: Iteration 16 WIP2  
Scope: MHL-011 + MHL-012.

## Result

WIP3 does not manufacture Windows PASS. It hardens the evidence contract and the final evidence handoff so an eventual PASS is harder to obtain from incomplete, stale or accidentally reused artifacts.

## Changes after WIP2

### 1. Installer evidence is now self-contained enough to audit

`tools/windows-installer-acceptance.ps1` now records:

- Windows host and OS;
- SHA-256 for the previous and current MSI;
- exactly which four `msiexec` logs belong to the install/upgrade/reinstall/uninstall sequence.

The script rejects identical previous/current MSI hashes. The evidence validator requires both hashes and all four present, non-truncated logs. Final acceptance still additionally requires `-RequireStandardUser` and a real external previous-release MSI.

### 2. Portable isolation is stricter

`smoke-portable.ps1` now redirects `USERPROFILE`, `HOME`, `APPDATA`, `LOCALAPPDATA` and Java `user.home` into the synthetic Unicode profile for the portable launch. The synthetic profile and working directory must remain empty after the smoke run; state is allowed only beside the launcher.

The report also records host/OS and SHA-256 of the portable archive.

### 3. DPI screenshots cannot be tiny placeholders or copied duplicates

`tools/windows-acceptance-evidence-check.py` now:

- parses PNG IHDR dimensions and requires at least 640x480;
- hashes screenshot bytes and rejects duplicate screenshot content even when copied to different filenames;
- keeps the prior unique-path, in-bundle path, scale and P4 completeness checks.

### 4. One strict final evidence pack command

Added `tools/windows-final-evidence-pack.ps1`. It first runs:

```text
windows-acceptance-evidence-check.py --require-standard-user --require-real-previous --dpi
```

Only after that succeeds does it package installer reports/logs, portable reports and all four DPI report/screenshot sets. The ZIP contains `manifest.sha256`; the script also writes a SHA-256 sidecar for the ZIP itself.

### 5. Regression ratchet

The cross-platform validator test now includes negative cases for:

- missing `msiexec` log;
- portable working-directory write;
- duplicate screenshot bytes under distinct filenames;
- implausibly small screenshot dimensions;
- the previous WIP2 malformed-evidence cases.

`windows-acceptance-harness-check.py` also requires the new report fields and final pack script.

## Validation in this environment

- `python3 tools/windows-acceptance-evidence-check-test.py` — PASS.
- `python3 tools/windows-acceptance-harness-check.py` — PASS.
- `python3 tools/static_release_check.py` — PASS.
- WIP2 already completed `mvn verify -Pproduction` for all 13 modules. WIP3 changed no Java sources; a repeat full reactor run reached `myhomelib-ui` without failures but exceeded the execution time limit before the reactor finished, so no new full-reactor PASS is claimed.

Full Windows acceptance remains pending because this environment is not an interactive Windows host and cannot provide authoritative MHL-011/MHL-012 evidence.

## Remaining release blockers

- **MHL-011:** real 100/125/150/200% Windows DPI acceptance with screenshot evidence.
- **MHL-012:** real previous-release MSI upgrade + reinstall/uninstall + portable smoke under a clean standard/non-elevated Windows user.

No Windows PASS is claimed by WIP3.
