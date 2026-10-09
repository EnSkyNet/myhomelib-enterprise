# MyHomeLib — Next Tasks after V17

Date: 2026-09-28  
Use `myhomelib-enterprise-optimized-v17-checkpoint-2026-09-28.zip` as the only baseline for the next session.

## P0 — exact Maven 3.9.16 acceptance

The supplied offline Maven distribution is 3.9.6; the wrapper contract is 3.9.16.

When Maven 3.9.16 is available, run in a clean UTF-8 environment:

1. `./mvnw -v` and verify 3.9.16 exactly.
2. Full `clean verify` under Xvfb where required.
3. Compare aggregate results to V17 reference: 1789 tests, 0 failures/errors, 17 skipped.
4. Production package with the production profile.
5. Linux `jpackage` app-image.
6. Packaged launcher release smoke.
7. Stage23 artifact validation.
8. Repeat from an ASCII checkout path and a non-ASCII checkout path.

Do not reopen completed P4 extractions unless this gate exposes a concrete regression.

## P1 — Windows x64 / conversion acceptance

Validate the user-observed conversion scenario on Windows with Calibre installed:

1. long FB2 → EPUB conversion remains responsive;
2. unrelated navigation, reader state and online download remain usable during conversion;
3. user cancellation interrupts conversion deterministically;
4. timeout/cancel terminates the complete Calibre child process tree;
5. temporary `mhl-calibre-*` workspace is removable after termination;
6. no stale `CONVERSION` operation lease remains;
7. app shutdown during conversion leaves no locked `input.fb2` or orphan process.

Capture process tree and logs for any failure before changing production code.

## P2 — architecture debt

Triage the existing standalone architecture-ratchet group without metric gaming:

- `DeferredMainFeatureActions`
- `ReaderBookMapCoordinator`
- `ReaderBookmarkCoordinator`

For each proposed change: characterization → one boundary → targeted tests → full relevant module → architecture/static check.

## P2 — packaging completeness

- Decide whether libmobi/mobitool should be bundled or remain an explicitly external runtime dependency.
- Decide whether release packaging should always generate `SHA256SUMS`; Stage23 currently treats its absence as a warning.

## P3 — next technical audit focus

With the planned large-class decomposition complete, return to defect-first auditing rather than further size-only refactoring. Prioritize:

- Windows process lifecycle and cancellation;
- long-running async operation responsiveness;
- collection-switch barriers during background operations;
- file-publication/race paths on Windows;
- only then additional large-class decomposition when a concrete responsibility boundary and characterization evidence justify it.
