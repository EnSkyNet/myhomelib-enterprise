# Iteration 74 — Release-candidate integrity binding

Date: 2026-09-13

## Scope

Release/evidence hardening only. No production Java behavior changes and no external MHL gate closure.

## Changes

- added `tools/release-candidate-integrity.py` + regression test;
- CI Release now creates and re-verifies one integrity record per platform after checksums/artifact validation and uploads the record + SHA-256 sidecar with that platform artifact;
- the record binds exact candidate Git SHA, formal Maven version, deterministic source-tree digest, critical policy-file hashes and every checksummed `dist/` file size/digest;
- unchecksummed release payload is rejected;
- GitHub connected acceptance now requires and verifies the Windows integrity record against the exact CI Release `head_sha` and candidate artifacts;
- supply-chain/readiness policy gates now ratchet this contract and PR CI runs the new regression test.

## Validation boundary

The integrity JSON is not a signature, SLSA provenance statement, SBOM replacement or external PASS. It strengthens exact-candidate handoff only. Real GitHub Actions evidence, CycloneDX/Dependency-Check/CodeQL results and Windows acceptance remain mandatory for MHL-010/011/012/017/018/019.

## Local validation

- `release-candidate-integrity-test.py`: PASS;
- `github-connected-acceptance-test.py`: PASS with integrity candidate mismatch rejection;
- `external-acceptance-readiness.py --run-regressions`: `READY_FOR_LIVE_EVIDENCE`, 18 checks PASS, 8 regressions PASS;
- supply-chain policy check: PASS;
- actual source-tree integrity smoke: generation + verify PASS on the Iteration 74 working tree with synthetic checksummed dist payload.
