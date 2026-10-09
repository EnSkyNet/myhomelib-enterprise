# MyHomeLib — Technical Audit Report V21

Date: 2026-09-28  
Baseline: `myhomelib-enterprise-optimized-v20-checkpoint-2026-09-28.zip`.

## Executive status

V21 closes the remaining code-controlled architecture debt named in the V20 task plan. V20 was used as the only source baseline.

No broad rewrite, dependency campaign, class-size-only cleanup, performance tuning or architecture-ratchet gaming was performed. Each architecture change was handled as one responsibility boundary at a time with characterization before the change, targeted verification after it, full relevant-module verification, full reactor verification and the standalone architecture check.

The three V20 debt classes are no longer architecture violations:

1. `DeferredMainFeatureActions` no longer exposes the domain `Collection` merely to signal whether collection properties were committed;
2. `ReaderBookMapCoordinator` no longer depends on persistence/domain `Bookmark`; it consumes reader-level `ReaderPosition` values;
3. `ReaderBookmarkCoordinator` and `NewReaderWorkspaceController` no longer consume domain `Bookmark`; the Reader UI now uses the immutable `ReaderBookmarkItem` projection while domain mapping remains inside the existing persistence boundary.

The standalone architecture ratchet now passes with no named debt group. The non-value domain-model UI-user count improved from the 28/28 baseline to 25/28 without changing the baseline threshold.

V21 is therefore code-complete for the audit scope available in this Linux environment. Two external acceptance inputs remain outside this environment: real Windows x64 + real Calibre acceptance, and a verified licensed libmobi 0.12 / `mobitool` release input.

## Boundary 1 — `DeferredMainFeatureActions`

### Characterization before change

`DeferredMainFeatureActionsTest` was added and run before the production change:

- properties dialog returns a saved collection → action reports committed;
- dialog cancellation (`null`) → action reports not committed;
- **2 tests, 0 failures/errors — PASS**.

### Minimal change

`DeferredMainFeatureActions.showCollectionProperties(...)` now returns a boolean commit signal instead of the domain `Collection` object.

`MainController` uses that signal to publish the same `NavigationRefreshEvent` and refresh navigation only when the dialog committed a change.

No collection editing behavior, persistence or navigation-refresh policy changed.

## Boundary 2 — `ReaderBookMapCoordinator`

### Characterization before change

The existing `ReaderBookMapCoordinatorTest` was green before the boundary change:

- **2 tests, 0 failures/errors — PASS**.

### Minimal change

`ReaderBookMapCoordinator` now consumes `List<ReaderPosition>` rather than `List<Bookmark>` and no longer depends on `NewReaderPersistenceService`.

`NewReaderWorkspaceController`, which already owns reader-workspace orchestration, converts bookmark projections to reader positions before invoking the map coordinator.

Chapter state, bookmark counts, annotation aggregation and passed/current chapter semantics remain unchanged.

## Boundary 3 — Reader bookmark projection

### Characterization before change

The existing `ReaderBookmarkCoordinatorTest` was green before the change:

- **2 tests, 0 failures/errors — PASS**.

### Minimal change

Added package-local Reader-facing immutable projection:

`myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/ReaderBookmarkItem.java`

It carries only the persisted bookmark fields required by Reader presentation/navigation. Domain `Bookmark` remains inside `NewReaderPersistenceService`, which is already the persistence adaptation boundary.

`ReaderBookmarkCoordinator` and `NewReaderWorkspaceController` now use `ReaderBookmarkItem`. Existing domain-oriented persistence methods were retained for compatibility; new projection methods map at the persistence boundary rather than duplicating repository logic.

### New mapping characterization

`ReaderBookmarkProjectionTest` verifies:

- text bookmark navigation/display fields are preserved and position round-trips;
- persisted legacy bookmark fields are preserved during projection;
- audio bookmark projection preserves the exact `AudioPosition` anchor.

Projection tests: **3/3 PASS**.

Combined focused Reader/Deferred set: **9 tests, 0 failures/errors**.

Expanded persistence/reader architecture set: **15 tests, 0 failures/errors**.

## Relevant downstream verification

Full UI dependency reactor (`-pl myhomelib-ui -am test`) after all three boundaries:

- shared: 41 tests, 0 failures/errors;
- application: 470 tests, 0 failures/errors, 1 skipped;
- reader: 137 tests, 0 failures/errors, 1 skipped;
- UI: **235 tests, 0 failures/errors, 0 skipped**;
- **8/8 modules SUCCESS**;
- **BUILD SUCCESS**.

