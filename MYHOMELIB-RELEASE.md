# MYHOMELIB — Release and Upgrade

**Source version:** 7.1.0  
**Documentation snapshot:** 31 August 2026

## Release focus

v7.1 concentrates on online-library compatibility, safe/atomic book downloads, large-catalog stability, Reader correctness, user-data safety and truthful release validation.

Key outcomes include:

- MyHomeLib-compatible declarative `ConnectionScript` with deterministic macros and no dynamic code execution;
- `collection.info` compatibility while preserving local secrets/settings during normal updates;
- validated atomic online download, durable credential-free queue and validator-bound resume;
- safe support for server-renamed FB2 entries inside downloaded ZIPs, with the actual resolved member persisted;
- centralized HTTP proxy/TLS policy with encrypted secrets and no trust-all mode;
- bounded import/catalogue update paths and large-catalog search/navigation hardening;
- stable remote-source/book revision state and downloaded baselines;
- Lucene fingerprinting/selective update plus rollback-safe rebuild behavior;
- real statistics/error state and explicit cache invalidation;
- archive integrity checks and safer resource resolution based on physical availability;
- Reader Canvas/ZIP/layout/settings/persistence fixes;
- versioned user-data backup/restore;
- OPDS and cross-platform release tooling;
- JDK 21 CI matrix and performance workflow.

## Upgrade compatibility

### From v6

Back up the data directory before upgrade. Existing collections follow the normal Flyway chain; stable book IDs and user data are intended to survive. Do not edit older migrations manually.

### From v7

v7.1 is an additive forward migration. V1–V36 are historical baseline and must remain immutable. Later migrations extend statistics, search/manifest compatibility, metabib/online state and subsequent schema corrections present in the repository. The metadata database has an independent migration chain for collection/download state.

Before upgrade, keep a restorable v7 backup. After first v7.1 start, verify catalogue/user data, local downloads, search health and online collection settings.

Rollback is backup-based: restore the pre-upgrade database/application state rather than deleting Flyway rows or columns manually.

## Search/cache behavior after upgrade

Older manifest/search compatibility values may trigger a one-time revalidation or index rebuild. A failed rebuild must leave the old committed Lucene index available. Lucene can be rebuilt; user data and local book files cannot, so retain the catalogue backup until acceptance is complete.

## Online compatibility note

Historical MyHomeLib/Flibusta servers may return a ZIP whose internal FB2 filename differs from the catalogue `archiveEntry`. Current v7.1 resolves this safely and persists the actual member. Example:

```text
catalogue: 586491.fb2
server ZIP: Romanovich_Zemli-chudovishch_1_Zemli-chudovishch.586491.fb2
```

This is accepted when the match is unambiguous. Multi-FB2 ambiguity remains a validation error.

## Release pipeline

Required CI:

```text
JDK 21
Maven 3.9.6+
Ubuntu + Windows + macOS
mvn clean verify -Pproduction
```

After verification, platform packaging creates `jpackage --type app-image` artifacts. A headless `--release-smoke` runs against the packaged launcher. Tagged releases publish platform archives and SHA-256 checksums in `SHA256SUMS` only after verification jobs succeed.

A normal packaged application does not download Maven dependencies at runtime.

### Release supply-chain artifacts

Before the cross-platform package matrix starts, the release workflow runs a dedicated supply-chain gate:

1. the exact release-candidate commit must already have a successful CodeQL analysis on the default branch, and open High/Critical code-scanning alerts block the release;
2. OWASP Dependency-Check blocks dependencies at CVSS 7.0 or higher unless a narrow, justified and unexpired suppression exists;
3. CycloneDX aggregate SBOM generation must succeed;
4. both `bom.json` and `bom.xml`, Dependency-Check HTML/JSON/SARIF reports, and the candidate-bound CodeQL gate JSON/Markdown are retained as release artifacts.

The formal source-release archive contains **no Maven runtime or Maven Wrapper payload**. `mvnw`, `mvnw.cmd` and the complete `.mvn/` directory are excluded; `pom.xml` remains included. Build the archive with a separately installed Maven 3.9.6+ and dependency access, or with Maven plus the prepared offline dependency repository. A repository/CI checkout may carry a wrapper, but that convenience launcher is not part of the formal source artifact.

Versioned **portable** archives include an empty `myhomelib2.ini` beside the native launcher.
Therefore an extracted portable archive uses its local `data/` directory immediately, even when
launched from another working directory. Native installers do not include this marker and keep
normal user data outside the installation directory, so uninstalling the application package does
not target the catalogue/database stored in the user profile.

Release/CI validation also extracts the versioned portable ZIP/TAR into a clean temporary directory
and starts that extracted launcher from an unrelated working directory. The smoke fails unless the
local `data/` directory is selected and the synthetic user profile remains untouched.

## Validation boundary

