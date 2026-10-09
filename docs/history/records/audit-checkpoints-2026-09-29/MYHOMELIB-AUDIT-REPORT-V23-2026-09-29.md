# MyHomeLib — Technical Audit Report V23

Date: 2026-09-29  
Baseline: **only** `myhomelib-enterprise-optimized-v22-checkpoint-2026-09-28.zip`.  
Product version: MyHomeLib Enterprise 8.0.0.

## Executive status

V23 is a **release/tooling hardening checkpoint**, not a product-behavior rewrite. The V22 conversion, process-lifecycle and batch-conversion semantics were audited and retained unchanged because no new observable defect was reproduced in those production paths.

The audit found and repaired six concrete release/CI tooling regressions plus one source-tree cleanliness defect. No production Java algorithm, Calibre timeout, process teardown, batch concurrency, migration or performance/memory implementation was changed.

## Baseline and environment

- V22 source checkpoint SHA-256: `b9b7da3d88374ebacebb4b4092c7f3bd0d15800072e8c934a54e4c13e64c27f3`.
- Supplied `tools.zip` SHA-256 verified: `46340ce33280b2768896c2790e4f9a2a8253ab87202eb965c18a37ad7f320cce`.
- Supplied large FB2 SHA-256 verified: `8cbc2ea9d148df24034aea475d394414a026d5e520c6858f839735db07f302b1`.
- JDK 21.
- Apache Maven 3.9.16.
- Offline Maven repository supplied by the user.
- Linux test environment with `LANG=C.UTF-8`, `LC_ALL=C.UTF-8`; JavaFX tests run under Xvfb.

The initial POSIX/ASCII locale reproduced Unicode `InvalidPath` failures in `KnowledgeMarkdownExportServiceTest`; the same test passes under UTF-8. This is an audit/CI environment prerequisite, not a MyHomeLib product defect. JavaFX tests likewise require a display/Xvfb in this Linux environment.

## Defects repaired

### 1. Clean-source documentation drift

**Regression evidence:** clean V22 `tools/build-check-v7.py` rejected 39 historical audit/change/task Markdown files at repository root as unexpected documentation drift.

**Minimum fix:** moved exactly those 39 historical checkpoint documents to:

`docs/history/records/audit-checkpoints-2026-09-27-28/`

The six active root Markdown documents remain at repository root. No Java source was touched.

**Targeted/downstream verification:** `tools/build-check-v7.py` — PASS, including migrations, source invariants and release-tree cleanliness.

### 2. Stale standalone-JDK smoke source closure

**Regression evidence:** `tools/v71-standalone-java-smoke.py` failed `javac` with 52 errors because its isolated source-set no longer included current filesystem/download dependencies and `DownloadPayloadValidator` now reaches the Commons Compress ZIP boundary.

**Minimum fix:**

- added the current JDK-only production dependencies to the real source-set (`SafeFilePublication`, `ArchiveSafetyLimits`, `FileNameSupport`, `NoLinkPathSafety`, `SafeFilePrimitives`, `CredentialTransportPolicy`, `DownloadSpaceSupport`);
- kept real production `DownloadPayloadValidator` and `ConnectionScriptExecutor` under test;
- supplied only the narrow ZIP API adapter required by the smoke in its temporary generated source tree, so the gate remains third-party-classpath-independent;
- corrected the HTTP harness collection to use no reusable Basic Auth username, matching the production `CredentialTransportPolicy`; POST form credentials remain tested via the script `ADD` command.

**Targeted verification:** `tools/v71-standalone-java-smoke.py` — PASS.

### 3. Stage 8+9 gate followed a stale SQL owner

**Regression evidence:** `tools/stage8-9-filter-table-check.py` claimed the fast INPX path did not maintain denormalized `format` because it searched `JdbcBatchWriter` for the old inline `format, author_sort` SQL fragment.

**Root cause:** the canonical UPSERT had already been correctly centralized in `BookUpsertSql`; `JdbcBatchWriter` still computes `BookDenormalizedValues.format(...)` and uses that canonical UPSERT.

**Minimum fix:** the gate now validates the column contract in `BookUpsertSql` and the format derivation in `JdbcBatchWriter`.

**Targeted verification:** Stage 8+9 — PASS, including all 63 SQLite migrations, denormalized indexes/backfill, Lucene/SQL shared filtering and no eager author-table materialization.

### 4. Architecture regression test expected obsolete output text

**Regression evidence:** `architecture-check.py` itself passed, but `architecture-check-regression-test.py` exited non-zero because it searched for an obsolete wording of the independent UI-debt ratchet.

**Minimum fix:** the regression test now requires the current `UI debt ratchet:` marker and `non-value domain-model users` detail.

**Targeted verification:** architecture regression test — PASS.

### 5. GitHub connected-acceptance regression contained an expiring calendar fixture

**Regression evidence:** `github-connected-acceptance-test.py` failed on 2026-09-29 because a fixed `2026-09-05` CodeQL analysis was now older than the production 14-day freshness policy. This test is executed by PR CI, so the repository could fail CI solely because wall-clock time advanced.

