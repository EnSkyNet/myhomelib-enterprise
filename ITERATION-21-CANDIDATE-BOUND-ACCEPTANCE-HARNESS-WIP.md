# Iteration 21 — candidate-bound acceptance harness (WIP)

Date: 2026-09-06

## Goal

Close the remaining integrity gap where the correct release candidate could theoretically be accepted using Windows acceptance scripts from a different checkout/commit.

The six externally gated 7.1 Final items remain unchanged:

- GitHub: MHL-010, MHL-017, MHL-018, MHL-019;
- Windows: MHL-011, MHL-012.

## Changes

### 1. Candidate-bound acceptance harness manifest

Added `tools/windows-acceptance-harness-binding.py` and its regression test.

The exact candidate checkout produces `acceptance-harness.sha256` covering the critical Windows final-acceptance scripts and validators. The connected GitHub JSON records the SHA-256 of that manifest.

### 2. Digest-verified GitHub ingest retains the harness manifest

`tools/github-acceptance-artifact-ingest.py` now requires `acceptance-harness.sha256`, verifies its hash against connected GitHub evidence and stages it only after the existing remote Actions artifact digest and candidate checks pass.

### 3. Windows acceptance refuses a different harness checkout

`tools/v71-windows-acceptance-start.ps1` now performs four stages. Stage 2 verifies the local repository files against the staged candidate manifest and writes `target/windows-harness-binding/windows-harness-binding.json`. Any hash mismatch is a hard failure before installer/portable/desktop evidence is produced.

### 4. Final decision and reviewer bundle revalidate harness identity

The final external gate requires the harness binding record and cross-checks:

- candidate SHA;
- candidate-bound harness manifest SHA-256;
- exact manifest file/hash set;
- local binding JSON file/hash set.

The immutable reviewer ZIP now contains both `github/acceptance-harness.sha256` and `windows/windows-harness-binding.json` and independently revalidates them.

### 5. Exact EXE launch for desktop acceptance

`windows-release-desktop-acceptance.ps1` now launches the already SHA-256-verified bound candidate EXE itself before P5-01. The tester no longer manually selects an installer executable.

### 6. CI/static ratchet

PR CI now runs `windows-acceptance-harness-binding-test.py`. Static/supply-chain/harness guards require the new manifest and binding contracts so they cannot be silently removed.

## Local verification

PASS:

```text
python3 tools/windows-acceptance-harness-binding-test.py
python3 tools/github-connected-acceptance-test.py
python3 tools/github-acceptance-artifact-ingest-test.py
python3 tools/v71-final-external-acceptance-check-test.py
python3 tools/v71-final-evidence-bundle-check-test.py
python3 tools/windows-acceptance-evidence-check-test.py
python3 tools/windows-acceptance-harness-check.py
python3 tools/supply-chain-policy-check.py
python3 tools/static_release_check.py
```

The reviewer-bundle regression intentionally prints FAIL diagnostics for negative tamper fixtures; the regression test process itself exits PASS.

No production Java source was changed in Iteration 21.

## Status

This checkpoint does **not** claim live external PASS. Remaining live actions are still:

1. exact-candidate CI Release + GitHub connected acceptance;
2. clean standard-user Windows `v71-windows-acceptance-start.ps1` using the same candidate checkout and a real previous MSI;
3. DPI 100/125/150/200 evidence;
4. `v71-finalize-external-acceptance.ps1` ending PASS;
5. then reconcile the six external backlog items and create the final non-WIP 7.1 checkpoint.

Additional verification:

```text
All five .github/workflows/*.yml parse as YAML.
./mvnw -o -B -ntp -Dmaven.repo.local=<offline-repo> -DskipTests -Pproduction package
BUILD SUCCESS — 13/13 modules, 20.088 s.
```

Iteration 21 changes only acceptance/CI/documentation code; production Java sources remain identical to Iteration 20, whose full module test coverage was 564 tests with 0 failures/errors (8 skipped).

Checkpoint hygiene before handoff:

```text
clean source files: 1529
target/__pycache__/*.pyc/*.log entries: 0
clean-staging regression/static/YAML ratchet: PASS
ZIP integrity (unzip -t): PASS
```
