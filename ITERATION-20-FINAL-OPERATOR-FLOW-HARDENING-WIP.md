# Iteration 20 — final operator flow hardening (WIP)

Date: 2026-09-06

## Goal

Remove the remaining operator/evidence gaps before the live GitHub + Windows acceptance without claiming the live PASS itself.

The six external backlog items remain externally gated:

- GitHub: MHL-010, MHL-017, MHL-018, MHL-019;
- Windows: MHL-011, MHL-012.

## Changes

### 1. Windows candidate binding now includes the published EXE

`tools/github-connected-acceptance.py` now extracts and hashes all three candidate artifacts from the exact `myhomelib-windows` CI Release artifact:

- `MyHomeLib-<version>.msi`;
- `MyHomeLib-<version>.exe`;
- `myhomelib-<version>-windows-<arch>.zip`.

`candidate-windows.sha256` therefore has exactly three entries. Final GitHub/Windows/reviewer gates require the EXE hash as well as MSI/portable hashes.

### 2. Digest-verified GitHub acceptance artifact ingest

Added:

```text
tools/github-acceptance-artifact-ingest.py
tools/github-acceptance-artifact-ingest-test.py
```

Final mode accepts the exact **GitHub connected acceptance** workflow run id, fetches its artifact through the Actions API, verifies the API-declared `sha256:` digest against downloaded ZIP bytes, rejects unsafe traversal/symlink/duplicate/oversized members, validates the connected evidence schema and exact candidate manifest, then atomically stages `target/github-connected-acceptance`.

It writes `github-connected-acceptance-ingest.json`. Final acceptance requires `remoteDigestVerified=true`; a manually copied local artifact is rehearsal-only.

### 3. Real desktop release acceptance is now explicit evidence

Added:

```text
tools/windows-release-desktop-acceptance.ps1
```

This closes the mismatch between the formal release boundary and the previous six-item finalizer. Under a standard/non-elevated user it binds the exact candidate EXE to GitHub evidence and captures screenshot-backed PASS/FAIL/BLOCKED evidence for:

- interactive EXE installer UI;
- first start;
- representative real previous-version profile/data migration;
- collection browse/search/details;
- online book download;
- Reader lifecycle;
- backup/restore round-trip.

`tools/windows-acceptance-evidence-check.py` now has `verify_release_desktop()` / `--release-desktop`, including unique screenshot-content checks and required notes for migration/download/backup-restore evidence.

### 4. One Windows start entrypoint

Added:

```text
tools/v71-windows-acceptance-start.ps1
```

It performs, in order:

1. connected artifact fetch + digest/safe-ingest;
2. bound real-previous MSI + portable acceptance;
3. bound EXE/data-migration desktop acceptance.

After it passes, only the four interactive DPI runs and the finalizer remain.

### 5. Final reviewer bundle strengthened

The final external gate/reviewer bundle now requires and cross-checks:

- GitHub connected JSON;
- GitHub digest-verified ingest record and acceptance run id/URL;
- exact three-entry MSI/EXE/portable candidate manifest;
- real Windows installer + portable + desktop + DPI evidence;
- nested Windows bundle checksum/manifest/self-validation;
- candidate binding across CI Release run, connected acceptance run and all three Windows artifact hashes.

### 6. PR/static ratchet

PR CI now runs the GitHub artifact-ingest regression. `windows-acceptance-harness-check.py`, `supply-chain-policy-check.py` and `static_release_check.py` require the new ingest/EXE/desktop contracts so they cannot be silently removed.

## Local verification

PASS:

```text
python3 tools/github-connected-acceptance-test.py
python3 tools/github-acceptance-artifact-ingest-test.py
python3 tools/windows-acceptance-evidence-check-test.py
python3 tools/v71-final-external-acceptance-check-test.py
python3 tools/v71-final-evidence-bundle-check-test.py
python3 tools/windows-acceptance-harness-check.py
python3 tools/supply-chain-policy-check.py
python3 tools/static_release_check.py
```

All five `.github/workflows/*.yml` files parse as YAML. The final evidence-bundle regression intentionally emits FAIL diagnostics for its negative tamper fixtures; the regression process itself exits PASS.

Production package:

```text
./mvnw -o -B -ntp -Dmaven.repo.local=<offline-repo> -DskipTests -Pproduction package
BUILD SUCCESS — 13/13 modules
```

The execution environment limits an individual command to about 45 seconds, so one uninterrupted production `verify` could not finish. Verification was completed without test failures by combining the initial reactor run with explicit UI/OPDS runs and a resumed bootstrap-to-benchmark tail after seeding the already-built local reactor artifacts into the offline repository. Final Surefire evidence in this checkpoint is:

```text
myhomelib-shared                 12 tests, 0 failures/errors
myhomelib-domain                  9 tests, 0 failures/errors
myhomelib-application           127 tests, 0 failures/errors, 1 skipped
myhomelib-infrastructure        275 tests, 0 failures/errors, 6 skipped
myhomelib-reader                 40 tests, 0 failures/errors
myhomelib-ui                     43 tests, 0 failures/errors
myhomelib-opds                   14 tests, 0 failures/errors
myhomelib-bootstrap              15 tests, 0 failures/errors
myhomelib-mcp                     6 tests, 0 failures/errors
myhomelib-architecture-tests     12 tests, 0 failures/errors
myhomelib-e2e-tests              10 tests, 0 failures/errors
myhomelib-benchmark               1 test, 0 failures/errors, 1 skipped
```

The resumed bootstrap -> benchmark reactor ended `BUILD SUCCESS`; UI and OPDS standalone production verifies also ended `BUILD SUCCESS`. This is complete module/test coverage for the checkpoint, but it is deliberately not described as one uninterrupted Maven reactor run.

PowerShell is not installed in this Linux execution environment, so the new `.ps1` entrypoints could not be runtime-executed here; their structural contracts are covered by the Python/static ratchets and final execution remains part of the real Windows acceptance.

No production Java source was changed in Iteration 20.

## Status

This checkpoint **does not claim live external PASS**. Required remaining actions are now reduced to:

1. real CI Release + GitHub connected acceptance on the intended candidate;
2. run `v71-windows-acceptance-start.ps1` on a clean standard-user Windows host with a real previous MSI;
3. complete 100/125/150/200% DPI evidence;
4. run `v71-finalize-external-acceptance.ps1` to PASS;
5. only then reconcile MHL-010/MHL-011/MHL-012/MHL-017/MHL-018/MHL-019 and create the final non-WIP 7.1 checkpoint.
