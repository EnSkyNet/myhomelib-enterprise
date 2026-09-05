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

The current MSI is built from the checked-out source automatically. Real application data migration, DPI 100/125/150/200%, GUI sidebar/Reader cycles, backup/restore and EXE installer UI remain manual Windows release gates.

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
