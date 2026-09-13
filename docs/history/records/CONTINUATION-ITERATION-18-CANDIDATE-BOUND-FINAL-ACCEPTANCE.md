# Continuation brief — Iteration 18 / candidate-bound final acceptance

## Goal

Close the six remaining 7.1 Final externally evidenced items without mixing evidence from different builds:

- GitHub: MHL-010, MHL-017, MHL-018, MHL-019.
- Windows: MHL-011, MHL-012.

## A. Produce the exact release candidate in GitHub

Run `CI Release` for the candidate commit that is intended for final 7.1 acceptance. The successful run must publish:

- `myhomelib-supply-chain`;
- `myhomelib-windows` with exact versioned MSI, EXE, portable ZIP and `SHA256SUMS`.

## B. Run connected GitHub acceptance on the same commit

From that same commit/ref run:

```text
GitHub Actions -> GitHub connected acceptance -> Run workflow
```

If more than one successful CI Release run exists, supply the exact `release_run_id`.

The workflow is fail-closed unless its `${{ github.sha }}` equals the selected release run `head_sha` and the same candidate has a successful CodeQL analysis.

Download the produced `github-connected-acceptance-*` artifact. It contains:

```text
github-connected-acceptance.json
github-connected-acceptance.md
candidate-windows/MyHomeLib-<version>.msi
candidate-windows/myhomelib-<version>-windows-<arch>.zip
candidate-windows/candidate-windows.sha256
```

Place/extract it so the evidence JSON is available at, for example:

```text
target/github-connected-acceptance/github-connected-acceptance.json
```

Do not rename or rebuild the current MSI/portable candidate.

## C. Run bound MHL-012 packaging acceptance on Windows

Use a clean disposable **standard/non-elevated** Windows user profile and a real previous-release MSI:

```powershell
.\tools\windows-bound-packaging-acceptance.ps1 `
  -GitHubEvidenceRoot target\github-connected-acceptance `
  -PreviousMsi C:\path\to\MyHomeLib-<previous>.msi `
  -PreviousVersion <previous-version>
```

The runner verifies that the current MSI and portable ZIP hashes match the connected GitHub release candidate before any acceptance result can pass.

Expected result:

```text
Bound Windows packaging acceptance: PASS (DPI still pending)
```

## D. Run MHL-011 DPI acceptance

On the same acceptance host, set Windows Display scaling for the monitor hosting MyHomeLib and run all four scales:

```powershell
.\tools\windows-ui-acceptance.ps1 -Scale 100
.\tools\windows-ui-acceptance.ps1 -Scale 125
.\tools\windows-ui-acceptance.ps1 -Scale 150
.\tools\windows-ui-acceptance.ps1 -Scale 200
```

Every P4 row must have screenshot evidence. Any critical clipping/overlap/geometry defect is a failure.

## E. Finalize all six external items

After all DPI reports are complete:

```powershell
.\tools\v71-finalize-external-acceptance.ps1 `
  -GitHubEvidenceRoot target\github-connected-acceptance
```

This command must finish with:

```text
MyHomeLib 7.1 final external evidence: PASS
```

Authoritative output:

```text
target/myhomelib-7.1-final-external-evidence.zip
target/myhomelib-7.1-final-external-evidence.zip.sha256
target/v71-final-external-acceptance/v71-final-external-acceptance.json
```

Only then reconcile MHL-010/MHL-011/MHL-012/MHL-017/MHL-018/MHL-019 to completed.

## F. Final repository/release gates

After the genuine external PASS:

```powershell
.\mvnw.cmd -B -ntp clean verify -Pproduction
python tools\static_release_check.py
python tools\stage23-cross-platform-release-check.py `
  --dist dist `
  --require-checksums `
  --require-portable `
  --expect-installer `
  --expect-windows-msi `
  --expect-windows-exe
```

Then create the final non-WIP 7.1 checkpoint and attach the consolidated external evidence bundle/hash.
