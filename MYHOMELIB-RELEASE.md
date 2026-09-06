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
Ubuntu + Windows + macOS
./mvnw clean verify -Pproduction
```

After verification, platform packaging creates `jpackage --type app-image` artifacts. A headless `--release-smoke` runs against the packaged launcher. Tagged releases publish platform archives and SHA-256 checksums in `SHA256SUMS` only after verification jobs succeed.

A normal packaged application does not download Maven dependencies at runtime.

### Release supply-chain artifacts

Before the cross-platform package matrix starts, the release workflow runs a dedicated supply-chain gate:

1. the exact release-candidate commit must already have a successful CodeQL analysis on the default branch, and open High/Critical code-scanning alerts block the release;
2. OWASP Dependency-Check blocks dependencies at CVSS 7.0 or higher unless a narrow, justified and unexpired suppression exists;
3. CycloneDX aggregate SBOM generation must succeed;
4. both `bom.json` and `bom.xml`, Dependency-Check HTML/JSON/SARIF reports, and the candidate-bound CodeQL gate JSON/Markdown are retained as release artifacts.

The formal source-release archive contains `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.jar`, wrapper properties and a SHA-256 file for the bundled wrapper JAR. The embedded `.mvn/maven/apache-maven-3.9.6` distribution is intentionally part of the formal source release, so `./mvnw -v` works directly from the extracted archive without downloading Maven itself. Project dependencies are still external; a fully offline build therefore additionally requires the prepared offline Maven repository.

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

1. `./mvnw clean verify -Pproduction` succeeds with dependency access;
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

Original Markdown source notes are preserved under `docs/archive/source-notes/`.

## 2026-09-02 refactoring completion note

The source tree has passed the repository's offline architecture, lifecycle, functional, Reader, localization, performance-baseline and static release checks after the stabilization pass. See `REFACTORING_COMPLETION.md` for the exact source-level baseline. This does not waive the formal release boundary above: compiled Maven verification, platform packaging and real desktop smoke testing are still mandatory before publishing a binary release.

## 2026-09-05 Stage 05 portable-launcher hardening

A real `jpackage` app-image probe found that portable mode could miss `myhomelib2.ini` when the native launcher was started from a working directory different from the launcher directory. `AppPaths.launchDir()` now keeps an explicit `-Dmyhomelib.launchDir` as the highest-priority override, derives the directory of the native process when the `jpackage.app-version` runtime marker is present, and retains `user.dir` as the fallback for ordinary JVM/IDE launches.

The Linux JDK 21 `jpackage` acceptance probe places `myhomelib2.ini` beside `dist/MyHomeLib/bin/MyHomeLib`, starts that launcher from an unrelated working directory, and confirms that runtime directories are created under `dist/MyHomeLib/bin/data` while the normal profile data directory is not created. Windows portable/installer execution, DPI, upgrade and uninstall acceptance remain mandatory before final release.

## Stage 05 P4 Windows DPI acceptance hardening — 2026-09-05

- `tools/windows-ui-acceptance.ps1` now cross-checks the requested 100/125/150/200% run against `GetDpiForSystem()` (96/120/144/192 DPI).
- On a single-monitor acceptance machine, a known system-DPI mismatch is an automatic `AUTO-0 = FAIL`; an unavailable API observation is `BLOCKED`, so the report cannot silently claim PASS.
- On multi-monitor Windows, a system-DPI mismatch is `BLOCKED` rather than a false FAIL because the monitor hosting MyHomeLib can use different per-monitor scaling; P4-01 must confirm that monitor explicitly.
- This is acceptance-tooling hardening only; production Java code is unchanged.

## 2026-09-06 connected GitHub acceptance

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

