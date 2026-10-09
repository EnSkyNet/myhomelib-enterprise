# MyHomeLib — Technical Audit Report V19

Date: 2026-09-28  
Baseline: `myhomelib-enterprise-optimized-v18-checkpoint-2026-09-28.zip`  
Baseline SHA-256: `6324e53bfdc36dda4a01a0d4f6d2ba475a69a8d4585f61735cf261a53aacd21a`.

## Executive status

V19 closes the Linux-side **exact Maven 3.9.16 acceptance** that remained open after V18 and keeps V18 as the sole source baseline for this iteration.

Two concrete defects were found and handled defect-first:

1. the build relied on Maven-provided implicit lifecycle versions for `maven-resources-plugin` and `maven-jar-plugin`; Maven 3.9.16 therefore selected versions different from the V18/3.9.6 build and the supplied populated offline repository could not reproduce the build;
2. `tools/dependency-upgrade-check.py` was no longer executable because it referenced a removed `LibraryHealthIndicator.java` path.

No production Java, resource, FXML, database migration or test Java file was changed. No performance/memory optimization was attempted.

## Exact Maven 3.9.16 input

Supplied archive:

- `apache-maven-3.9.16.zip`;
- SHA-256: `81bce692f3ffcab79dbebf857fddf6110a6a3575ed929e087471000d57f7b131`.

The archive was staged only into the Maven Wrapper local distribution cache; the project wrapper configuration was not rewritten.

`./mvnw -v` reports:

- Apache Maven **3.9.16** (`2bdd9fddda4b155ebf8000e807eb73fd829a51d5`);
- Java 21.0.11;
- UTF-8 platform encoding.

## Defect 1 — implicit Maven lifecycle plugin versions

### Regression evidence on unchanged V18

With the exact Maven 3.9.16 wrapper distribution available, unchanged V18 failed before tests:

1. first failure: Maven 3.9.16 selected `maven-resources-plugin:3.4.0`, which was not present in the supplied populated offline repository;
2. after characterizing that dependency only, the next unchanged lifecycle dependency selected by Maven 3.9.16 was `maven-jar-plugin:3.5.0`, also absent from the V18 offline repository.

This demonstrated a reproducibility defect rather than an application-code regression: the project pinned compiler and Surefire versions but still allowed Maven itself to change resources/JAR lifecycle semantics by Maven version.

### Minimal fix

Root `pom.xml` now pins only the two proven implicit lifecycle plugins:

- `maven-resources-plugin` = **3.3.1**;
- `maven-jar-plugin` = **3.3.0**.

Both are the versions already used/available in the V18 verified toolchain/repository. No unrelated lifecycle plugin was pinned merely for symmetry; `maven-clean-plugin:3.2.0` already resolved successfully under 3.9.16, while compiler and Surefire were already explicitly managed.

### ASCII checkout full gate

Checkout path: `/mnt/data/mhl-v19-work/myhomelib-enterprise-optimized-v18-checkpoint-2026-09-28`.

Environment:

- Maven 3.9.16 through `./mvnw`;
- Java 21.0.11;
- `LANG=C.UTF-8`, `LC_ALL=C.UTF-8`;
- UTF-8 JVM file encoding;
- Xvfb for JavaFX-dependent tests;
- populated supplied offline Maven repository.

Full `clean verify` result:

- **20/20 reactor modules SUCCESS**;
- **BUILD SUCCESS**;
- total time: **3:02**;
- aggregate Surefire XML: **1793 tests, 0 failures, 0 errors, 17 skipped**;
- 545 Surefire XML reports parsed;
- application: 464 tests, 1 skipped;
- infrastructure: 637 tests, 9 skipped;
- reader: 137 tests, 1 skipped;
- UI: 228 tests, 0 skipped;
- bootstrap: 52 tests, 2 skipped;
- E2E: 15 tests, all PASS;
- benchmark: 3 tests, all intentionally skipped.

This exactly matches the V18 functional reference count.

Release/downstream checks from the same ASCII source state:

- production profile package: **BUILD SUCCESS**;
- Linux `jpackage` app-image: **PASS**;
- packaged launcher smoke: `MYHOMELIB_RELEASE_SMOKE_OK` — **PASS**;
- Stage23 app-image validation: **PASS**;
- static release check: **PASS**;
- supply-chain policy check: **PASS**.

## Non-ASCII checkout full gate

Checkout path: `/mnt/data/перевірка-mhl-v19/МояБібліотека`.

`./mvnw -v` again reports Maven **3.9.16** and UTF-8.

Full `clean verify`:

- **20/20 reactor modules SUCCESS**;
- **BUILD SUCCESS**;
- total time: **3:07**;
- aggregate Surefire XML: **1793 tests, 0 failures, 0 errors, 17 skipped**;
- 545 Surefire XML reports parsed.

