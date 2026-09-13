# Iteration 75 — Final reviewer evidence self-containment

Date: 2026-09-13

## Scope

Release/evidence hardening only. No production Java behavior changes and no external MHL gate closure.

## Changes

- GitHub connected acceptance now preserves the exact Windows `release-candidate-integrity-windows.json`, its SHA-256 sidecar and original release `SHA256SUMS` beside the staged candidate binaries;
- safe connected-acceptance artifact ingest requires that exact evidence set, validates it against the remotely digest-verified artifact and carries its digests into the ingest record;
- the final external decision includes the integrity/checksum binding fields in its candidate-binding evidence;
- `v71-finalize-external-acceptance.ps1` embeds the candidate integrity JSON, sidecar and release checksum manifest in the immutable reviewer bundle;
- `v71-final-evidence-bundle-check.py` independently revalidates the embedded record and rejects semantic tampering even when inner/outer hash manifests are recomputed;
- external readiness now ratchets this self-contained reviewer-bundle contract.

## Local validation

- GitHub connected acceptance regression: PASS;
- connected-acceptance artifact ingest regression: PASS, including candidate-integrity tamper rejection;
- final six-gate external decision regression: PASS;
- final reviewer evidence bundle regression: PASS, including recomputed-sidecar/outer-manifest tamper rejection;
- external readiness with regressions: `READY_FOR_LIVE_EVIDENCE`, **21 checks PASS / 8 regressions PASS**.

## Acceptance boundary

This iteration strengthens evidence portability and offline review only. It does not produce live GitHub/Windows evidence and cannot close MHL-010/011/012/017/018/019.
