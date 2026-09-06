# Continuation brief — Iteration 21 / final live acceptance

## Goal

Finish the six remaining 7.1 Final external items using the exact release candidate, exact GitHub evidence and the exact candidate-bound Windows acceptance harness.

## A. GitHub candidate

1. Ensure CodeQL successfully analyzed the intended default-branch candidate SHA.
2. Run **CI Release** on that SHA.
3. Run **GitHub connected acceptance** from the same SHA/run.
4. Require `Overall: PASS` and the non-expired `github-connected-acceptance-<run>-<attempt>` artifact.

The artifact must contain:

- `github-connected-acceptance.json/.md`;
- `acceptance-harness.sha256`;
- `candidate-windows/candidate-windows.sha256`;
- the exact candidate MSI, EXE and portable ZIP.

## B. Windows start

Use a clean disposable standard/non-elevated Windows profile and a repository checkout of the **same candidate SHA**:

```powershell
.\tools\v71-windows-acceptance-start.ps1 `
  -Repo OWNER/REPO `
  -AcceptanceRunId <github-connected-acceptance-run-id> `
  -PreviousMsi C:\path\to\MyHomeLib-<previous>.msi `
  -PreviousVersion <previous-version>
```

The command must first verify that the local acceptance harness matches `acceptance-harness.sha256`. A different checkout is a hard FAIL. It then performs the real-previous MSI/portable lifecycle and launches the exact bound EXE for interactive desktop acceptance.

## C. DPI

With the candidate installed, run:

```powershell
.\tools\windows-ui-acceptance.ps1 -Scale 100
.\tools\windows-ui-acceptance.ps1 -Scale 125
.\tools\windows-ui-acceptance.ps1 -Scale 150
.\tools\windows-ui-acceptance.ps1 -Scale 200
```

Every P4 row must be PASS with unique visible PNG evidence.

## D. Finalization

```powershell
.\tools\v71-finalize-external-acceptance.ps1 `
  -GitHubEvidenceRoot target\github-connected-acceptance
```

Required terminal result:

```text
MyHomeLib 7.1 final external evidence: PASS
```

The reviewer bundle must independently validate GitHub ingest, candidate MSI/EXE/portable hashes, candidate-bound acceptance harness manifest/binding, Windows installer/portable/desktop/DPI evidence and the six-item final decision.

## E. Backlog and release

Only after final PASS:

- MHL-010 -> Виконано;
- MHL-011 -> Виконано;
- MHL-012 -> Виконано;
- MHL-017 -> Виконано;
- MHL-018 -> Виконано;
- MHL-019 -> Виконано.

Then run the normal clean production release gates on the same candidate and create the final non-WIP 7.1 checkpoint.