Offline/static checks are valuable for architecture, source contracts, SQLite/Flyway, FXML/XML, Reader/OPDS standalone smokes, download behavior and packaging integrity. They do **not** replace a connected compiled Maven reactor and real GitHub Actions run.

Therefore the formal release acceptance rule is:

1. `mvn clean verify -Pproduction` succeeds with Maven 3.9.6+ and dependency access;
2. GitHub Actions passes on Ubuntu/Windows/macOS;
3. final archive is extracted and revalidated from the extracted tree;
4. checksums and Unix executable permissions are verified;
5. a real desktop smoke covers collection open, online book download, Reader and backup/restore.

## Stage 05 Windows installer lifecycle gate

The release CI now runs `tools/windows-installer-acceptance.ps1` on the disposable Windows runner after WiX is available. The gate uses MSI because Windows Installer exposes deterministic silent install/uninstall through `msiexec`, while the EXE installer remains the published interactive Windows installer artifact.

The automated lifecycle checks:

- package a synthetic previous version (`7.0.99`) and the current package from the same already-verified application JAR, both with the same stable `--win-upgrade-uuid`;
- install the previous MSI per-user and verify exactly one MyHomeLib uninstall registration;
- verify the native launcher under `%LOCALAPPDATA%\MyHomeLib`, the Desktop shortcut and the Start Menu shortcut;
- run the installed launcher with `--release-smoke`;
- create deterministic sentinels under `%USERPROFILE%\.myhomelibcorp` and `libraries/`;
- upgrade to the current MSI and prove that no side-by-side product registration is left;
- install the current MSI again to exercise repair/idempotent installation;
- uninstall and prove that application registration/launcher/shortcuts are removed but the profile database/library sentinels remain byte-identical.

The script refuses to run when MyHomeLib is already installed or `%USERPROFILE%\.myhomelibcorp` already exists. It is intentionally a disposable-runner/VM acceptance tool and must not be used against a user's normal profile. MSI logs are retained under `target/windows-installer-acceptance/` and uploaded by CI.

The synthetic previous package proves installer identity, upgrade, shortcut, repair and uninstall semantics. It does **not** replace upgrade testing from a real previous MyHomeLib build because both synthetic MSI packages contain the current application JAR. For a real previous-release acceptance on a clean Windows VM, run:

```powershell
.\tools\windows-installer-acceptance.ps1 `
  -PreviousMsi C:\path\to\MyHomeLib-<previous>.msi `
  -PreviousVersion <previous-version>
```

The final candidate is now consumed from the digest-verified GitHub connected-acceptance artifact. Real application-data migration, interactive EXE installer UI, collection/online-download/Reader/backup-restore smoke and DPI 100/125/150/200% remain interactive Windows release gates, but they are no longer undocumented manual checks: `tools/windows-release-desktop-acceptance.ps1` and `tools/windows-ui-acceptance.ps1` capture screenshot-backed JSON/Markdown evidence and bind it to the exact GitHub candidate EXE/MSI/portable hashes.

### Manual Windows UI/DPI acceptance runner

`tools/windows-ui-acceptance.ps1` records the mandatory P4 runtime checks without changing Windows display settings itself. Change the tested monitor scaling in Windows Settings, then run the script separately at each required scale:

```powershell
.\tools\windows-ui-acceptance.ps1 -Scale 100
.\tools\windows-ui-acceptance.ps1 -Scale 125
.\tools\windows-ui-acceptance.ps1 -Scale 150
.\tools\windows-ui-acceptance.ps1 -Scale 200
```

Each run first verifies the installed packaged launcher with `--release-smoke`, records the system-DPI observation for diagnostics, then requires explicit PASS/FAIL/BLOCKED results for Main Window geometry, repeated left/right sidebar cycles, the four required author-search forms plus case/space/Cyrillic variants, Search column/clear/Select All behavior, Book Details, Reader toolbar/sidebar geometry, Collection Wizard, Backup/Restore, Back/Forward and Followed Authors. The final geometry criterion is explicit: no sidebar, toolbar or content pane may extend the layout beyond the client area. Reports are written to `target/windows-ui-acceptance-<scale>.md`.

Use `-ChecklistOnly` only to generate a blank protocol. A release acceptance requires four interactive reports with `Overall: PASS`; checklist generation is not a runtime PASS.

## Historical documentation

Development-stage changelogs, runtime fixes, parity/audit documents and older release notes are no longer active specifications. Their consolidated summaries are in:

- `docs/history/MYHOMELIB-HISTORY-STAGES.md`;
- `docs/history/MYHOMELIB-HISTORY-FIXES.md`;
- `docs/history/MYHOMELIB-HISTORY-AUDITS.md`.

Original Markdown source notes are preserved under `docs/history/source-notes/`.

## 2026-09-02 refactoring completion note

The source tree has passed the repository's offline architecture, lifecycle, functional, Reader, localization, performance-baseline and static release checks after the stabilization pass. See `docs/history/records/REFACTORING_COMPLETION.md` for the exact source-level baseline. This does not waive the formal release boundary above: compiled Maven verification, platform packaging and real desktop smoke testing are still mandatory before publishing a binary release.

