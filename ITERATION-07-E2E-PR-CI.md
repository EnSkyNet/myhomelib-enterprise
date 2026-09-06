# Iteration 07 — E2E regression journeys and fast PR CI

Date: 2026-09-06
Baseline: `myhomelib-enterprise-7.1.0-rc3-iter06-tls-certificate-encryption-envelope.zip`
Backlog scope: **MHL-009**, **MHL-010**
Additional regression fixed during gate hardening: `CollectionLifecycleService` INDEX/SWITCH completion race.

## 1. Scope and rationale

MHL-009 and MHL-010 were implemented together because the PR workflow needs stable, bounded end-to-end journeys to provide meaningful release-blocking feedback. The new E2E module deliberately uses real production components while avoiding external network access and JavaFX UI dependence, so it remains deterministic enough for a fast PR gate.

## 2. MHL-009 — real E2E coverage

`myhomelib-e2e-tests` now contains four suites and exactly ten `@Test` scenarios:

### Import / search / Reader — 4 scenarios

`ImportSearchReaderJourneyE2ETest`

1. FB2 file → production importer → Lucene → Reader.
2. Imported metadata remains searchable by author and language aliases.
3. Reader position persists across close/reopen.
4. Reader page navigation moves forward/backward on imported FB2.

### Backup / restore — 2 scenarios

`BackupRestoreJourneyE2ETest`

5. Portable user-data backup/restore round-trip restores state by stable `libId` even when the target collection has a different internal database id.
6. Restore compensates database/settings/Reader state when settings persistence fails.

### Collection rollback / metadata — 2 scenarios

`CollectionRollbackJourneyE2ETest`

7. Collection metadata round-trip uses a real SQLite database and keeps credentials encrypted.
8. Failed import during collection creation rolls back metadata and physical database state.

### OPDS — 2 scenarios

`OpdsJourneyE2ETest`

9. Loopback OPDS enforces Basic Auth and serves a real Atom feed.
10. Managed certificate + encrypted settings survive reload and start real HTTPS without a manually supplied keystore secret.

### E2E stability choices

- no external HTTP dependency;
- no Docker/Testcontainers dependency;
- no JavaFX UI dependency;
- deterministic test encryption key is injected only through Surefire test properties;
- test stdout/stderr is redirected to Surefire files for CI diagnostics;
- one reusable fork is used to reduce startup overhead.

Result: **10/10 PASS, 0 failures, 0 errors, 0 skipped**.

## 3. MHL-010 — fast pull-request CI

Added `.github/workflows/ci-pr.yml`.

The `Fast gate` job contains:

- checkout;
- JDK 21 setup with Maven cache;
- Python 3.12 setup;
- explicit `chmod +x mvnw` to avoid executable-bit issues after ZIP/Windows handling;
- production + test-source compilation;
- bounded core unit tests;
- migration/security regression tests;
- ArchUnit boundaries;
- all ten E2E journeys;
- XML/archive security static gate;
- language catalogue consistency gate;
- Surefire/Failsafe/log artifact upload on failure.

Workflow controls:

- `pull_request` trigger;
- push validation for `main`/`master`;
- `cancel-in-progress: true` per PR/ref;
- Maven dependency cache;
- hard `timeout-minutes: 10`;
- read-only repository content permission.

### Maven launcher repair

The repository referenced `mvnw` / `mvnw.cmd` from CI and packaging scripts, but those launchers were absent in the iter06 source tree. Iteration 07 restores both launchers. They prefer the supplied embedded Maven/wrapper when present and fall back to a system Maven installation.

The code-only iteration archive intentionally does not embed the local `.mvn/maven` tool distribution; the separately supplied `.mvn` bundle may still be used for fully offline builds.

## 4. Concurrency regression found by the new gate

The fast-core gate exposed a race in `CollectionLifecycleService` / `SwitchCollectionRebuildConcurrencyTest`:

