# MYHOMELIB — Release and Upgrade

**Source version:** 8.0.0  
**Documentation snapshot:** 18 September 2026

## Release focus

8.0 builds on the hardened 7.x line and focuses on complete Reader workflows, annotations/notes UX, large-catalog stability, user-data safety and truthful release validation.

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

8.0 is an additive forward migration from the supported v7 line. V1–V36 are historical baseline and must remain immutable. Later migrations extend statistics, search/manifest compatibility, metabib/online state and subsequent schema corrections present in the repository. The metadata database has an independent migration chain for collection/download state.

Before upgrade, keep a restorable v7 backup. After first 8.0 start, verify catalogue/user data, local downloads, search health, annotations/notes and online collection settings.

Rollback is backup-based: restore the pre-upgrade database/application state rather than deleting Flyway rows or columns manually.

## Search/cache behavior after upgrade

Older manifest/search compatibility values may trigger a one-time revalidation or index rebuild. A failed rebuild must leave the old committed Lucene index available. Lucene can be rebuilt; user data and local book files cannot, so retain the catalogue backup until acceptance is complete.

## Online compatibility note

Historical MyHomeLib/Flibusta servers may return a ZIP whose internal FB2 filename differs from the catalogue `archiveEntry`. Current 8.0 resolves this safely and persists the actual member. Example:

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

For a **publishable release**, `SHA256SUMS` is mandatory and Stage23 validation must run with `--require-checksums`. A raw/intermediate app-image may be validated without checksums during local packaging work; in that mode Stage23 intentionally reports their absence as a warning rather than treating the intermediate artifact as published.

The formal full desktop release also requires the verified bundled **libmobi 0.12 / `mobitool`** payload and its license. Developer/intermediate app-images may omit bundled libmobi; MOBI/AZW conversion can still use a compatible configured/system `mobitool`. This optional intermediate mode is not equivalent to the full release gate, which remains fail-closed when the bundled libmobi payload is incomplete.

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

## Windows installer lifecycle gate

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

- `docs/history/HISTORY-UA.md`.

Exact low-level checkpoint/source evidence remains under `docs/history/records/` and `docs/history/source-notes/`.

Original Markdown source notes are preserved under `docs/history/source-notes/`.

## Current external acceptance flow

The six candidate-bound external gates remain `OPEN_EXTERNAL` until real evidence exists for the exact frozen candidate:

- `MHL-010` — GitHub required-check enforcement and connected acceptance;
- `MHL-011` — Windows DPI acceptance at 100/125/150/200%;
- `MHL-012` — Windows MSI/EXE/portable lifecycle and real previous-version upgrade;
- `MHL-017` — candidate-bound CycloneDX SBOM evidence;
- `MHL-018` — candidate-bound dependency vulnerability scan evidence;
- `MHL-019` — candidate-bound CodeQL/SAST evidence.

Canonical operator documents:

- `docs/release/EXTERNAL-ACCEPTANCE-RUNBOOK.md`;
- `docs/release/EXTERNAL-TEST-PLAN.md`;
- `docs/release/CURRENT-VALIDATION.md`;
- `docs/release/WHAT-REMAINS-UA.md`.

The legacy-compatible `v71-*` script names are retained only as stable tool entry points; they do not change the product version. External evidence must stay bound to one candidate SHA, one verified artifact set and the documented Windows host/session flow.


## Latest deep-audited local source checkpoint — 2026-10-02 / V34 post-real-data

V34 includes V30–V34 product work and the subsequent large real-data validation/tuning pass. Exact-current local evidence:

- Maven 3.9.16 / JDK 21 full UTF-8/Xvfb `clean verify`: **2076 tests / 0 failures / 0 errors / 22 skipped; 21/21 reactor projects SUCCESS**;
- UI: **366 tests**; Reader: **150 tests**; Architecture: **20/20**; E2E: **15/15**;
- user-action inventory: **484/484 mapped** (273 FXML + 205 programmatic + 6 Web); behavior evidence: **483 automated + 1 justified exclusion = 484/484**;
- all 484 actions link deterministically to **31** documented flows in `docs/current/USER-FUNCTIONS-UA.md`;
- static release, architecture, supply-chain and UK/EN/BG UI/Web/help localization gates: **PASS**;
- SQLite migration chain: **67 immutable migrations**;
- real supplied Flibusta INPX: **707,154 fresh imports / 0 errors**; identical re-import **0 imported / 707,154 skipped / 0 errors**;
- real-size **V65→V67** migration PASS and final SQLite `quick_check=ok`, `integrity_check=ok`;
- Lucene rebuild: **571,947 active docs** in **38.599 s**; second startup/reopen **214 ms** with reusable fresh index;
- V33 real-data 5-sample bounded recommendation probe: **329–1,387 ms**, all requests returned ≤10 results;
- V34 synthetic 1000-page CBZ stress: **1000/1000 scanned, 100 exact duplicate groups, 0 failures, 6.270 s**;
- all three supplied real FB2 ZIP archives pass the reader probe.

Passive Book Details enrichment was also corrected after external-test UX review: row selection now renders catalog metadata immediately and no longer acquires a global `QUERY` library lease; rich content/cover/recommendation enrichment remains managed background work with stale-result guards. The V33 production query was changed only after the real 1.59-GB catalog exposed the unbounded path. The 21/21 full `clean verify` above was run after the final production change. Detailed evidence is in `docs/release/REAL-DATA-ACCEPTANCE-V34-2026-10-02.md` and `docs/release/CURRENT-VALIDATION.md`.

Physical/platform/provider gates remain external: unrestricted real-browser PWA acceptance, physical Kobo, Windows installer/portable/updater/Calibre/DPI/DPAPI/signing, real Readwise, real large CBR/CB7 corpus, and exact-candidate GitHub/SBOM/SCA/CodeQL/distribution evidence.