## 2026-09-05 Stage 05 portable-launcher hardening

A real `jpackage` app-image probe found that portable mode could miss `myhomelib2.ini` when the native launcher was started from a working directory different from the launcher directory. `AppPaths.launchDir()` now keeps an explicit `-Dmyhomelib.launchDir` as the highest-priority override, derives the directory of the native process when the `jpackage.app-version` runtime marker is present, and retains `user.dir` as the fallback for ordinary JVM/IDE launches.

The Linux JDK 21 `jpackage` acceptance probe places `myhomelib2.ini` beside `dist/MyHomeLib/bin/MyHomeLib`, starts that launcher from an unrelated working directory, and confirms that runtime directories are created under `dist/MyHomeLib/bin/data` while the normal profile data directory is not created. Windows portable/installer execution, DPI, upgrade and uninstall acceptance remain mandatory before final release.

## Stage 05 P4 Windows DPI acceptance hardening — 2026-09-05

- `tools/windows-ui-acceptance.ps1` now cross-checks the requested 100/125/150/200% run against `GetDpiForSystem()` (96/120/144/192 DPI).
- On a single-monitor acceptance machine, a known system-DPI mismatch is an automatic `AUTO-0 = FAIL`; an unavailable API observation is `BLOCKED`, so the report cannot silently claim PASS.
- On multi-monitor Windows, a system-DPI mismatch is `BLOCKED` rather than a false FAIL because the monitor hosting MyHomeLib can use different per-monitor scaling; P4-01 must confirm that monitor explicitly.
- This is acceptance-tooling hardening only; production Java code is unchanged.

## 2026-09-06 connected GitHub acceptance

The canonical operator sequence for the six remaining external gates is `docs/release/EXTERNAL-ACCEPTANCE-RUNBOOK.md`. The offline `tools/external-acceptance-readiness.py` gate is fail-closed on missing harness/workflow contracts but intentionally reports the six gates as OPEN until live evidence exists.

The remaining repository-side 7.1 Final evidence for PR enforcement/performance and supply-chain security is collected by `.github/workflows/github-acceptance.yml` using `tools/github-connected-acceptance.py`.

The workflow fails closed unless the default branch actively requires the `Fast gate` status check, at least five successful hosted PR samples have a `Fast gate` median no greater than 600 seconds, and the selected successful `ci-release.yml` run is the exact candidate commit. Its non-expired `myhomelib-supply-chain` and `myhomelib-windows` artifact ZIPs must match the SHA-256 digests declared by the GitHub Actions API. The supply-chain artifact must contain CycloneDX 1.6 JSON/XML, Dependency-Check JSON/SARIF/HTML, and a PASS CodeQL release-gate record for that exact candidate. The same candidate must also have a recent successful CodeQL analysis on the default branch with no open High/Critical code-scanning alerts.

Release CI invokes the same tested CodeQL implementation with `--codeql-release-gate-only --expected-sha "$GITHUB_SHA"`; it fails closed when the exact release candidate has no successful CodeQL analysis yet. Its JSON/Markdown evidence is retained inside the release supply-chain artifact. An offline source archive still cannot claim this connected PASS: the authoritative evidence is produced by real GitHub workflow/API state.

### Final 7.1 external evidence decision

For the final Windows handoff, prefer `tools/v71-windows-acceptance-start.ps1`. Given the repository name, the exact successful **GitHub connected acceptance** run id, a real previous-release MSI and its version, it downloads the acceptance artifact through the GitHub Actions API, verifies the API-declared SHA-256 digest, safely stages the exact MSI/EXE/portable candidate set, runs the real-previous MSI + portable lifecycle, and launches the interactive real-desktop acceptance. A merely copied local ZIP is insufficient for final PASS because the final gate requires `github-connected-acceptance-ingest.json` with `remoteDigestVerified=true`.

After the four 100/125/150/200% DPI passes exist, run `tools/v71-finalize-external-acceptance.ps1`. The flow revalidates all four GitHub connected checks, the digest-verified GitHub artifact ingest, the strict standard-user/real-previous-MSI/portable lifecycle, the exact candidate EXE desktop smoke, all four DPI reports and the nested Windows evidence ZIP. `tools/v71-final-external-acceptance-check.py` reruns the strict Windows validator against the ZIP payload itself, so a detached or altered reviewer archive cannot pass merely because the live `target` tree passed.

The finalizer then creates `myhomelib-7.1-final-external-evidence.zip` and immediately runs `tools/v71-final-evidence-bundle-check.py`. That last gate verifies the outer sidecar, exact manifest/member set, connected GitHub JSON, GitHub ingest record, three-entry bound candidate manifest (MSI/EXE/portable), nested Windows ZIP + sidecar, desktop/DPI evidence and the consolidated decision record. Only a finalizer run ending in `MyHomeLib 7.1 final external evidence: PASS` is sufficient to reconcile the six externally evidenced 7.1 Final backlog items as complete.