**Minimum fix:** only the high-level `codeql_release_gate` fixture now gets a current test timestamp. The lower-level freshness tests still use an explicit fixed `now`, preserving deterministic verification of age, branch and candidate-SHA behavior.

**Targeted verification:** GitHub connected-acceptance regression — PASS.

### 6. External-acceptance readiness contract had stale current-validation tokens

**Regression evidence:** `external-acceptance-readiness-test.py` failed because the structural gate still required obsolete current-validation tokens/counts.

**Minimum fix:** refreshed `docs/release/CURRENT-VALIDATION.md` with exact-current V23 evidence and aligned the structural token contract to `2026-09-29`, `20/20`, `1815`, `17 skipped` plus the explicit open external gate IDs/status.

**Targeted/downstream verification:**

- `external-acceptance-readiness-test.py` — PASS;
- `external-acceptance-readiness.py` — `READY_FOR_LIVE_EVIDENCE`;
- `external-acceptance-readiness.py --run-regressions` — `READY_FOR_LIVE_EVIDENCE`.

### 7. Source packager could re-include generated Python cache after its own checks

**Regression evidence:** final ZIP validation found `tools/__pycache__/*.pyc` in the first archive attempt. The clean staging copy was correct initially, but `offline_checks()` executes Python tools, which regenerate `__pycache__`; `create_zip()` then walked the staging tree without applying `is_excluded()` a second time.

**Minimum fix:** `tools/package-v71-source.py:create_zip()` now applies the same canonical exclusion predicate at the ZIP boundary. This also protects against `target/`, cache and other generated directories appearing between staging and archive creation.

**Targeted verification:** a temporary source fixture containing post-check `tools/__pycache__/x.pyc` and `module/target/x.class` is archived; only the normal source file is present — PASS. Final V23 ZIP is independently scanned for unsafe paths and forbidden generated directories.

## Real supplied FB2 acceptance

The existing opt-in `RealFb2ReaderProbeTest` was run against all three supplied FB2 ZIP archives:

1. `Демон. Пожиратель душ #01-18`: 17,736,920 FB2 bytes; 7,756,433 text characters; 49,010 paragraphs; 17 chapters; 483 TOC nodes; 4 resources; ~1.22 s parse time.
2. `Возвращение Великого Я... 1-15`: 14,618,515 bytes; 6,657,913 text characters; 54,551 paragraphs; 16 chapters; 316 TOC nodes; 41 resources; ~0.69 s.
3. `Хранитель Древа Мира 1-8`: 8,181,973 bytes; 3,470,361 text characters; 28,682 paragraphs; 9 chapters; 172 TOC nodes; 38 resources; ~0.47 s.

All text lengths, paragraph counts, chapter offsets and TOC offsets passed validation.

These timings are observational probe results, **not** a performance A/B claim and not a portable SLA.

## Full verification after changes

Final Maven 3.9.16 `clean verify`, with UTF-8 locale and Xvfb:

- **1815 tests**;
- **0 failures**;
- **0 errors**;
- **17 skipped**;
- **20/20 reactor modules SUCCESS**;
- `BUILD SUCCESS`.

Additional gates:

- clean-source `build-check-v7.py`: PASS;
- static release check: PASS;
- architecture check: PASS;
- catalog lifecycle regression: PASS;
- standalone-JDK smoke: PASS;
- Stage 8+9: PASS;
- Stage 24: PASS;
- Stage 25C: PASS;
- supply-chain policy: PASS;
- workflow YAML parse: PASS;
- root/release shell syntax: PASS;
- external acceptance regression bundle: PASS / `READY_FOR_LIVE_EVIDENCE`.

## Production audit decisions

The following V22 production behavior was reviewed and deliberately **not** changed:

- Calibre default/effective legacy timeout policy (1800 s; exact legacy 300 migration; explicit custom values bounded 10…3600 s);
- 10 s external-process termination grace;
- checkbox selection with current-row fallback;
- batch conversion common-format intersection;
- maximum two in-flight conversions and queued-work cancellation;
- conversion coexistence with reader/download operation types.

Existing tests cover these contracts and no new incorrect observable result was reproduced.

A raw duplicate scan found textual duplication candidates, but no refactor was made without a defect, responsibility-boundary need or measured benefit. This avoids converting superficial similarity into unnecessary abstraction.

## Performance / memory

No production performance or memory optimization was made. No same-environment A/B result justified one, so V23 makes **no performance claim** beyond the unchanged V22 behavior and the reader corpus correctness probe above.

## Limitations

The supplied Calibre `ebook-convert.exe` and libmobi `mobitool.exe` are Windows x64 binaries. This Linux environment cannot complete the mandatory Windows P0 acceptance: real Calibre version execution, >5 minute conversion, cancellation/process-tree teardown, file-handle release, application-close teardown, and real batch converter process concurrency.

Therefore V23 is locally green but **not a substitute for the Windows acceptance required by the V22 handoff**.