One exploratory run that exported `JAVA_TOOL_OPTIONS` caused a child-Java stderr assertion to fail because the JVM prints `Picked up JAVA_TOOL_OPTIONS...`. The same downstream reactor was rerun in the normal release environment using `MAVEN_OPTS`; it passed. No production change was made for this environment-only artifact.

## Full Maven 3.9.16 verification

Environment:

- Apache Maven **3.9.16** through `./mvnw`;
- Java 21;
- `LANG=C.UTF-8`, `LC_ALL=C.UTF-8`;
- UTF-8 JVM file encoding through Maven options;
- Xvfb for JavaFX-dependent tests;
- supplied populated offline Maven repository.

Final V21 `clean verify`:

- **20/20 modules SUCCESS**;
- **BUILD SUCCESS**;
- total time: **3:27**;
- aggregate Surefire XML: **1807 tests, 0 failures, 0 errors, 17 skipped**;
- 549 Surefire XML reports parsed.

V20 reference was 1802 tests. V21 adds five characterization/projection tests and preserves the existing 17 intentional skips.

## Architecture status

Final `tools/architecture-check.py`:

- UI output-port users: **15/18 baseline**;
- non-value domain-model users: **25/28 baseline**;
- **PASS: architecture baseline is intact**.

The V20 named debt group is closed:

- `DeferredMainFeatureActions` — resolved;
- `ReaderBookMapCoordinator` — resolved;
- `ReaderBookmarkCoordinator` — resolved.

No architecture baseline number was relaxed or edited to obtain the pass.

## Release/downstream gates

On the final V21 source state:

- production profile package — **BUILD SUCCESS**;
- Linux `jpackage` app-image — **PASS**;
- packaged desktop launcher smoke — `MYHOMELIB_RELEASE_SMOKE_OK`, **PASS**;
- extracted portable archive smoke — `MYHOMELIB_RELEASE_SMOKE_OK`, **PASS**;
- portable archive — `myhomelib-8.0.0-linux-x86_64.tar.gz`, **PASS**;
- `SHA256SUMS` — **225 entries**;
- Stage23 with `--require-checksums --require-portable` — **PASS**, 225 checksums verified;
- bundled runtime-module validation — **PASS**;
- static release check — **PASS**;
- supply-chain policy check — **PASS**.

Intermediate Linux packaging intentionally continued without bundled `mobitool`, as allowed by the documented policy.

## Formal libmobi release gate

The strict full-release input remains intentionally fail-closed:

- `MHL_REQUIRE_LIBMOBI=1 ./tools/prepare-runtime-tools.sh` exits **2**;
- verified `tools/converters/libmobi/build-src` is absent;
- therefore a formal full release with bundled libmobi cannot be certified from the supplied inputs.

The strict script was not weakened and no unverified binary/license substitute was introduced.

## Windows acceptance status

This environment is Linux and has no Windows/Wine, real Calibre (`ebook-convert`) or Windows installer runtime. Therefore the following cannot be honestly certified here:

- Windows x64 packaged desktop acceptance;
- real long FB2 → EPUB Calibre conversion responsiveness;
- Windows child-process-tree termination and file-lock release after cancel/timeout/shutdown;
- immediate removal of conversion workspaces on Windows;
- Windows installer/portable acceptance.

V18–V20 deterministic Linux tests model the corresponding lifecycle/cancellation contracts, and V21 does not change those paths, but modeling is not a substitute for platform acceptance.

## Performance/memory status

No performance or memory production change was made in V21. No functional test timing or Maven build timing is presented as A/B performance evidence.

## Source delta from pristine V20

Excluding generated `target/`, `dist/`, local Maven caches and V21 documentation:

- production Java modified: **6 files**;
- production Java added: **1 file** (`ReaderBookmarkItem.java`);
- test Java modified: **2 files**;
- test Java added: **2 files**;
- deleted source/test files: **0**;
- combined Java delta: approximately **+239 / -54 lines**, dominated by characterization/projection tests and boundary mapping.

No application/domain/infrastructure production source was changed.

## Release decision

V21 is the recommended final code-audit source baseline and release-candidate source checkpoint for this environment.

No known code-controlled audit task from the V20 plan remains open. Promotion to a final cross-platform production release requires only external/platform evidence not present in this environment:

1. real Windows x64 + real Calibre acceptance;
2. verified licensed libmobi 0.12 / `mobitool` release input, followed by strict full-release packaging and applicable Windows installer acceptance.

Any code change after V21 should be driven by a concrete failure from one of those acceptance gates or by a new independently reproduced defect, not by further proactive restructuring.