## 7.1 final acceptance harness binding

The final Windows acceptance harness is itself candidate-bound. `GitHub connected acceptance` writes `acceptance-harness.sha256` from the exact dispatched candidate checkout. The manifest covers every script that can influence the Windows MHL-011/MHL-012 decision, including ingest, installer/portable, desktop/DPI, evidence validators and final reviewer-bundle checks.

`tools/v71-windows-acceptance-start.ps1` must verify the local checkout against that manifest before any Windows acceptance scenario runs and writes `target/windows-harness-binding/windows-harness-binding.json`. A different/newer/older harness checkout is therefore a hard failure even when the MSI/EXE/portable candidate hashes are correct. The final external gate and reviewer bundle revalidate the manifest hash, the binding record and the exact manifest file/member hash set.

The desktop acceptance runner also launches the already SHA-256-verified bound EXE itself for P5-01; the tester no longer manually chooses an installer executable.

### Candidate-bound Windows host/session evidence

Final Windows evidence must belong to one machine, one Windows user and one acceptance session. `tools/v71-windows-acceptance-start.ps1` clears stale Windows/DPI outputs and creates `target/windows-host-binding/windows-host-binding.json` before installer/portable/desktop evidence is produced. The binding stores a random session id plus one-way SHA-256 fingerprints derived from Windows MachineGuid and the current user SID; raw MachineGuid/SID values are not written to evidence.

Installer, portable, desktop and all four DPI reports carry the same session/host/user fingerprints. `windows-acceptance-evidence-check.py --require-host-binding` fails closed if reports from different machines, users or sessions are combined. The nested Windows evidence ZIP and the final reviewer bundle both retain and independently cross-check this binding against the exact GitHub candidate and connected-acceptance run. Re-running `v71-windows-acceptance-start.ps1` creates a new session and intentionally invalidates any earlier DPI reports.

The validator also requires timezone-aware report timestamps that do not predate the current `windows-host-binding`. Final evidence is a **closed set**: installer logs and screenshot files must be referenced by their report JSON, nested Windows evidence rejects unreferenced extras, and the outer reviewer bundle accepts only its documented exact member set. All acceptance ZIP readers share bounded archive guards for traversal/drive paths, normalized-name collisions, encrypted/symlink/special members and uncompressed-size/file-count limits.



## Iteration 70 local release evidence — MHL-509

- Knowledge Markdown exporter targeted tests: 9/9 PASS.
- Annotation Manager UI contract: 2/2 PASS.
- SQLite annotation export projection integration: 1/1 PASS.
- Full Application: 277 tests, 0 failures, 0 errors, 1 skipped.
- `LayerArchitectureTest`: 14/14 PASS.
- architecture/completeness/localization/static-release/supply-chain gates: PASS.
- full 16-project offline `test-compile`: BUILD SUCCESS.

These are local/source gates. The six external Windows/GitHub acceptance items remain separate and unchanged.


## Iteration 71 local release evidence — MHL-510

- AI host consent/secret contract: 7/7 PASS.
- AI plugin security contract: 3/3 PASS.
- Plugin SPI public surface: 4/4 PASS; full Plugin API: 28/28 PASS.
- Full Application: 284 tests, 0 failures, 0 errors, 1 skipped.
- `LayerArchitectureTest`: 14/14 PASS.
- architecture/completeness/localization/static-release/supply-chain gates: PASS.

Plugin API 1.3 adds `AI_PROVIDER` additively. The core ships with no AI provider enabled, per-book AI is opt-in, book-content/network consent are explicit per invocation, and provider credentials are namespaced through the existing `SecretStore` abstraction rather than plaintext settings. These are local/source gates; the six external Windows/GitHub acceptance items remain separate and unchanged.


## Iteration 72 — 8.0 release hardening / split full-regression baseline

Iteration 72 changes no production Java behavior. It closes release-test debt discovered only after the 8.0 feature backlog was complete:

- `ComicReaderWorkflowContractTest` now reflects the already-correct audiobook guard (`!currentAudio`) when asserting text-annotation refresh behavior.
- `BackupRestoreJourneyE2ETest` creates the current V59+ `reading_progress.last_device` column, matching the schema contract that the production backup adapter runs against after Flyway migration.
- the missing deterministic `golden/reader-rich.zip` Reader fixture is restored together with its synthetic second FB2 member; Reader golden ZIP regression is therefore runnable from the formal source package.
- Reader Surefire runs with `java.awt.headless=true`, which keeps PDFBox raster tests independent of a live X11 display while leaving production runtime flags unchanged.
- active plugin-development documentation now identifies Plugin API 1.3 as current.

Validation on the final hardening source:

