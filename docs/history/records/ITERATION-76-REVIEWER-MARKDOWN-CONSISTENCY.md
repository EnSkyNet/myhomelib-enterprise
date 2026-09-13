# Iteration 76 — Reviewer Markdown/JSON consistency hardening

Date: 2026-09-13

## Scope

Release/evidence hardening only. No production Java behavior changes and no external MHL gate closure.

## Changes

- final reviewer verifier reconstructs canonical `github-connected-acceptance.md` from the embedded GitHub JSON and requires exact UTF-8 equality;
- final reviewer verifier reconstructs canonical `v71-final-external-acceptance.md` from the embedded final-decision JSON and requires exact UTF-8 equality;
- reviewer-bundle regressions now reject contradictory GitHub/final Markdown even when the outer ZIP manifest and sidecar are recomputed;
- external readiness adds two structural ratchets for the human-readable consistency checks.

## Local validation

- final reviewer bundle regression: PASS, including GitHub Markdown tamper rejection and final-decision Markdown tamper rejection;
- production GitHub/final Markdown writers are regression-checked for byte-exact compatibility with the verifier canonical renderers;
- external readiness with regressions: `READY_FOR_LIVE_EVIDENCE`, **23 checks PASS / 8 regressions PASS**.

## Acceptance boundary

This iteration prevents human-readable evidence from disagreeing with machine-readable evidence. It does not produce live GitHub/Windows evidence and cannot close MHL-010/011/012/017/018/019.
