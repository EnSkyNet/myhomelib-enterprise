# MyHomeLib — Next Tasks after V16

Date: 2026-09-28
Baseline for the next session: `myhomelib-enterprise-optimized-v16-checkpoint-2026-09-28.zip` only.

## P0 — authoritative release gate

1. Supply/use Apache Maven **3.9.16** exactly as required by `.mvn/wrapper/maven-wrapper.properties`.
2. Run from a clean UTF-8 environment:
   - `./mvnw -v`
   - full `clean verify` under Xvfb where required.
3. Record aggregate Surefire/Failsafe counts and all skipped tests.
4. Repeat production package, Linux `jpackage` app-image, packaged `--release-smoke` and Stage23 on the exact 3.9.16 build.
5. Run from both ASCII and non-ASCII checkout paths.
6. Complete Windows x64 packaged acceptance.

## Verified current reactor baseline

Under the supplied Maven 3.9.6 with explicit UTF-8 locale and Xvfb, V16 `clean verify` is green across all 20 modules: 1789 tests, 0 failures, 0 errors, 17 skipped. Infrastructure contributes 637 tests with 9 skipped. Preserve this as comparison evidence when the exact Maven 3.9.16 gate is run.

## P4 — continue large-class decomposition

After the exact P0 Maven 3.9.16 acceptance (or an explicit decision to accept the already-green 3.9.6 gate), continue with the remaining planned class:

1. `InpxImportPipeline`
   - characterize current import/rollback/cancellation/progress semantics;
   - identify one responsibility boundary;
   - extract one component only;
   - run targeted tests;
   - run full infrastructure module;
   - run relevant Stage6/import contracts;
   - run architecture/static/release gates.

Do not perform broad renames, style-only rewrites or multiple extractions in one step.

## P1 — Windows conversion acceptance

From V15 runtime evidence, keep the following open until tested on Windows:
- Calibre child/process-tree termination on cancel/timeout;
- temporary Calibre workspace cleanup when Windows still holds `input.fb2`;
- explicit user cancellation UX for a long conversion;
- verify conversion never blocks reader state, downloads or unrelated navigation.

## Architecture debt

Triage, without metric gaming, the existing UI → non-value-domain group:
- `DeferredMainFeatureActions`
- `ReaderBookMapCoordinator`
- `ReaderBookmarkCoordinator`

No new architecture debt should be accepted while resolving it.