- an INDEX detached lease could become visible to `LibraryOperationCoordinator` before `activeRebuild` published its cancellation handle;
- a SWITCH could therefore observe INDEX as active but fail to cancel/await the corresponding rebuild;
- additionally, the rebuild `CompletableFuture` could become terminal before the detached INDEX lease had actually been released.

Fix:

1. publish `RebuildTask` before acquiring the immediate detached INDEX lease;
2. clear/complete it exceptionally if lease acquisition fails;
3. release the detached lease before completing/cancelling the public future;
4. make the future the true completion barrier for lifecycle callers.

The previously unstable regression test is green after the change.

## 5. Acceptance mapping

### MHL-009

- **Minimum 10 stable E2E scenarios:** satisfied — exactly 10 scenarios.
- **Run from CI:** satisfied — all four E2E suites are included in `ci-pr.yml`.
- **Failure artifacts/logs:** satisfied — Surefire/Failsafe reports and logs are uploaded when the job fails.

### MHL-010

- **Every PR runs CI:** satisfied by the `pull_request` trigger.
- **Merge blocked on failure:** workflow side is ready, but this is a GitHub repository setting. Protected branches must require the **`Fast gate`** status check in Branch protection / Rulesets. This cannot be truthfully verified or enforced from a source archive alone.
- **Median runtime target ≤10 min:** workflow has a hard 10-minute timeout. Local warmed-cache evidence is roughly 35–40 seconds for the sequential Maven/static gate set, but this is not a substitute for median GitHub-hosted PR-run history. The median criterion remains to be confirmed from real CI runs.
- **Release workflow avoids duplicated PR jobs:** the new workflow is PR-focused; `ci-release.yml` remains the cross-platform production verification/package matrix rather than duplicating the fast PR job graph.

## 6. Verification evidence

### Compile gate

`./mvnw -o -Dmaven.repo.local=/mnt/data/maven-offline-repo -DskipTests test-compile`

- all **13 Maven modules: BUILD SUCCESS**.

### Fast core

Modules: shared, domain, application, OPDS.

- **148 tests total**;
- **147 PASS**;
- **1 SKIP**;
- **0 failures / 0 errors**;
- reactor time: **12.906 s** in the local warmed-cache environment.

### Migration/security

- **18/18 PASS**;
- **0 failures / 0 errors**;
- reactor time: **7.076 s**.

### Architecture

- **12/12 PASS**;
- **0 failures / 0 errors**;
- reactor time: **7.409 s**.

### E2E

- **10/10 PASS**;
- **0 failures / 0 errors**;
- reactor time: **6.234 s**.

### Static gates

- XML/archive security: **PASS**;
- language catalogue validation: **PASS** for `bg`, `en`, `uk` (schema 3; 203 UI keys and 335 genre keys each).

### Final package gate

`./mvnw -o -Dmaven.repo.local=/mnt/data/maven-offline-repo -DskipTests package`

- **all 13 modules: BUILD SUCCESS**;
- Maven Shade for `myhomelib-mcp`: completed successfully;
- latest successful reactor time: **5.935 s**.

The first package attempt was interrupted externally while Maven Shade was running. The successful retry proves the Shade step and full reactor package complete normally.

## 7. Files added/changed versus iter06

Added:

- `.github/workflows/ci-pr.yml`;
- `mvnw`;
- `mvnw.cmd`;
- four E2E test suites under `myhomelib-e2e-tests/src/test/java/com/myhomelibcorp/e2e/`;
- this iteration report.

Changed:

- `myhomelib-e2e-tests/pom.xml`;
- `myhomelib-application/.../CollectionLifecycleService.java`;
- `MYHOMELIB-DEVELOPMENT.md`;
- `MYHOMELIB-OPERATIONS.md`.

## 8. Remaining external validation

Two MHL-010 acceptance facts require the actual GitHub repository and cannot be established from the local source tree:

1. protected branches must require the `Fast gate` status check;
2. median PR runtime ≤10 minutes must be confirmed from real GitHub Actions run history.

No local source-code blocker remains for Iteration 07.
