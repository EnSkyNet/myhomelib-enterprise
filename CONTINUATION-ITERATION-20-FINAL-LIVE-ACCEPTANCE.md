# Continuation brief — Iteration 20 / final live acceptance

## Goal

Produce genuine candidate-bound evidence for the six remaining 7.1 Final external items and finish with one independently verified reviewer bundle. Iteration 20 removes the remaining undocumented/manual handoff gaps; it still does **not** manufacture GitHub or Windows PASS.

Remaining external items:

- GitHub: MHL-010, MHL-017, MHL-018, MHL-019;
- Windows: MHL-011, MHL-012.

## A. Create the exact GitHub release candidate

1. Ensure CodeQL has successfully analyzed the intended default-branch candidate SHA.
2. Run **CI Release** on that exact SHA.
3. Run **GitHub connected acceptance** from the same SHA; if needed provide the exact `release_run_id`.
4. The connected workflow must end in PASS and upload one non-expired `github-connected-acceptance-<run>-<attempt>` artifact.

PASS proves the required Fast gate, hosted PR median <= 600 s, exact CI Release SHA, SBOM/Dependency-Check evidence, exact-candidate CodeQL, and GitHub-declared digests of the release supply-chain/Windows artifacts. The resulting candidate set now contains the exact MSI, EXE and portable ZIP plus `candidate-windows.sha256`.

## B. One Windows acceptance start command

Use a clean disposable **standard/non-elevated** Windows profile. Set a GitHub token with Actions read access in `GH_TOKEN` or `GITHUB_TOKEN` when the repository requires authentication.

```powershell
.\tools\v71-windows-acceptance-start.ps1 `
  -Repo OWNER/REPO `
  -AcceptanceRunId <github-connected-acceptance-run-id> `
  -PreviousMsi C:\path\to\MyHomeLib-<previous>.msi `
  -PreviousVersion <previous-version>
```

This single entrypoint:

1. downloads the exact connected-acceptance artifact through the GitHub Actions API;
2. verifies its API-declared SHA-256 digest and safe ZIP structure;
3. stages `target\github-connected-acceptance` only after schema/hash/candidate validation;
4. verifies the exact MSI/EXE/portable hashes;
5. runs real previous -> bound current MSI lifecycle and portable Unicode/isolation smoke;
6. launches real desktop release acceptance for the bound EXE.

The desktop acceptance requires screenshot-backed PASS for:

- interactive bound EXE installer UI under the standard user;
- first start;
- migration/opening of a representative real previous-version profile/database/library;
- real collection browse/search/details;
- one real online book download;
- Reader open/navigation/close/reopen;
- real backup -> state change -> restore round-trip.

A local copied artifact can be used only for rehearsal. Final PASS requires `github-connected-acceptance-ingest.json` with `remoteDigestVerified=true`.

## C. MHL-011 DPI acceptance

With the EXE-installed candidate still installed, set the monitor hosting MyHomeLib to each scale and run:

```powershell
.\tools\windows-ui-acceptance.ps1 -Scale 100
.\tools\windows-ui-acceptance.ps1 -Scale 125
.\tools\windows-ui-acceptance.ps1 -Scale 150
.\tools\windows-ui-acceptance.ps1 -Scale 200
```

Every P4 item must be PASS with unique visible PNG evidence. Any critical clipping/overlap/geometry defect is FAIL.

## D. One finalization command

```powershell
.\tools\v71-finalize-external-acceptance.ps1 `
  -GitHubEvidenceRoot target\github-connected-acceptance
```

It must end with:

```text
MyHomeLib 7.1 final external evidence: PASS
```

Authoritative outputs:

```text
target/myhomelib-7.1-final-external-evidence.zip
target/myhomelib-7.1-final-external-evidence.zip.sha256
target/v71-final-external-acceptance/v71-final-external-acceptance.json
```

The reviewer bundle independently revalidates GitHub JSON + ingest record, exact MSI/EXE/portable manifest binding, real Windows installer/portable/desktop/DPI evidence, nested Windows bundle checksum/manifest and the consolidated six-item decision.

## E. Backlog reconciliation

Only after section D PASS:

- MHL-010 -> Виконано;
- MHL-011 -> Виконано;
- MHL-012 -> Виконано;
- MHL-017 -> Виконано;
- MHL-018 -> Виконано;
- MHL-019 -> Виконано.

Attach the final reviewer ZIP/hash plus the CI Release and GitHub connected acceptance run URLs as evidence.

## F. Final release gates

Run the normal clean production release gates on the same candidate and create the final non-WIP 7.1 checkpoint only after they pass.
