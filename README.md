# MyHomeLib Enterprise 7.1.0

Desktop library manager for Windows, Linux and macOS built on Java 21, JavaFX, SQLite/Flyway and Lucene, with an integrated Canvas-based reader.

## Quick start

Requirements:

- JDK 21;
- network access for the first Maven Wrapper dependency download, or a populated local Maven cache;
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

Expected JAR: `myhomelib-bootstrap/target/myhomelib-bootstrap-7.1.0.jar`.

## What is included

The project supports multiple SQLite collections, local and online catalogues, INPX and directory import, Lucene search, navigation facets, reading history/user data, backup/restore, online book download through MyHomeLib-compatible `ConnectionScript`, OPDS, MCP, export/device actions, external file-based localization and the integrated Reader.

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
- [MYHOMELIB-RELEASE.md](MYHOMELIB-RELEASE.md) — v7.1 release/upgrade summary and current validation boundary;
- `docs/history/MYHOMELIB-HISTORY-*.md` — consolidated development history;
- `docs/archive/source-notes/` — original historical Markdown notes retained as source evidence.

`Lang/README.md` and the Markdown files under `myhomelib-ui/src/main/resources/help/` are runtime localization/help assets and are intentionally kept separate from project documentation.

## Validation note

The repository contains offline architecture/static/regression gates and GitHub Actions workflows for JDK 21 on Windows, Linux and macOS. Critical JavaFX localization is also guarded by stable-key and UK/EN/BG catalogue consistency checks. Repository-side 7.1 acceptance for PR enforcement/timing, SBOM, Dependency-Check and CodeQL can be collected with the manually dispatched **GitHub connected acceptance** workflow; it writes machine-readable JSON plus a reviewer Markdown summary. A connected `./mvnw clean verify -Pproduction` and real CI run remain the authoritative compiled/tested release gate. Runtime startup itself does not require Maven or dependency downloads.

Release supply-chain gates additionally generate CycloneDX JSON/XML SBOMs (`-Psbom`), run OWASP Dependency-Check with CVSS >= 7.0 blocking policy (`-Pdependency-check`), and run CodeQL on pull requests, main branches and a schedule. A release is fail-closed unless the exact candidate commit already has a successful CodeQL analysis; connected acceptance verifies GitHub-declared SHA-256 digests for the downloaded supply-chain and Windows Actions artifacts and extracts the exact MSI/EXE/portable candidates. Final Windows acceptance should start with `tools/v71-windows-acceptance-start.ps1`, which re-downloads the connected-acceptance artifact by run id, verifies its GitHub Actions digest, verifies the local Windows acceptance harness against the exact candidate-bound `acceptance-harness.sha256`, creates one clean candidate-bound Windows host/user/session record, binds real-previous MSI/portable testing and the interactive EXE/data-migration desktop smoke to that session, and leaves only the four DPI passes on the same host/user/session before finalization. The formal source release includes `mvnw`, `mvnw.cmd`, `.mvn/wrapper/*` and the embedded Maven 3.9.6 distribution; it does not depend on a separately transferred hidden Maven bundle. A first connected build still needs project-dependency access unless a populated offline repository is supplied.
