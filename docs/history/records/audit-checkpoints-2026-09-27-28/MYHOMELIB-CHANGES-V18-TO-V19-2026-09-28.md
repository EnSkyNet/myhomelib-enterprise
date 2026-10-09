# MyHomeLib — Changes V18 → V19

Date: 2026-09-28  
Baseline: `myhomelib-enterprise-optimized-v18-checkpoint-2026-09-28.zip`.

## Build reproducibility

Modified root `pom.xml` to pin the two Maven lifecycle plugins proven to vary under exact Maven 3.9.16:

- `maven-resources-plugin` → 3.3.1;
- `maven-jar-plugin` → 3.3.0.

These are the versions already used/available in the V18 verified toolchain. No unrelated lifecycle plugin was pinned.

Regression sequence:

1. unchanged V18 + Maven 3.9.16 failed resolving implicit `maven-resources-plugin:3.4.0`;
2. after characterizing that boundary, the next implicit mismatch was `maven-jar-plugin:3.5.0`;
3. after the two explicit pins, the exact 3.9.16 full reactor is green.

## Maintenance-tool correctness

Modified `tools/dependency-upgrade-check.py`.

Before V19 it crashed because it referenced removed `myhomelib-bootstrap/.../LibraryHealthIndicator.java`.

The stale class-specific check was replaced with a Java-source scan that retains the intended guard against legacy Boot 3 `org.springframework.boot.actuate.health` imports without requiring a no-longer-existing health indicator class.

## Release policy documentation

Updated `MYHOMELIB-RELEASE.md` to make existing release behavior explicit:

- `SHA256SUMS` is mandatory for publishable releases and Stage23 must be strict there;
- non-strict Stage23 remains valid for intermediate/raw app-images;
- formal full desktop releases require the verified bundled libmobi 0.12 / `mobitool` payload plus license;
- intermediate app-images may rely on configured/system `mobitool`.

## Verification

Exact Maven 3.9.16, ASCII checkout:

- `./mvnw -v`: Maven 3.9.16;
- full `clean verify`: **1793 tests, 0 failures, 0 errors, 17 skipped; 20/20 modules SUCCESS**;
- production package: PASS;
- Linux jpackage app-image: PASS;
- packaged `--release-smoke`: PASS;
- Stage23: PASS;
- static release: PASS;
- supply-chain policy: PASS.

Exact Maven 3.9.16, non-ASCII checkout:

- full `clean verify`: **1793 tests, 0 failures, 0 errors, 17 skipped; 20/20 modules SUCCESS**;
- production package: PASS;
- Linux jpackage app-image: PASS;
- packaged `--release-smoke`: PASS;
- Stage23: PASS.

Targeted tooling:

- dependency-upgrade static check: PASS;
- strict checksum generation: 221 entries;
- Stage23 `--require-checksums`: PASS;
- strict libmobi probe: expected fail-closed because verified bundled source/runtime is absent.

## Source delta

Modified existing files: 3.  
Added V19 documentation files: 3.  
Production Java changes: 0.  
Test Java changes: 0.  
Deleted source/test files: 0.
