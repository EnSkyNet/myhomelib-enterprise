# Continuation brief — Iteration 22 / final live acceptance

## Goal

Finish the six remaining 7.1 Final external items with one exact GitHub candidate, one exact candidate-bound acceptance harness and one Windows host/user/session.

Remaining external items:

- GitHub: MHL-010, MHL-017, MHL-018, MHL-019;
- Windows: MHL-011, MHL-012.

## A. GitHub candidate

1. Ensure CodeQL successfully analyzed the intended default-branch candidate SHA.
2. Run **CI Release** on that exact SHA.
3. Run **GitHub connected acceptance** from that same candidate/release run.
4. Require `Overall: PASS` and a non-expired `github-connected-acceptance-<run>-<attempt>` artifact.

The connected artifact must contain the exact MSI, EXE, portable ZIP, `candidate-windows.sha256`, `acceptance-harness.sha256`, JSON and Markdown evidence.

## B. Start exactly one Windows acceptance session

Use a clean disposable **standard/non-elevated** Windows profile and a checkout of the exact candidate SHA:

```powershell
.\tools\v71-windows-acceptance-start.ps1 `
  -Repo OWNER/REPO `
  -AcceptanceRunId <github-connected-acceptance-run-id> `
  -PreviousMsi C:\path\to\MyHomeLib-<previous>.msi `
  -PreviousVersion <previous-version>
```

The command:

1. downloads and GitHub-digest-verifies the connected artifact;
2. verifies the local harness against candidate `acceptance-harness.sha256`;
3. removes stale Windows/DPI/final evidence from earlier sessions;
4. creates `target\windows-host-binding\windows-host-binding.json` with a fresh session id and hashed machine/user fingerprints;
5. runs real-previous MSI + portable acceptance;
6. runs the exact bound EXE desktop acceptance.

**Do not run the start command again after beginning the DPI sequence.** A rerun creates a new session id and invalidates earlier Windows evidence by design.

If Windows requires a sign-out/restart while changing display scaling, keep the same machine, same Windows account, same candidate checkout and the same `target\windows-host-binding` record. Each DPI runner re-verifies the live machine/user against that record.

## C. DPI on the same bound host/user/session

With the EXE-installed candidate still installed, run:

```powershell
.\tools\windows-ui-acceptance.ps1 -Scale 100
.\tools\windows-ui-acceptance.ps1 -Scale 125
.\tools\windows-ui-acceptance.ps1 -Scale 150
.\tools\windows-ui-acceptance.ps1 -Scale 200
```

Every P4 row must be PASS with unique visible PNG evidence. A different host, Windows user or acceptance session is a hard FAIL.

## D. Finalization

```powershell
.\tools\v71-finalize-external-acceptance.ps1 `
  -GitHubEvidenceRoot target\github-connected-acceptance
```

Required terminal result:

```text
MyHomeLib 7.1 final external evidence: PASS
```

Authoritative outputs:

```text
target/myhomelib-7.1-final-external-evidence.zip
target/myhomelib-7.1-final-external-evidence.zip.sha256
target/v71-final-external-acceptance/v71-final-external-acceptance.json
```

The reviewer bundle independently cross-checks GitHub candidate/run/digests, candidate MSI/EXE/portable, candidate-bound harness, Windows host/user/session, installer/portable/desktop/DPI evidence, nested evidence ZIP and the consolidated six-item decision.

## E. Backlog reconciliation

Only after section D PASS:

- MHL-010 -> Виконано;
- MHL-011 -> Виконано;
- MHL-012 -> Виконано;
- MHL-017 -> Виконано;
- MHL-018 -> Виконано;
- MHL-019 -> Виконано.

Attach the final reviewer ZIP/hash plus CI Release and GitHub connected acceptance run URLs.

## F. Final release

Run the normal clean production release gates on the same candidate and create the final non-WIP 7.1 checkpoint only after they pass.
