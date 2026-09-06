# Continuation brief — Iteration 19 / external acceptance finalization

## Goal

Produce genuine candidate-bound evidence for the six remaining 7.1 Final external items and finish with one independently verified reviewer bundle.

## A. GitHub candidate

Run `CI Release` on the exact commit intended for release.

The release now fails closed unless that exact SHA already has a successful CodeQL analysis on the repository default branch and there are no open High/Critical code-scanning alerts.

A successful run must expose non-expired:

- `myhomelib-supply-chain`;
- `myhomelib-windows`.

## B. Connected GitHub acceptance

Run **GitHub connected acceptance** from the same commit/ref. If necessary provide the exact `release_run_id`.

PASS requires, among other checks:

- default branch requires `Fast gate`;
- at least five successful PR Fast gate samples and median <= 600 s;
- selected CI Release `head_sha` equals candidate SHA;
- downloaded supply-chain and Windows artifact ZIP bytes match GitHub's declared SHA-256 digests;
- release supply-chain artifact contains the exact-candidate CodeQL release-gate PASS record;
- the candidate has successful CodeQL analysis and no open High/Critical alerts;
- candidate MSI and portable SHA-256 are captured.

Download the produced `github-connected-acceptance-*` artifact intact to:

```text
target/github-connected-acceptance/
```

It must include `github-connected-acceptance.json`, `.md`, candidate MSI, portable ZIP and `candidate-windows.sha256`.

## C. Windows MHL-012

On a clean disposable standard/non-elevated Windows profile:

```powershell
.\tools\windows-bound-packaging-acceptance.ps1 `
  -GitHubEvidenceRoot target\github-connected-acceptance `
  -PreviousMsi C:\path\to\MyHomeLib-<previous>.msi `
  -PreviousVersion <previous-version>
```

Do not rebuild/rename the current candidate.

## D. Windows MHL-011

Run all four interactive DPI passes on the monitor hosting MyHomeLib:

```powershell
.\tools\windows-ui-acceptance.ps1 -Scale 100
.\tools\windows-ui-acceptance.ps1 -Scale 125
.\tools\windows-ui-acceptance.ps1 -Scale 150
.\tools\windows-ui-acceptance.ps1 -Scale 200
```

Every P4 item requires unique visible PNG evidence. Any critical clipping/overlap/geometry defect is FAIL.

## E. One finalization command

```powershell
.\tools\v71-finalize-external-acceptance.ps1 `
  -GitHubEvidenceRoot target\github-connected-acceptance
```

The command now performs six stages and must end with:

```text
MyHomeLib 7.1 final external evidence: PASS
```

It also independently verifies the completed reviewer ZIP using:

```text
tools/v71-final-evidence-bundle-check.py
```

Authoritative files:

```text
target/myhomelib-7.1-final-external-evidence.zip
target/myhomelib-7.1-final-external-evidence.zip.sha256
target/v71-final-external-acceptance/v71-final-external-acceptance.json
```

## F. Backlog reconciliation

Only after section E PASS:

- MHL-010 -> Виконано;
- MHL-011 -> Виконано;
- MHL-012 -> Виконано;
- MHL-017 -> Виконано;
- MHL-018 -> Виконано;
- MHL-019 -> Виконано.

Attach the final reviewer ZIP/hash and live GitHub run URLs as evidence.

## G. Final release gates

Run the normal clean production release gates on the candidate and create the final non-WIP 7.1 checkpoint only after they pass.
