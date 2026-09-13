# Iteration 17 — GitHub connected acceptance harness (WIP)

Date: 2026-09-06  
Baseline: Iteration 16 WIP3  
Backlog scope: MHL-010, MHL-017, MHL-018, MHL-019.

## Result

This iteration removes the remaining *local* ambiguity around the four GitHub-backed 7.1 Final acceptance items. It does **not** claim connected GitHub PASS from the offline handoff environment. Instead, one manually dispatched workflow now collects the required live evidence and fails closed when any criterion is missing.

The acceptance implementation follows GitHub REST API version `2026-03-10` and uses only read permissions for repository metadata/rules, Actions artifacts/history and code-scanning evidence.

## 1. `tools/github-connected-acceptance.py`

The new collector validates four external facts:

### MHL-010 — PR CI is actually enforced and fast

1. Reads the active rules that apply to the repository default branch and requires a `required_status_checks` rule containing **Fast gate**.
2. Falls back to legacy branch-protection required-status-checks when the repository does not expose the requirement through a ruleset.
3. Reads completed pull-request runs from `ci-pr.yml`.
4. Reads the real job timestamps for `Fast gate` from each selected run.
5. Requires at least 5 successful samples by default and calculates the median from real GitHub-hosted job time.
6. Fails when median `Fast gate` duration exceeds 600 seconds.

This closes the evidence gap that a source archive cannot prove: repository-side merge enforcement and hosted-run timing history.

### MHL-017 — real release SBOM artifact

The collector selects an explicitly supplied successful CI Release run or the latest successful `ci-release.yml` run, requires one non-expired `myhomelib-supply-chain` artifact and verifies:

- GitHub reports a SHA-256 artifact digest;
- the downloaded artifact ZIP contains exactly one `bom.json` and one `bom.xml`;
- JSON identifies CycloneDX 1.6 and has components;
- XML is a CycloneDX BOM and has components.

### MHL-018 — real Dependency-Check evidence

The same live supply-chain artifact must contain:

- `dependency-check-report.json`;
- `dependency-check-report.sarif`;
- `dependency-check-report.html`.

Every JSON report is parsed, must expose a dependency array, and at least one dependency must have been scanned. A successful `ci-release.yml` supply-chain job remains the authoritative CVSS-threshold outcome because Maven Dependency-Check itself is configured to fail the build at the policy threshold.

### MHL-019 — real CodeQL analysis + release gate

The collector requires:

- a successful CodeQL analysis for the default branch;
- `rules_count > 0`;
- analysis age no greater than 14 days by default;
- no currently open code-scanning alert whose `security_severity_level` is `high` or `critical`.

The same blocking function is used by release CI through `--codeql-release-gate-only`. Therefore the release gate and acceptance evidence no longer use two independent implementations.

## 2. Safe Actions artifact download

GitHub's artifact download endpoint redirects to short-lived blob storage. The collector deliberately stops the authenticated API redirect, reads the `Location` header, then downloads the blob **without** forwarding `GITHUB_TOKEN` to the storage host.

## 3. Offline regression ratchet

`tools/github-connected-acceptance-test.py` proves without network access that:

- required `Fast gate` contexts are recognized;
- insufficient PR history fails closed;
- median >10 minutes fails closed;
- a valid synthetic CycloneDX + Dependency-Check artifact passes;
- malformed ZIP evidence fails;
- stale/wrong-branch CodeQL evidence fails;
- a synthetic open High alert triggers the same release blocking function used by CI.

The test is executed by the ordinary PR `Fast gate`.

## 4. Connected workflow

`.github/workflows/github-acceptance.yml` is a `workflow_dispatch` acceptance workflow with:

- `contents: read`;
- `actions: read`;
- `security-events: read`.

It runs the offline regression first and then live evidence collection. Evidence is always uploaded as:

```text
target/github-connected-acceptance/github-connected-acceptance.json
target/github-connected-acceptance/github-connected-acceptance.md
```

The JSON is the machine-readable authority; the Markdown file is the reviewer summary.

## 5. Release CI hardening

The former inline `actions/github-script` CodeQL alert filter in `ci-release.yml` was replaced by:

```text
python3 tools/github-connected-acceptance.py \
  --repo "$GITHUB_REPOSITORY" \
  --codeql-release-gate-only \
  --out-dir target/github-release-codeql-gate
```

The generated gate evidence is retained inside `myhomelib-supply-chain` together with SBOM and Dependency-Check reports.

## 6. Local verification

Completed in the supplied offline environment:

```text
python3 tools/github-connected-acceptance-test.py     PASS
python3 tools/supply-chain-policy-check.py            PASS
python3 tools/static_release_check.py                  PASS
Workflow YAML parse: ci-pr / ci-release / codeql / github-acceptance / performance-baseline PASS
```

No Java source was changed in Iteration 17.

## 7. Remaining external action

The four backlog items remain **Очікує GitHub-перевірки** until a real repository run produces `Overall: PASS`.

Run from GitHub Actions → **GitHub connected acceptance** → **Run workflow**. Leave `release_run_id` blank to use the latest successful CI Release run, or supply an exact successful release-run id to bind the evidence to that run.

A genuine PASS can then be used to update MHL-010/MHL-017/MHL-018/MHL-019 to completed. Until that artifact exists, no connected PASS is claimed.

## 8. Final six-item external evidence aggregator

`tools/v71-final-external-acceptance-check.py` is the final post-evidence ratchet for the six externally proven 7.1 Final items:

```text
MHL-010, MHL-011, MHL-012, MHL-017, MHL-018, MHL-019
```

It does not create evidence. It requires the connected GitHub JSON to contain PASS rows for all four GitHub checks, re-runs the strict Windows installer/portable/DPI validator with standard-user + real-previous-MSI requirements, validates the SHA-256 sidecar of `windows-final-acceptance-evidence.zip`, then emits:

```text
target/v71-final-external-acceptance/v71-final-external-acceptance.json
target/v71-final-external-acceptance/v71-final-external-acceptance.md
```

This is the single final external decision record to use before reconciling the six backlog rows.

`tools/v71-final-external-acceptance-check-test.py` covers the GitHub evidence schema/check set, Windows archive sidecar verification and PASS/FAIL output behavior. The PR Fast gate runs this regression alongside the connected GitHub acceptance regression.
