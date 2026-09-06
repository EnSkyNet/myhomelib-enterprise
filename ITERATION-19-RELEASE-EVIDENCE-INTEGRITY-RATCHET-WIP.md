# Iteration 19 — release evidence integrity ratchet (WIP)

Date: 2026-09-06

## Goal

Close the remaining integrity gaps around the externally evidenced 7.1 Final release flow without claiming the live GitHub or Windows acceptance itself.

The six external backlog items remain externally gated:

- GitHub: MHL-010, MHL-017, MHL-018, MHL-019;
- Windows: MHL-011, MHL-012.

## Changes

### 1. Exact-candidate CodeQL is now a release precondition

`ci-release.yml` now calls:

```text
python3 tools/github-connected-acceptance.py \
  --repo "$GITHUB_REPOSITORY" \
  --expected-sha "$GITHUB_SHA" \
  --codeql-release-gate-only
```

The release gate no longer accepts merely a globally clear High/Critical alert set. It also requires a successful CodeQL analysis on the default branch whose `commit_sha` is the exact release-candidate SHA.

The resulting candidate-bound CodeQL JSON/Markdown remains in `myhomelib-supply-chain` and is revalidated by connected acceptance.

### 2. GitHub Actions artifact digests are verified against downloaded bytes

`tools/github-connected-acceptance.py` now hashes the actually downloaded artifact ZIP bytes for:

- `myhomelib-supply-chain`;
- `myhomelib-windows`.

The calculated SHA-256 must equal the `sha256:<hex>` digest returned by the GitHub Actions artifact API before SBOM/SCA or Windows-candidate evidence is accepted.

### 3. Connected supply-chain evidence must include candidate CodeQL gate evidence

`validate_supply_chain_artifact()` now requires exactly one:

```text
target/github-release-codeql-gate/github-connected-acceptance.json
```

and requires:

- schemaVersion 2;
- `overall = PASS`;
- one `MHL-019-release-gate = PASS` row;
- `candidateSha` equal to the selected CI Release `head_sha`.

### 4. Windows final ZIP is independently strict-validated

`tools/v71-final-external-acceptance-check.py` no longer treats the nested Windows ZIP as only a manifest/hash container.

After validating its sidecar and exact SHA-256 manifest, it extracts the archive into a safe temporary tree and reruns:

- strict standard-user/real-previous installer evidence validation;
- portable Unicode/isolation evidence validation;
- all four 100/125/150/200 DPI reports and screenshot evidence validation.

The archive must also contain the reviewer Markdown reports, not only JSON.

### 5. Immutable final reviewer bundle checker

Added:

```text
tools/v71-final-evidence-bundle-check.py
tools/v71-final-evidence-bundle-check-test.py
```

The checker verifies:

- outer ZIP SHA-256 sidecar;
- exact outer `manifest.sha256` member set and every file hash;
- connected GitHub schema-v2 PASS evidence;
- exact two-entry candidate MSI/portable manifest and its hash binding;
- nested Windows ZIP + sidecar and full strict self-validation;
- final consolidated PASS record and the exact six backlog item IDs;
- candidate SHA, release run ID/URL, MSI SHA and portable SHA consistency across all layers.

`v71-finalize-external-acceptance.ps1` now requires the GitHub Markdown and candidate manifest, builds the reviewer bundle, and runs this checker before printing final PASS.

### 6. PR/static ratchet

PR CI now runs the immutable reviewer bundle regression. `supply-chain-policy-check.py` requires:

- exact release SHA passed to the CodeQL release gate;
- retained CodeQL gate evidence;
- reviewer-bundle regression in PR CI.

## Local verification

PASS:

```text
python3 tools/github-connected-acceptance-test.py
python3 tools/v71-final-external-acceptance-check-test.py
python3 tools/v71-final-evidence-bundle-check-test.py
python3 tools/windows-acceptance-evidence-check-test.py
python3 tools/windows-acceptance-harness-check.py
python3 tools/supply-chain-policy-check.py
python3 tools/static_release_check.py
```

All five `.github/workflows/*.yml` files parse as YAML.

Production package:

```text
./mvnw -o -B -ntp -Dmaven.repo.local=<offline-repo> -DskipTests -Pproduction package
BUILD SUCCESS — 13/13 modules
```

A single full `verify` invocation exceeded the execution limit after completing modules through OPDS with no failures. The remaining reactor tail was then run from `myhomelib-bootstrap` after seeding the already-built local MyHomeLib dependency artifacts into the offline repository:

```text
myhomelib-bootstrap ................. SUCCESS
myhomelib-mcp ....................... SUCCESS
myhomelib-architecture-tests ........ SUCCESS — 12 tests, 0 failures/errors
myhomelib-e2e-tests ................. SUCCESS — 10 tests, 0 failures/errors
myhomelib-benchmark ................. SUCCESS — performance baseline intentionally skipped by default
BUILD SUCCESS
```

The first portion had already completed, among others:

- `myhomelib-ui`: 43 tests, 0 failures/errors;
- `myhomelib-opds`: 14 tests, 0 failures/errors.

No production Java source was changed in Iteration 19.

## Status

This checkpoint **does not claim live external PASS**.

Required remaining actions are unchanged:

1. real GitHub `CI Release` on the intended candidate;
2. real `GitHub connected acceptance` on the same SHA/run;
3. clean standard-user Windows packaging acceptance using a real previous MSI and the bound current candidate;
4. interactive Windows DPI acceptance at 100/125/150/200%;
5. `v71-finalize-external-acceptance.ps1` ending in PASS;
6. only then mark MHL-010/MHL-011/MHL-012/MHL-017/MHL-018/MHL-019 complete.
