# Iteration 18 — Candidate-bound external acceptance (WIP)

Date: 2026-09-06  
Baseline: Iteration 17 connected GitHub acceptance harness  
Backlog scope: MHL-010, MHL-011, MHL-012, MHL-017, MHL-018, MHL-019.

## Result

Iteration 17 could validate GitHub and Windows evidence independently, but it did not prove that the manual Windows acceptance used the **same release candidate** that passed connected GitHub supply-chain checks. Iteration 18 removes that ambiguity.

No external PASS is claimed by this checkpoint. The six remaining items still require live GitHub/Windows evidence.

## 1. GitHub evidence schema v2: exact candidate binding

`tools/github-connected-acceptance.py` now records `candidateSha` and requires the selected successful `ci-release.yml` run to have exactly the same `head_sha`.

The connected workflow passes `${{ github.sha }}` as `--expected-sha`, so a stale successful release run can no longer satisfy acceptance for a newer checkout.

CodeQL acceptance is also filtered to a successful analysis for the same candidate commit, not merely any recent analysis on the default branch.

## 2. Windows release artifact is validated in the same CI Release run

The connected collector now also requires one non-expired `myhomelib-windows` artifact from the selected release run and validates:

- exact `MyHomeLib-<version>.msi`;
- exact `MyHomeLib-<version>.exe`;
- exactly one `myhomelib-<version>-windows-*.zip` portable archive;
- `SHA256SUMS` entries for MSI, EXE and portable archive;
- SHA-256 equality between the published files and `SHA256SUMS`.

The GitHub evidence records `windowsMsiSha256` and `windowsPortableSha256`.

For operator handoff the collector also extracts the exact MSI + portable ZIP into:

```text
target/github-connected-acceptance/candidate-windows/
```

with `candidate-windows.sha256`. These files are uploaded inside the ordinary connected-acceptance artifact.

## 3. Release packaging bug fixed: MSI remains publishable

The automated installer lifecycle previously moved the freshly built current MSI from `dist` into `target/windows-installer-acceptance`. That meant release validation could pass because an EXE existed while the Windows publication artifact had no MSI.

The current MSI is now **copied** into acceptance evidence and remains in `dist` for checksums/publication.

`tools/stage23-cross-platform-release-check.py` gained:

```text
--expect-windows-msi
--expect-windows-exe
```

and Windows release CI requires both exact versioned candidates.

## 4. Bound Windows packaging runner

`tools/windows-bound-packaging-acceptance.ps1` is the final MHL-012 operator entrypoint before DPI acceptance. It:

1. requires a standard/non-elevated Windows user;
2. validates GitHub schema-v2 evidence;
3. takes the current MSI + portable archive from the connected evidence artifact by default;
4. verifies both hashes match the selected GitHub release candidate;
5. runs real previous -> bound current MSI install/upgrade/reinstall/uninstall acceptance;
6. runs the bound portable Unicode/isolation smoke;
7. runs strict packaging evidence validation with a real previous MSI;
8. writes `target/windows-bound-packaging-preflight/` evidence.

It deliberately leaves DPI as pending.

## 5. Final six-item candidate-bound aggregator

`tools/v71-final-external-acceptance-check.py` now requires GitHub evidence schema v2 and rejects:

- missing/invalid `candidateSha`;
- release-run `headSha` different from `candidateSha`;
- missing candidate MSI/portable SHA-256 values;
- Windows root evidence whose current MSI hash differs from the GitHub candidate;
- Windows root evidence whose portable hash differs from the GitHub candidate;
- Windows final archive with an invalid sidecar;
- unsafe/duplicate ZIP members;
- missing required evidence files;
- archive `manifest.sha256` mismatch;
- archive MSI/portable hashes different from the GitHub candidate.

A successful final decision now includes a `candidate-binding` PASS row containing the commit SHA, release run and both Windows artifact hashes.

## 6. One-command final evidence packaging

After all four DPI runs pass, `tools/v71-finalize-external-acceptance.ps1`:

1. re-runs strict Windows evidence validation;
2. builds the immutable Windows evidence ZIP;
3. runs the six-item candidate-bound final external gate;
4. stages GitHub, Windows and final-decision evidence;
5. creates `target/myhomelib-7.1-final-external-evidence.zip` and a SHA-256 sidecar.

This is the reviewer/audit handoff package after a genuine live PASS.

## 7. Offline regression ratchet

Updated regression/static checks cover:

- candidate SHA normalization and mismatch rejection;
- exact-candidate CodeQL filtering;
- Windows release-artifact checksums and non-empty MSI/EXE/portable payloads;
- release-run SHA mismatch rejection;
- GitHub schema-v1 rejection by the final aggregator;
- candidate MSI/portable binding;
- Windows archive manifest and sidecar validation;
- presence of the bound Windows operator/finalizer scripts;
- explicit MSI + EXE release requirements.


### Local verification on 2026-09-06

Completed successfully in the offline Linux workspace:

- `python3 tools/github-connected-acceptance-test.py` — PASS;
- `python3 tools/v71-final-external-acceptance-check-test.py` — PASS;
- `python3 tools/windows-acceptance-evidence-check-test.py` — PASS;
- `python3 tools/windows-acceptance-harness-check.py` — PASS;
- `python3 tools/supply-chain-policy-check.py` — PASS;
- `python3 tools/static_release_check.py` — PASS;
- all five GitHub workflow YAML files parse successfully;
- synthetic Stage23 Windows release contract passes with MSI+EXE+portable+checksums and fails closed when the MSI is removed;
- offline `mvn package -Pproduction -DskipTests` — **BUILD SUCCESS, 13/13 modules**.

A full Iteration-18 `mvn verify -Pproduction` was also attempted twice, but the execution environment stopped each run on its wall-clock limit rather than a Maven/test failure. Before the second stop, `myhomelib-infrastructure` completed **275 tests, 0 failures, 0 errors, 6 skipped** and `myhomelib-reader` completed **40 tests, 0 failures, 0 errors**; the reactor had reached `myhomelib-ui` (module 7/13). This checkpoint therefore does **not** claim a fresh full-reactor test PASS. The earlier Iteration-16 WIP2 baseline had a complete 13/13 `verify` PASS; Iteration 18 changes are acceptance/release tooling, workflow and documentation rather than production Java behavior.

The connected acceptance job timeout is 20 minutes because it now downloads and validates both supply-chain and Windows release artifacts. This timeout is unrelated to the MHL-010 PR `Fast gate` runtime target.

## 8. Remaining external action

Still not locally claimable:

- MHL-010 / MHL-017 / MHL-018 / MHL-019: run connected GitHub acceptance on the exact release candidate commit.
- MHL-011 / MHL-012: run the extracted bound candidate on a real interactive standard-user Windows host at 100/125/150/200% DPI.

Only `tools/v71-finalize-external-acceptance.ps1` completing successfully after those live runs is sufficient to create the final non-WIP 7.1 evidence checkpoint.
