# Iteration 08 — Supply-chain security and self-contained source packaging

Date: 2026-09-06
Baseline: Iteration 07
Backlog scope: MHL-017, MHL-018, MHL-019, MHL-020

## Result

Iteration 08 adds release/PR supply-chain controls and makes the formal source release carry its Maven launcher/toolchain contract instead of relying on an untracked local Maven installation.

### MHL-017 — SBOM

- Added a root Maven `sbom` profile based on CycloneDX Maven plugin.
- Aggregate BOM covers compile/runtime/system dependencies and excludes test/provided scope.
- Produces both `bom.json` and `bom.xml` using CycloneDX schema 1.6.
- Release CI requires both files and publishes them as supply-chain artifacts.
- Failure to generate the BOM fails the release job.

### MHL-018 — dependency vulnerability scan

- Added a root Maven `dependency-check` profile.
- Blocking threshold: CVSS >= 7.0 (High/Critical policy).
- Release and PR dependency-scan jobs preserve HTML/JSON/SARIF reports.
- Suppressions are stored in `security/dependency-check-suppressions.xml`.
- `tools/supply-chain-policy-check.py` rejects suppressions without expiry and substantive issue-linked rationale and rejects blanket severity suppressions.
- NVD API key is accepted through `NVD_API_KEY`; it is not stored in the repository.

### MHL-019 — CodeQL/SAST

- Added `.github/workflows/codeql.yml` for Java/Kotlin.
- Runs on pull requests, pushes to main/master, a weekly schedule, and manual dispatch.
- Uses GitHub CodeQL v4 actions with `security-events: write`.
- Release preflight reads open code-scanning alerts and blocks High/Critical findings before packaging.

### MHL-020 — self-contained source archive

- Restored/retained `mvnw`, `mvnw.cmd`, wrapper JAR/properties, and wrapper JAR SHA-256.
- Formal source release now includes embedded Maven 3.9.6 under `.mvn/maven/...`.
- `mvnw` launches the embedded Maven through `sh` when present, so extraction tools that lose Unix executable bits do not force a network wrapper download.
- `tools/package-v71-source.py` validates the launcher, wrapper checksum/JAR contents, clean staging tree, archive path safety, release/source policies, and upgrade patch <-> ZIP byte equivalence.
- The generated patch force-stages the otherwise ignored `.mvn` release toolchain.
- Maven distribution files are emitted as binary patch entries so original CRLF bytes in Windows launchers/config are preserved.

## Additional release-gate repairs found by rehearsal

The stronger source-release rehearsal exposed stale checks that predated this iteration:

- `tools/build-check-v7.py` expected Flyway only through V48; it now follows the actual V1..V49 chain and accepts iteration reports as intentional root documentation.
- `tools/architecture-check.py` expected `myhomelib-opds` to depend only on `myhomelib-application`; its source ratchet now matches the already-valid compiled architecture (`application` + `shared`).
- Release checks now run on a clean staging copy rather than a working tree polluted by Maven `target/` directories.

## Verification

### Existing product regression gates retained

From Iteration 07 and re-run after the Iteration 08 CI/POM changes:

- Fast core gate: 148 tests total — 147 PASS, 1 SKIP, 0 failures/errors.
- ArchUnit: 12/12 PASS.
- E2E: 10/10 PASS.

### Iteration 08 validation

- `tools/supply-chain-policy-check.py`: PASS.
- Workflow YAML parse (`ci-pr.yml`, `ci-release.yml`, `codeql.yml`, `performance-baseline.yml`): PASS.
- XML/POM/suppression parsing: PASS.
- `tools/static_release_check.py`: PASS.
- Maven reactor package, offline repository, all 13 modules: BUILD SUCCESS (final warm run 6.445 s).
- Full formal source-release rehearsal: PASS.
  - clean staged tree offline suite: PASS;
  - extracted ZIP launcher starts embedded Maven 3.9.6 without network: PASS;
  - extracted source/release policy checks: PASS;
  - generated v7 -> v7.1 binary patch applies cleanly: PASS;
  - patch <-> ZIP equivalence: PASS for 1425 files.

## Connected-CI acceptance still required

This execution environment is offline and its supplied Maven repository does not contain the CycloneDX/OWASP Dependency-Check plugin artifacts or live NVD data; it also cannot execute GitHub CodeQL services. Therefore the configuration, policy, ordinary reactor, and source-package contracts were validated locally, but the following evidence must come from a connected GitHub CI run:

- actual generated `bom.json` / `bom.xml` artifact;
- actual Dependency-Check report against current vulnerability feeds;
- CodeQL analysis upload/results and release blocking on a real alert.

No claim is made that those network-backed scans were executed locally.
