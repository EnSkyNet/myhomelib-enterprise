# MyHomeLib Enterprise 7.1.0

Current source checkpoint: **Iteration 83, 2026-09-13 — Windows TTS discovery + Annotation Manager FXML hotfix after real-host testing**. A real Windows run after Iteration 82 showed that the voice chooser was receiving PowerShell parser diagnostics/script fragments rather than only voice data, and that Annotations/Notes still failed because JavaFX could not coerce `CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN` from an FXML string into a `Callback`. Iteration 83 moves Windows voice discovery to PowerShell `-EncodedCommand`, frames valid rows as `MHLVOICE|<UTF-8 Base64>|<locale>`, separates stderr from stdout, fails closed on non-zero discovery exit, and ignores all unframed diagnostics. The Annotation Manager now configures the JavaFX resize-policy callback in Java rather than in FXML, with source-contract and display-capable `FXMLLoader` regressions. The authoritative exhaustive split baseline is **1,049 tests, 0 failures, 0 errors, 12 skipped**; Infrastructure is **437/0/0/7**, Application **287/0/0/1**, UI **98/98**, Reader **77/0/0/1**, Bootstrap **18/18**, OPDS **20/20**, E2E **14/14**, and Architecture **14/14**. Full eager Spring context remains **1/1 PASS**. Because production source changed, Iteration 82 candidate-bound external evidence is invalid for final acceptance; MHL-010/011/012/017/018/019 remain `OPEN_EXTERNAL` and must run against the new Iteration 83 SHA.

The chronological development record is consolidated in [docs/history/MYHOMELIB-HISTORY-ITERATIONS.md](docs/history/MYHOMELIB-HISTORY-ITERATIONS.md). Original iteration/task/continuation records are kept under `docs/history/records/`. The six external backlog items MHL-010/011/012/017/018/019 remain OPEN until real GitHub/Windows acceptance evidence exists.

## Quick start

Requirements:

- JDK 21;
- Maven 3.9.6+ installed on `PATH` when building from the formal source archive;
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

Expected JAR: `myhomelib-bootstrap/target/myhomelib-bootstrap-7.1.0.jar`.

## What is included

The project supports multiple SQLite collections, local and online catalogues, INPX and directory import, Lucene search, navigation facets, reading history/user data, backup/restore, online book download through MyHomeLib-compatible `ConnectionScript`, OPDS, MCP, export/device actions, external file-based localization, the integrated Reader and a versioned plugin SPI core with an optional provider-neutral AI extension point (no provider enabled by default).

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
- `docs/history/source-notes/` — original historical Markdown notes retained as source evidence;
- `docs/history/records/` — iteration/checkpoint/task evidence moved out of the source root.

`Lang/README.md` and the Markdown files under `myhomelib-ui/src/main/resources/help/` are runtime localization/help assets and are intentionally kept separate from project documentation.

## Validation note

The repository contains offline architecture/static/regression gates and GitHub Actions workflows for JDK 21 on Windows, Linux and macOS. Critical JavaFX localization is also guarded by stable-key and UK/EN/BG catalogue consistency checks. Repository-side 7.1 acceptance for PR enforcement/timing, SBOM, Dependency-Check and CodeQL can be collected with the manually dispatched **GitHub connected acceptance** workflow; it writes machine-readable JSON plus a reviewer Markdown summary. A connected `mvn clean verify -Pproduction` and real CI run remain the authoritative compiled/tested release gate. Runtime startup itself does not require Maven or dependency downloads.

The canonical six-gate external acceptance handoff is `docs/release/EXTERNAL-ACCEPTANCE-RUNBOOK.md`; `tools/external-acceptance-readiness.py` validates repository readiness but never closes an external gate.

Release supply-chain gates additionally generate CycloneDX JSON/XML SBOMs (`-Psbom`), run OWASP Dependency-Check with CVSS >= 7.0 blocking policy (`-Pdependency-check`), and run CodeQL on pull requests, main branches and a schedule. A release is fail-closed unless the exact candidate commit already has a successful CodeQL analysis; connected acceptance verifies GitHub-declared SHA-256 digests for the downloaded supply-chain and Windows Actions artifacts and extracts the exact MSI/EXE/portable candidates. Final Windows acceptance should start with `tools/v71-windows-acceptance-start.ps1`, which re-downloads the connected-acceptance artifact by run id, verifies its GitHub Actions digest, verifies the local Windows acceptance harness against the exact candidate-bound `acceptance-harness.sha256`, creates one clean candidate-bound Windows host/user/session record, binds real-previous MSI/portable testing and the interactive EXE/data-migration desktop smoke to that session, and leaves only the four DPI passes on the same host/user/session before finalization. The formal source-release archive contains **no Maven runtime or Maven Wrapper payload**: `mvnw`, `mvnw.cmd` and the complete `.mvn/` directory are excluded. `pom.xml` remains part of the source contract. Install Maven 3.9.6+ separately; project dependencies are also external, so a fully offline build requires both that Maven runtime and the prepared offline dependency repository. Repository/CI checkouts may still carry a wrapper for developer convenience, but it is not shipped in the formal source ZIP.
