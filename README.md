# MyHomeLib Enterprise 8.0.0

Current source checkpoint: **MyHomeLib 8.0.0 FINAL AUDITED local candidate, 2026-09-18**. After the Iteration 85 baseline, the tree received an additional deep audit across localization, import/export semantics, backup/restore crash safety, external-process lifecycle, bounded I/O/HTTP, filename portability, collection switching, full-text indexing durability, SQLite migrations and Spring wiring. One uninterrupted final `clean verify -Pproduction` on the final code state completed with **17/17 modules BUILD SUCCESS**, **400 test suites / 1,189 tests / 0 failures / 0 errors / 12 skipped**. The same audited state has **3,352 synchronized desktop localization keys in UK/EN/BG**, **74 synchronized Web/OPDS keys**, and **1,125 production Java files** with the implementation-completeness guard passing. Linux validation uses the exact production **JavaFX 21.0.12** artifacts without the earlier 21.0.2 test override. The six live external gates MHL-010/011/012/017/018/019 remain `OPEN_EXTERNAL`; Windows-native, connected GitHub, real Calibre/external-integration and signing acceptance are not inferred from the local Linux/offline PASS.

The chronological development record is consolidated in [docs/history/HISTORY.md](docs/history/HISTORY.md). Original iteration/task/continuation records are kept under `docs/history/records/`. The six external backlog items MHL-010/011/012/017/018/019 remain OPEN until real GitHub/Windows acceptance evidence exists.

## Quick start

Requirements:

- JDK 21;
- this **DEVELOPER-SOURCE** handoff includes `mvnw`/`mvnw.cmd` and bundled Maven 3.9.6; the separately generated formal source-release archive intentionally excludes that Maven payload and requires Maven 3.9.6+ externally;
- dependency access, or a populated Maven cache/offline dependency repository;
- a JavaFX-compatible desktop environment.

Build:

```bash
./build.sh
```

Windows PowerShell:

```powershell
.\build.ps1
```

Run:

```bash
./run.sh
```

Windows:

```powershell
.\run.ps1
```

Package the executable Boot JAR:

```bash
./package.sh
```

or:

```powershell
.\package.ps1
```

Expected JAR: `myhomelib-bootstrap/target/myhomelib-bootstrap-8.0.0.jar`.

## What is included

The project supports multiple SQLite collections, local and online catalogues, INPX and directory import, Lucene search, navigation facets, reading history/user data, backup/restore, online book download through MyHomeLib-compatible `ConnectionScript`, OPDS, MCP, export/device actions, external file-based localization, the integrated Reader and a versioned plugin SPI core with a provider-neutral AI extension point plus an optional built-in OpenAI Responses adapter that remains inactive until credentials and explicit user consent are supplied.

For the current feature contract, see [MYHOMELIB-FEATURES.md](MYHOMELIB-FEATURES.md).

## Data location

Normal installed mode stores runtime data under `${user.home}/.myhomelibcorp`.
Portable mode is enabled by `myhomelib2.ini` beside the launcher or `-Dmyhomelib.portable=true` and stores data under `<launch-dir>/data`.
Both can be overridden with JVM properties documented in [MYHOMELIB-OPERATIONS.md](MYHOMELIB-OPERATIONS.md).

## Documentation

Active documentation is intentionally small:

- [ARCHITECTURE.md](ARCHITECTURE.md) — current module/layer architecture and dependency rules;
- [MYHOMELIB-FEATURES.md](MYHOMELIB-FEATURES.md) — supported functionality and deliberate limits;
- [MYHOMELIB-OPERATIONS.md](MYHOMELIB-OPERATIONS.md) — data paths, collections, online download, backup/restore, upgrades and troubleshooting;
- [MYHOMELIB-DEVELOPMENT.md](MYHOMELIB-DEVELOPMENT.md) — build, tests, performance and contribution/release gates;
- [MYHOMELIB-RELEASE.md](MYHOMELIB-RELEASE.md) — v8.0 release/upgrade summary and current validation boundary;
- `docs/README.md` — documentation map;
- `docs/history/HISTORY.md` — consolidated human-readable development history;
- `docs/history/source-notes/` and `docs/history/records/` — archived source evidence.

`Lang/README.md` and the Markdown files under `myhomelib-ui/src/main/resources/help/` are runtime localization/help assets and are intentionally kept separate from project documentation.

## Validation note

The repository contains offline architecture/static/regression gates and GitHub Actions workflows for JDK 21 on Windows, Linux and macOS. Critical JavaFX localization is also guarded by stable-key and UK/EN/BG catalogue consistency checks. Repository-side 8.0 acceptance for PR enforcement/timing, SBOM, Dependency-Check and CodeQL can be collected with the manually dispatched **GitHub connected acceptance** workflow; it writes machine-readable JSON plus a reviewer Markdown summary. A connected `mvn clean verify -Pproduction` and real CI run remain the authoritative compiled/tested release gate. Runtime startup itself does not require Maven or dependency downloads.

The canonical six-gate external acceptance handoff is `docs/release/EXTERNAL-ACCEPTANCE-RUNBOOK.md`; `tools/external-acceptance-readiness.py` validates repository readiness but never closes an external gate.

Release supply-chain gates additionally generate CycloneDX JSON/XML SBOMs (`-Psbom`), run OWASP Dependency-Check with CVSS >= 7.0 blocking policy (`-Pdependency-check`), and run CodeQL on pull requests, main branches and a schedule. A release is fail-closed unless the exact candidate commit already has a successful CodeQL analysis; connected acceptance verifies GitHub-declared SHA-256 digests for the downloaded supply-chain and Windows Actions artifacts and extracts the exact MSI/EXE/portable candidates. Final Windows acceptance should start with `tools/v71-windows-acceptance-start.ps1`, which re-downloads the connected-acceptance artifact by run id, verifies its GitHub Actions digest, verifies the local Windows acceptance harness against the exact candidate-bound `acceptance-harness.sha256`, creates one clean candidate-bound Windows host/user/session record, binds real-previous MSI/portable testing and the interactive EXE/data-migration desktop smoke to that session, and leaves only the four DPI passes on the same host/user/session before finalization. Artifact types are intentionally different. The audited **DEVELOPER-SOURCE** handoff includes `mvnw`, `mvnw.cmd`, the wrapper payload and bundled Maven 3.9.6 so the audited build entrypoint is reproducible; it does **not** include the large local dependency cache, so Maven still needs dependency access or the prepared offline repository. The separately generated **formal source-release ZIP** produced by the source packager remains Maven-payload-free by policy: `mvnw`, `mvnw.cmd` and the complete `.mvn/` directory are excluded and Maven 3.9.6+ must be supplied externally. `pom.xml` is authoritative in both artifact types.
