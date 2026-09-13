# Iteration 22 — Windows host/session binding (WIP)

Date: 2026-09-06

## Goal

Close the remaining accidental evidence-mixing gap in the final 7.1 Windows acceptance flow without claiming the live Windows/GitHub PASS itself.

Before this iteration, the installer, portable, real-desktop and four DPI reports each recorded host information, but the final validator did not require all of them to come from one identical Windows host, Windows user and acceptance session. In principle, individually valid reports from different machines/sessions could be combined.

The six external backlog items remain externally gated:

- GitHub: MHL-010, MHL-017, MHL-018, MHL-019;
- Windows: MHL-011, MHL-012.

## Changes

### 1. Candidate-bound Windows host/session identity

Added:

```text
tools/windows-acceptance-host.ps1
```

The helper is included in the candidate-bound `acceptance-harness.sha256` manifest and creates:

```text
target/windows-host-binding/windows-host-binding.json
```

The record contains only audit metadata and one-way hashes for stable Windows identities:

- random `acceptanceSessionId`;
- exact candidate SHA/repository;
- exact GitHub connected-acceptance run id;
- computer/user display names;
- OS version/build/architecture;
- SHA-256 of Windows `MachineGuid` under a MyHomeLib domain prefix;
- SHA-256 of the current Windows user SID under a MyHomeLib domain prefix;
- explicit non-elevated-user result.

The raw MachineGuid and raw SID are not written to evidence.

### 2. Clean acceptance session starts before Windows evidence

`tools/v71-windows-acceptance-start.ps1` now:

1. digest-verifies GitHub connected evidence;
2. verifies the exact candidate acceptance harness;
3. removes stale Windows acceptance/DPI/finalization outputs from prior sessions;
4. creates one fresh candidate-bound Windows host/session binding;
5. runs bound installer/portable acceptance;
6. runs real-desktop acceptance.

Re-running the start command intentionally creates a new session id, so earlier DPI evidence cannot be silently reused.

### 3. Every Windows report carries the same binding

The following reports now embed and inherit the verified binding:

- installer lifecycle;
- portable Unicode/isolation smoke;
- real desktop release acceptance;
- DPI 100/125/150/200 reports.

Required common fields include:

```text
acceptanceSessionId
hostFingerprintSha256
userFingerprintSha256
host
user
osVersion
osBuild
osArchitecture
```

The desktop report is additionally required to carry the same candidate SHA and repository as the host binding.

### 4. Strict host-cohesion validation

`tools/windows-acceptance-evidence-check.py` adds:

```text
verify_host_binding()
verify_host_cohesion()
--require-host-binding
```

Final/bound validation fails if any requested Windows report belongs to a different:

- acceptance session;
- computer fingerprint;
- Windows user fingerprint;
- host/user name;
- OS version/build/architecture.

Regression fixtures explicitly prove that mixed-host and mixed-session evidence fail closed.

### 5. Nested and outer reviewer evidence retain the host binding

`windows-final-evidence-pack.ps1` now includes the host binding inside the immutable Windows evidence ZIP.

`v71-final-external-acceptance-check.py` cross-checks the live evidence root and nested Windows archive for identical session/host/user fingerprints and also binds the Windows host record to the exact GitHub candidate/repository/acceptance run.

`v71-finalize-external-acceptance.ps1` also copies the host binding to the outer reviewer bundle.

`v71-final-evidence-bundle-check.py` independently verifies:

- outer host binding candidate/repository/run;
- nested Windows bundle session/host/user identity;
- final consolidated decision session/host/user identity.

A tampered outer host fingerprint fails even when the attacker/test fixture recomputes the outer ZIP and manifest SHA-256 values.

### 6. Static/PR ratchet

The shared host helper itself is part of the exact-candidate acceptance harness manifest.

Static gates now require the host/session contract in:

- Windows harness structural check;
- supply-chain policy check;
- static release check.

## Local verification

PASS:

```text
python3 tools/windows-acceptance-harness-binding-test.py
python3 tools/github-connected-acceptance-test.py
python3 tools/github-acceptance-artifact-ingest-test.py
python3 tools/windows-acceptance-evidence-check-test.py
python3 tools/v71-final-external-acceptance-check-test.py
python3 tools/v71-final-evidence-bundle-check-test.py
python3 tools/windows-acceptance-harness-check.py
python3 tools/supply-chain-policy-check.py
python3 tools/static_release_check.py
```

All five `.github/workflows/*.yml` files parse as YAML.

Production package:

```text
./mvnw -o -B -ntp -Dmaven.repo.local=<offline-repo> -DskipTests -Pproduction package
BUILD SUCCESS — 13/13 modules
Total time: 20.375 s
```

No production Java source was changed in Iteration 22.

PowerShell is not installed in this Linux execution environment, so the new Windows host helper and updated `.ps1` entrypoints cannot be runtime-executed here. Their structure, cross-platform JSON validators and tamper fixtures are covered locally; actual PowerShell execution remains part of live Windows acceptance.

## Status

This checkpoint **does not claim live external PASS**.

Remaining actions:

1. real CI Release + GitHub connected acceptance on the exact intended candidate;
2. clean standard/non-elevated Windows host using the exact candidate checkout;
3. one `v71-windows-acceptance-start.ps1` session with a real previous MSI;
4. complete DPI 100/125/150/200 on the same bound host/user/session;
5. run `v71-finalize-external-acceptance.ps1` to PASS;
6. only then mark MHL-010/MHL-011/MHL-012/MHL-017/MHL-018/MHL-019 complete and create the final non-WIP 7.1 checkpoint.