- split full regression across all 15 child modules: **1,035 tests, 0 failures, 0 errors, 12 skips**;
- Infrastructure exhaustive split: all 132 test classes covered; aggregate reports **432 tests, 0 failures, 0 errors, 7 skips**;
- Reader: **77 tests, 0 failures, 0 errors, 1 skip** without an X11 server;
- UI: **93/93 PASS**; E2E: **14/14 PASS**; Bootstrap: **17/17 PASS**; OPDS: **20/20 PASS**; MCP: **6/6 PASS**; Web: **6/6 PASS**;
- Plugin API: **28/28 PASS**; Application: **284 tests, 0 failures, 0 errors, 1 skip**;
- `LayerArchitectureTest`: **14/14 PASS**;
- architecture, implementation-completeness, critical UI localization, static-release and supply-chain checks: **PASS**;
- full 16-project offline `test-compile`: **BUILD SUCCESS**.

A monolithic root `mvn test` was also attempted. It completed Shared, Domain, Application, Plugin API and plugin samples without failures and entered Infrastructure, but the available foreground execution window ended before the reactor completed. Therefore Iteration 72 deliberately does **not** claim a new monolithic full-reactor PASS; the split full-regression evidence above is the authoritative local baseline for this checkpoint. This does not change or waive MHL-010/011/012/017/018/019, which still require real Windows/GitHub evidence.

## Iteration 73 — external acceptance handoff hardening

Iteration 73 changes no production Java behavior and does not close any external gate. It refreshes `docs/release/CURRENT-VALIDATION.md`, adds the canonical operator sequence in `docs/release/EXTERNAL-ACCEPTANCE-RUNBOOK.md`, and adds `tools/external-acceptance-readiness.py` plus regression coverage to PR CI.

The readiness tool verifies that the PR/release/CodeQL/connected-acceptance workflows, candidate-bound Windows harness and final evidence validators are present and internally consistent. With `--run-regressions` it executes all seven evidence-policy regression scripts. Its only successful overall state is `READY_FOR_LIVE_EVIDENCE`; MHL-010/011/012/017/018/019 remain `OPEN_EXTERNAL` until real GitHub/Windows evidence is collected and the final candidate-bound aggregator passes.

## Iteration 74 — release-candidate integrity binding

Iteration 74 changes no production Java behavior and does not close any external gate. `tools/release-candidate-integrity.py` generates a platform-specific integrity record only after `dist/SHA256SUMS` exists and has been validated. The record binds the exact Git candidate SHA, Maven release identity, a deterministic digest of the Maven-runtime-free source tree, hashes of critical release-policy files, and every checksummed release payload file with size + SHA-256. A `dist/` file not represented by `SHA256SUMS`, a checksum mismatch, or a malformed candidate SHA fails closed.

`CI Release` generates and immediately re-verifies `release-candidate-integrity-linux.json`, `release-candidate-integrity-windows.json` and `release-candidate-integrity-macos.json` before platform artifacts are uploaded. `github-connected-acceptance.py` now requires the Windows integrity JSON + sidecar and independently verifies the exact CI Release `head_sha`, version/platform, `SHA256SUMS` digest, closed dist file set and MSI/EXE/portable sizes/digests before Windows evidence can be staged.

This record is **not** a cryptographic signature, SLSA provenance statement or external acceptance evidence. It is a deterministic SHA-256 integrity binding that strengthens candidate handoff. MHL-010/011/012/017/018/019 still require real GitHub/Windows evidence and remain OPEN until the final candidate-bound aggregator passes.

## Iteration 75 — self-contained final reviewer evidence binding

Iteration 75 changes no production Java behavior and does not close any external gate. It extends the Iteration 74 candidate-integrity chain through the **final reviewer bundle**, so the last offline verifier no longer trusts only derived integrity fields from `github-connected-acceptance.json`.

`tools/github-connected-acceptance.py` now preserves the exact Windows release integrity JSON, its SHA-256 sidecar and the original release `SHA256SUMS` when staging candidate evidence. `tools/github-acceptance-artifact-ingest.py` fail-closes unless those files are present, match the remotely digest-verified connected-acceptance artifact, and are semantically consistent with the candidate manifest and GitHub evidence. The ingest record carries the same integrity/checksum digests forward.

`tools/v71-finalize-external-acceptance.ps1` embeds those exact files into `myhomelib-7.1-final-external-evidence.zip`. `tools/v71-final-evidence-bundle-check.py` then independently verifies the integrity sidecar, candidate SHA, formal version, source-tree digest, release `SHA256SUMS`, deterministic dist-manifest digest and candidate MSI/EXE/portable hashes before accepting the immutable reviewer package. Tampering with the integrity JSON fails even when its sidecar and the outer bundle manifest are recomputed.

The integrity JSON remains a hash-bound evidence record, not a cryptographic signature or SLSA provenance statement. Real GitHub Actions, CodeQL/SBOM/SCA and Windows DPI/packaging evidence are still required for MHL-010/011/012/017/018/019.

## Iteration 76 — reviewer Markdown/JSON consistency hardening

