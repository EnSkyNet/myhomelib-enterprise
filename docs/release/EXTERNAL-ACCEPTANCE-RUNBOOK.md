# External acceptance runbook — MHL-010/011/012/017/018/019

This runbook is the handoff procedure for the six remaining release gates. It deliberately separates **readiness** from **acceptance**. Offline tools may prove that the evidence harness is structurally sound; they cannot create GitHub or Windows PASS evidence.

> **Do not mark any external gate PASS until the final candidate-bound evidence set validates successfully.**

## 1. Freeze the exact candidate

Use one Git commit SHA as the release candidate. Do not mix evidence from different commits, rerolled binaries or different Windows sessions. The Maven release identity currently comes from `pom.xml` (`7.1.0`); the `v71-*` script names are compatibility-stable entrypoints for that formal release identity.

Before dispatching remote jobs, run locally:

```bash
python3 tools/external-acceptance-readiness.py --run-regressions \
  --out-json target/external-acceptance-readiness/readiness.json \
  --out-md target/external-acceptance-readiness/readiness.md
```

Expected result: `READY_FOR_LIVE_EVIDENCE`. All six gates must still be reported as `OPEN_EXTERNAL`.

## 2. MHL-010 — live PR CI enforcement and timing

On GitHub for the exact repository/candidate line:

1. Ensure the default branch requires the **Fast gate** status check through repository rules or branch protection.
2. Accumulate at least the configured number of successful pull-request `Fast gate` runs (default connected-acceptance minimum: 5).
3. The median Fast gate duration must satisfy the policy limit used by `tools/github-connected-acceptance.py`.
4. Do not substitute push-only runs for the pull-request sample.

The `GitHub connected acceptance` workflow validates this live through the GitHub API.

## 3. MHL-017 / MHL-018 / MHL-019 — live supply-chain and SAST evidence

For the exact candidate SHA:

1. Ensure CodeQL has successfully analyzed that exact candidate and there are no open blocking High/Critical alerts.
2. Run **CI Release** (`.github/workflows/ci-release.yml`) for the exact candidate.
3. The release run must succeed and publish:
   - `myhomelib-supply-chain` with CycloneDX `bom.json` and `bom.xml`;
   - Dependency-Check HTML/JSON/SARIF reports under the configured CVSS blocking policy;
   - candidate-bound CodeQL release-gate JSON;
   - `myhomelib-windows` containing the expected MSI, EXE and portable ZIP plus checksums;
   - `release-candidate-integrity-windows.json` plus its SHA-256 sidecar, bound to the exact release-run commit SHA and the complete `SHA256SUMS` payload.
4. GitHub artifact SHA-256 digests must verify before any files are trusted locally. Connected acceptance also verifies the Windows integrity record's candidate SHA, formal version, platform, checksum-manifest hash, per-file digests/sizes and deterministic dist-manifest digest.

Then dispatch **GitHub connected acceptance** (`.github/workflows/github-acceptance.yml`) from the same candidate, preferably supplying the successful CI Release run id. The workflow binds MHL-010/017/018/019 evidence to the candidate SHA and Windows candidate hashes.

## 4. Move connected evidence to the real Windows host

On the Windows machine that will perform final acceptance, start from the exact candidate checkout and use a **standard/non-elevated user**.

Preferred entrypoint:

```powershell
.\tools\v71-windows-acceptance-start.ps1 `
  -Repo "OWNER/REPO" `
  -AcceptanceRunId <GITHUB_CONNECTED_ACCEPTANCE_RUN_ID> `
  -PreviousMsi <PATH_TO_REAL_PREVIOUS_RELEASE_MSI> `
  -PreviousVersion <REAL_PREVIOUS_VERSION>
```

This path must:

- re-download the connected-acceptance artifact through GitHub;
- verify the GitHub-declared artifact digest;
- verify `acceptance-harness.sha256` against the local harness;
- preserve and revalidate the exact `release-candidate-integrity-windows.json`, its SHA-256 sidecar and release `SHA256SUMS`;
- stage the exact candidate-bound MSI/EXE/portable artifacts;
- create a single Windows host/user/session binding record;
- run the real previous-version packaging lifecycle and launch interactive desktop acceptance.

A locally copied/repacked ZIP is not sufficient for final PASS because final acceptance requires digest-verified GitHub ingest evidence.

## 5. MHL-012 — Windows packaging and desktop acceptance

On the **same bound Windows host/user/session**:

- MSI: install, first start, upgrade from the supplied real previous MSI, uninstall, user-data preservation and documented uninstall behaviour.
- Portable: extract/run from an ordinary-user Unicode path; data must remain portable and writes must not escape into redirected profile/CWD locations.
- EXE/Desktop: run the candidate-bound per-user EXE and complete the release desktop checklist, including existing-data migration/retention observations.

All evidence must remain bound to the candidate hashes recorded by GitHub connected acceptance.

## 6. MHL-011 — Windows DPI acceptance

On the **same bound Windows host/user/session**, perform the critical-screen acceptance at each DPI scale:

- 100%
- 125%
- 150%
- 200%

For each scale, record the required checklist rows and real screenshot evidence. Reusing the same screenshot content for different checks/scales is rejected by the evidence validator.

## 7. Validate and finalize the six-gate evidence set

After all Windows checks are complete, run:

```powershell
.\tools\v71-finalize-external-acceptance.ps1
```

The finalizer revalidates strict Windows evidence, candidate/hash/session cohesion, connected GitHub evidence and reviewer-bundle integrity. The final immutable reviewer ZIP includes the bound candidate manifest, the original Windows release `SHA256SUMS`, `release-candidate-integrity-windows.json` + sidecar, nested Windows evidence and the consolidated decision. `tools/v71-final-evidence-bundle-check.py` independently verifies the embedded integrity record against the GitHub-derived candidate SHA/source/dist digests before the package can PASS. The final evidence aggregator is `tools/v71-final-external-acceptance-check.py`.

Only a successful final candidate-bound aggregation can support closing:

- MHL-010
- MHL-011
- MHL-012
- MHL-017
- MHL-018
- MHL-019

## 8. Failure handling

If any live step fails, keep the relevant gate OPEN. Fix the repository or environment, create/reuse an appropriate exact candidate, and regenerate the affected evidence. Never edit a PASS JSON or screenshot bundle by hand to make validation succeed; the evidence set is hash-, candidate- and session-bound.

## Reviewer consistency note

The final reviewer verifier does not treat Markdown as decorative or trusted prose. `github/github-connected-acceptance.md` and `final/v71-final-external-acceptance.md` must be exact canonical projections of their corresponding JSON records. Any contradictory status/check/evidence text fails the final bundle verifier even if outer hash manifests were recomputed.