From the same non-ASCII checkout:

- production profile package: **BUILD SUCCESS**;
- Linux `jpackage` app-image: **PASS**;
- packaged launcher smoke: `MYHOMELIB_RELEASE_SMOKE_OK` — **PASS**;
- Stage23 app-image validation: **PASS**.

No checkout-path, native launcher, Java/JNU encoding or jpackage publication regression was observed in this Linux acceptance.

## Defect 2 — stale dependency-upgrade static checker

### Regression evidence

Running `python3 tools/dependency-upgrade-check.py` on V18 failed immediately with `FileNotFoundError` for:

`myhomelib-bootstrap/src/main/java/com/myhomelibcorp/monitoring/LibraryHealthIndicator.java`

The class is not present anywhere in the current codebase; library-health behavior now lives in the application layer. The checker was therefore stale rather than pointing to a simple moved file.

### Minimal fix

The obsolete single-file health check was replaced by a repository Java-source scan preserving its still-valid intent: reject any remaining Boot 3 `org.springframework.boot.actuate.health` import.

The checker no longer invents or requires a replacement `HealthIndicator` class.

### Targeted/downstream verification

- `tools/dependency-upgrade-check.py`: **PASS**;
- `tools/static_release_check.py`: **PASS**;
- `tools/supply-chain-policy-check.py`: **PASS**.

The Maven reactor was not rerun solely for this Python-tool change because it does not affect Maven inputs, Java sources/resources or runtime packaging; the exact Maven full reactor had already passed on the final `pom.xml` state.

## Packaging decisions closed in V19

### SHA256SUMS

Decision: **mandatory for publishable release artifacts**, not for an intermediate/raw app-image.

The existing release scripts/CI already generate `SHA256SUMS` and invoke Stage23 with `--require-checksums`. V19 documents this explicitly. Intermediate app-image validation may still use non-strict Stage23, where missing checksums remain a warning by design.

Additional strict check on the V19 app-image:

- `checksums.sh dist`: generated **221** checksum entries;
- Stage23 with `--require-checksums`: **PASS**, 221 files verified.

### libmobi / mobitool

Decision: the **formal full desktop release remains fail-closed and requires the verified bundled libmobi 0.12 / `mobitool` payload plus its license**. Developer/intermediate app-images may omit bundled libmobi and use a compatible configured/system `mobitool` at runtime.

Current baseline still does not contain `tools/converters/libmobi/build-src`, so the strict probe correctly fails with exit code 2 and reports that full release packaging requires the missing verified bundle.

This is now an explicit release-input blocker, not an ambiguous warning or a reason to silently weaken the full-release contract.

## Architecture status

Standalone `tools/architecture-check.py` remains intentionally non-zero with exactly the existing known debt group:

- `DeferredMainFeatureActions`;
- `ReaderBookMapCoordinator`;
- `ReaderBookmarkCoordinator`.

Ratchets are unchanged:

- UI output-port users: 15/18 baseline;
- non-value domain-model users: 28/28 baseline.

The Maven architecture-test module remains green: 14/14.

No architecture refactor was attempted because V19 had concrete build/tooling defects and real Windows P0 acceptance is still unavailable in this Linux environment.

## Performance/memory status

No performance or memory source change was made. No benchmark result is used as performance evidence and no A/B performance claim is made.

## Source delta

Compared with pristine V18, excluding generated `target/`, `dist/` and local Maven cache trees:

Modified existing files:

1. `pom.xml` — explicit resources/JAR lifecycle plugin pins;
2. `tools/dependency-upgrade-check.py` — remove stale single-file health-indicator dependency while preserving the legacy-import guard;
3. `MYHOMELIB-RELEASE.md` — explicit checksum/libmobi release policy.

Added V19 documentation:

- `MYHOMELIB-AUDIT-REPORT-V19-2026-09-28.md`;
- `MYHOMELIB-CHANGES-V18-TO-V19-2026-09-28.md`;
- `MYHOMELIB-NEXT-TASKS-V19-2026-09-28.md`.

Production Java changes: **0**.  
Test Java changes: **0**.  
FXML/resource/migration changes: **0**.

## Release decision for this checkpoint

V19 is the recommended clean source baseline for the next audit session.

The exact Maven **3.9.16 Linux acceptance is CLOSED**, including full reactor, production package, jpackage, packaged smoke, Stage23, ASCII checkout and non-ASCII checkout.

V19 is still not a final cross-platform production release candidate until:

1. real Windows x64 packaged/Calibre cancellation acceptance is completed;
2. the verified bundled libmobi 0.12 release input is restored for the formal full-release path;
3. platform release CI/installer acceptance succeeds where applicable;
4. the existing architecture debt group is triaged after the P0/P1 correctness gates, without ratchet gaming.