Iteration 76 changes no production Java behavior and does not close any external gate. The final reviewer ZIP already carried machine-readable GitHub/final-decision JSON plus human-readable Markdown, but the offline verifier previously validated only the JSON semantics and outer hashes. A reviewer-facing `.md` file could therefore contradict the JSON if an attacker or broken post-processing step rewrote the ZIP and recomputed its unsiged hash manifests.

`tools/v71-final-evidence-bundle-check.py` now reconstructs the canonical GitHub connected-acceptance Markdown and final external-decision Markdown directly from the embedded JSON and requires byte-for-byte UTF-8 equality. This covers repository/branch/API version, candidate SHA, harness digest, overall status, every GitHub check row and every final evidence status row. Extra or contradictory reviewer text fails closed even when the outer `manifest.sha256` and ZIP sidecar are recomputed.

The reviewer-bundle regression suite includes dedicated tampering cases for both Markdown files. External readiness ratchets both checks, raising the local readiness baseline to **23/23 checks PASS** with the same **8/8 evidence-policy regressions PASS**. This remains offline evidence hardening only: MHL-010/011/012/017/018/019 still require real GitHub/Windows evidence.



## Iteration 77 — versioned external-evidence contract registry

Iteration 77 changes no production Java behavior and closes no external gate. The external acceptance chain already used `schemaVersion` fields, but expected versions were duplicated across individual scripts. That creates release-review drift risk: a producer and verifier can evolve independently, or a future/legacy JSON shape can be accepted by one stage while another stage expects a different contract.

`tools/evidence_contracts.py` is now the single fail-closed registry for the supported evidence scenarios and current schema versions. Release-candidate integrity, GitHub connected acceptance, artifact ingest, Windows harness/host reports, final external decision and readiness records use the registry. Producers derive the current version rather than repeating a literal, while verifiers reject missing/non-integer, legacy, future or wrong-scenario records until an explicit reviewed schema bump changes the registry and regression fixtures. No automatic release-evidence migration is performed.

The registry is included in both the release-candidate `criticalPolicyFiles` digest and the Windows acceptance-harness manifest. `tools/evidence-contracts-test.py` exercises all registered scenarios and future/legacy/missing-schema failure paths. External readiness ratchets the registry structure plus this ninth evidence-policy regression, raising the local baseline to **25/25 checks PASS** with **9/9 evidence-policy regressions PASS**. Real GitHub/Windows evidence remains mandatory for MHL-010/011/012/017/018/019.

## Iteration 78 — final local preflight

Iteration 78 is the final locally actionable preflight before live external acceptance. Unlike Iterations 73–77, it includes one production hardening change: `ContentIndexingQueueService` replaces `Executors.newFixedThreadPool` (which carries an implicit unbounded queue) with an owned zero-queue `ThreadPoolExecutor` using `SynchronousQueue` + `AbortPolicy`; the service's existing active-work cap remains the submission bound.

The complete split Java baseline is revalidated on the Iteration 78 tree: **1,035 tests, 0 failures, 0 errors, 12 skipped**. Infrastructure is exhaustive by split execution (**432/0/0/7**), including watcher/monitor tests separately. Application is **284/0/0/1**, Reader **77/0/0/1**, UI **93/0/0/0**, E2E **14/0/0/0**, Bootstrap **17/17**, Architecture **14/14**, OPDS **20/20**, MCP **6/6**, Web **6/6**, with three benchmark environment skips.

The local check sweep reports **84 PASS** and exactly **3 NEED_EXTERNAL_INPUT** checks; those three validate evidence that can only exist after the real Windows/GitHub run. Offline acceptance and external-readiness regressions pass; readiness remains **25/25 checks PASS / 9/9 evidence-policy regressions PASS** and `READY_FOR_LIVE_EVIDENCE`.

Linux release packaging is also exercised end-to-end after the split test baseline: production package build PASS, `jpackage` app-image launcher smoke PASS, extracted portable archive smoke PASS, deterministic `SHA256SUMS` generation PASS, and Stage23 portable/release-artifact validation PASS. This evidence proves the Linux packaging path only and does not close Windows/GitHub gates.

Historical Stage25 size ratchets and several old source-shape assertions were recalibrated to the reviewed post-feature architecture while preserving behavioral/extraction contracts; this is test-harness maintenance, not a claim of additional class-size refactoring.

The operational external plan is `docs/release/EXTERNAL-TEST-PLAN-ITERATION-78.md`. External MHL-010/011/012/017/018/019 remain OPEN.


## Iteration 79 — Windows Spring wiring startup hotfix

Real Windows execution of the Iteration 78 candidate exposed a startup blocker before manual acceptance could begin. `ContentIndexingQueueService` had two constructors after the Iteration 78 bounded-worker hardening: the normal four-dependency production constructor and a package-private five-argument constructor used by deterministic tests. With multiple constructor candidates and no explicit injection marker, Spring fell back to no-arg instantiation and failed with `NoSuchMethodException: ContentIndexingQueueService.<init>()`.

Iteration 79 fixes the actual production wiring instead of weakening the Windows harness: the four-dependency constructor is explicitly annotated for Spring injection. `ContentIndexingQueueServiceSpringWiringTest` creates the service through a real `AnnotationConfigApplicationContext`, preserving the package-private deterministic-test constructor while proving that Spring selects the production constructor.

Validation on the hotfix source:

- queue behavior + Spring wiring regression: **5/5 PASS**;
- full Application suite: **285 tests, 0 failures, 0 errors, 1 skip**;
- Bootstrap startup-task suite: **17/17 PASS**;
- `LayerArchitectureTest`: **14/14 PASS**.

Because production source changed, any Iteration 78 candidate-bound GitHub/Windows evidence is no longer valid for final acceptance. A new candidate SHA must be frozen after Iteration 79 and all six external gates remain OPEN until evidence is collected for that exact new SHA.


## Iteration 80 — full Spring runtime wiring hardening

A second real Windows launch after the Iteration 79 `ContentIndexingQueueService` hotfix progressed farther into Spring initialization and exposed another runtime-only constructor-selection defect: `OpdsAccessTokenService` had a normal production constructor plus a deterministic-test constructor but no explicit injection marker, so Spring attempted a missing no-arg constructor. Iteration 80 explicitly marks the production `ApplicationSettingsPort` constructor for injection and adds `OpdsAccessTokenServiceSpringWiringTest`.

Instead of waiting for another one-bean-at-a-time Windows failure, Iteration 80 adds a full eager `SpringContextStartupSmokeTest` in `myhomelib-bootstrap`. That test immediately found two additional runtime startup risks: `SqliteContinueReadingRepository` and `SqliteBookQueryRepository` were `final @Repository` classes and therefore could not be subclassed by Spring's class-based exception-translation proxies. Both repositories are now proxyable. The complete non-web Spring context now refreshes and closes successfully.

Two permanent source policies are added to PR CI: `spring-constructor-wiring-check.py` rejects ambiguous Spring stereotype constructors that lack either a no-arg path or explicit injection constructor, and `spring-proxyability-check.py` rejects final Spring classes that require class-based proxying. PR CI also runs the full Spring-context startup smoke.

Final local validation for this checkpoint:

- exhaustive split baseline: **1,038 tests, 0 failures, 0 errors, 12 skipped**;
- Infrastructure: **432/0/0/7**; Application: **286/0/0/1**; Reader: **77/0/0/1**; UI: **93/93**; OPDS: **20/20**; Bootstrap: **18/18**; Architecture: **14/14**; E2E: **14/14**;
- `OpdsAccessTokenService` wiring + functional token tests: **5/5 PASS**;
- affected SQLite repository tests: **6/6 PASS**;
- full eager Spring context: **1/1 PASS**;
- constructor-wiring and proxyability static policies: **PASS**.

Because Iteration 80 changes production source, Iteration 78/79 candidate-bound external evidence cannot be reused. Freeze a new Iteration 80 candidate SHA, rerun live GitHub gates, then restart Windows acceptance on that exact SHA. MHL-010/011/012/017/018/019 remain `OPEN_EXTERNAL`.

## Iteration 81 — Windows export/TTS usability hotfix

Real Windows testing after the Iteration 80 startup hardening exposed two user-visible defects after the application could progress into normal use. First, a request whose selected format was `FB2_ZIP` could still resolve a later device-profile fallback such as direct `FB2` before trying the converter for the selected format. The log therefore reported `format FB2_ZIP` while the committed target ended in `.fb2`. Export resolution now honors the preference order **per format**: existing direct artifact first, then a compatible converter, and only then legacy raw-copy fallback. This preserves crash-safe converter behavior while ensuring an explicitly selected `FB2_ZIP` is actually converted to a ZIP before considering a later `FB2` fallback.

Second, the Reader TTS voice chooser no longer relies on Java object rendering. It presents explicit labels from voice display name plus language tag, and duplicate labels receive deterministic numeric suffixes. This prevents implementation identities such as `SystemTtsProvider$...@...` from reaching the user even if a provider changes its internal voice object implementation.

Regression coverage added in this checkpoint:

- `ExportToDeviceUseCaseDeviceProfileTest`: selected `FB2_ZIP` must invoke the converter before later direct `FB2` fallback, commit a `.fb2.zip`, and preserve a real FB2 entry inside the archive;
- `Fb2ZipBookConverterTest`: the production converter itself creates a readable ZIP containing one `.fb2` entry;
- `TtsVoicePresentationTest`: voice labels are human-readable, do not expose object identity text, and duplicate labels are disambiguated.

Validation on the Iteration 81 tree:

- exhaustive split baseline: **1,042 tests, 0 failures, 0 errors, 12 skipped**;
- Application: **287/0/0/1**; Infrastructure: **433/0/0/7**; UI: **95/95**; Reader: **77/0/0/1**; Bootstrap: **18/18**; OPDS: **20/20**; E2E: **14/14**; Architecture: **14/14**;
- targeted production `Fb2ZipBookConverter` + system TTS provider: **4/4 PASS**;
- full eager Spring context startup: **1/1 PASS**;
- architecture/completeness/localization/static-release/supply-chain/Spring-wiring/proxyability source gates: **PASS**.

Because Iteration 81 changes production source, Iteration 80 candidate-bound GitHub/Windows evidence cannot be reused. Freeze a new Iteration 81 candidate SHA and restart live external acceptance on that exact candidate. MHL-010/011/012/017/018/019 remain `OPEN_EXTERNAL`.

## Iteration 82 — Windows workflow/state hotfix

Further real Windows testing after Iteration 81 exposed three workflow defects that unit-only/local presentation checks had not fully represented. First, Windows PowerShell 5.1 could corrupt Unicode voice names when redirected stdout used the active OEM/console code page. `SystemTtsProvider` now emits each Windows voice name as UTF-8 Base64 in an ASCII-only discovery row and decodes it in Java; malformed Base64 rows are discarded instead of leaking garbage into the UI.

Second, successful mass export of remote/not-yet-local books behaved correctly at the file layer but left stale UI state: downloaded/local status could remain unchanged and consumed checkbox selection stayed checked. Export completion is now an explicit UI transaction: only a successful non-cancelled export clears the exported checkbox ids and invokes a workspace refresh callback. Main/tree workspaces refresh local status, while Author Workspace reloads the current author and restores the previously selected book when still visible. Failed/cancelled exports deliberately keep selection for retry.

Third, the global Annotations/Notes command bypassed the normal Reader-safe navigation coordinator. It now releases an active Reader before loading the Annotation Manager workspace, with an ordered-interaction regression proving cleanup precedes workspace switch.

Validation on the Iteration 82 tree:

- exhaustive split baseline: **1,048 tests, 0 failures, 0 errors, 12 skipped**;
- Infrastructure: **436/0/0/7**; Application: **287/0/0/1**; UI: **98/98**; Reader: **77/0/0/1**;
- Bootstrap: **18/18** including eager Spring-context smoke; OPDS **20/20**; E2E **14/14**; Architecture **14/14**;
- Windows TTS provider targeted regressions: **6/6 PASS**;
- mass-export completion + Annotation/Notes navigation targeted regressions: **6/6 PASS**.

Because Iteration 82 changes production source, Iteration 81 candidate-bound evidence cannot be reused. Freeze a new Iteration 82 candidate SHA and rerun the six live external gates MHL-010/011/012/017/018/019 against that exact candidate.

## Iteration 83 — Windows TTS discovery + Annotation Manager FXML hotfix

Further real Windows testing after Iteration 82 clarified two remaining defects. The TTS chooser was receiving PowerShell parser diagnostics and fragments of the discovery script through a merged stdout/stderr stream; those lines were then accepted as voice records. Windows voice discovery now uses PowerShell `-EncodedCommand` (UTF-16LE Base64 script transport), emits only strict `MHLVOICE|<UTF-8 Base64 name>|<locale>` rows, parses only that framing, keeps stderr separate and treats every non-zero discovery exit as an I/O failure instead of UI data.

Annotations/Notes navigation itself was already Reader-safe, but the actual Annotation Manager FXML still failed to load on JavaFX 21 because the string `CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN` cannot be coerced into the `Callback`-typed `columnResizePolicy` property. The FXML literal is removed; `TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN` is now applied programmatically in `AnnotationManagerWorkspaceController`. A source-level contract prevents the invalid literal from returning, and a display-capable JavaFX `FXMLLoader` regression exercises the real FXML load path.

Validation on the Iteration 83 tree:

- exhaustive split baseline: **1,049 tests, 0 failures, 0 errors, 12 skipped**;
- Infrastructure: **437/0/0/7**; Application: **287/0/0/1**; UI: **98/98**; Reader: **77/0/0/1**;
- Windows TTS provider targeted regressions: **7/7 PASS**;
- Annotation Manager source/FXML contract: **3/3 PASS** locally; display-capable `FXMLLoader` test is present for Windows/desktop CI;
- full eager Spring context: **1/1 PASS**; E2E: **14/14 PASS**; Architecture: **14/14 PASS**;
- architecture/completeness/localization/static-release/supply-chain/Spring-wiring/proxyability gates: **PASS**;
- external-acceptance readiness: **25/25 checks + 9/9 regressions PASS**, overall `READY_FOR_LIVE_EVIDENCE`;
- clean-source offline `test-compile`: **16/16 Maven projects BUILD SUCCESS**.

Because Iteration 83 changes production source, Iteration 82 candidate-bound evidence cannot be reused. Freeze a new Iteration 83 candidate SHA and rerun all six live external gates MHL-010/011/012/017/018/019 against that exact candidate.
