# MyHomeLib 1.0.0

Modern Java/JavaFX MyHomeLib desktop library with SQLite, Flyway, Lucene and a lightweight Canvas-based reader.

## Requirements

- JDK 21
- No preinstalled Maven is required: the included Maven Wrapper downloads Maven on first use (or uses a local cache).
- Windows, Linux or macOS with JavaFX-compatible desktop environment

## Build

Linux/macOS:

```bash
./build.sh
```

Windows PowerShell:

```powershell
.\build.ps1
```

## Run

Linux/macOS:

```bash
./run.sh
```

Windows:

```powershell
.\run.ps1
```

## Package executable Boot JAR

```bash
./package.sh
```

or Windows:

```powershell
.\package.ps1
```

Output: `myhomelib-bootstrap/target/myhomelib-bootstrap-1.0.0.jar`.

## Library/import features

- FB2/FBD, EPUB and TXT catalogue import;
- ZIP/FB2ZIP/CBZ/JAR archives;
- 7z archives through Apache Commons Compress;
- RAR archives through junrar;
- INPX import with all `.inp` parts, `structure.info` and `archives.info` support;
- idempotent INPX refresh preserving local storage and user state;
- Stage 6 online-catalog revision tracking with stable remote source identity, SHA-256 source/book fingerprints, downloaded baselines and pending update classification;
- online-library settings and cancellable HTTP download with `.part` cleanup;
- archive-entry aware opening/export/cover lookup;
- bounded batch import and streaming directory traversal.

Nested archives are intentionally not recursively unpacked. This keeps archive scanning predictable and avoids decompression bombs.

## Reader

The reader uses JavaFX Canvas rather than WebView. FB2/FBD and FB2 ZIP are parsed incrementally, pages are laid out on demand and bounded caches are used to keep memory consumption predictable. It supports rich text, built-in reading presets, categorized typography/color/layout/navigation/status settings, global defaults plus per-book overrides, live preview, configurable tap zones, a separate status bar, search, TOC, bookmarks, Shift-drag text selection/Ctrl+C, swipe navigation and autoscroll. Reading position is flushed on a short background interval and again on normal workspace close. Language-aware hyphenation uses bundled uk/en/bg/ru dictionaries with a conservative fallback. EPUB3 nav and EPUB2 NCX fragment anchors resolve to exact text positions; EPUB/TXT remain supported by the same reader pipeline.

## Navigation and reading history

Catalogue navigation includes Authors, Series, Genres, Years, Languages, Archives, Keywords, Groups, Reviews, Already Read, History and All Books. The View menu also exposes timestamped Recent books. Reader opens are journaled separately from resume-position data, so clearing reading history does not remove reading progress or bookmarks. Toolbar Back/Forward and `Alt+Left` / `Alt+Right` restore workspace navigation.

## OPDS

The desktop distribution includes the separate `myhomelib-opds` delivery module. By default the OPDS catalogue binds to `127.0.0.1:8088`; use **Tools → OPDS server...** to change the port/bind address, enable optional Basic authentication, configure autostart, and start/stop the service. The catalogue exposes authors, series, genres, search, book metadata and streaming downloads for local books. `/health` provides a lightweight lifecycle probe. Binding beyond localhost is explicitly marked as network exposure in the UI.

## Localization and context help

UI translations are file-based. Each language is a standalone UTF-8 `Lang/<code>.json` schema-versioned catalogue. Schema v2 keeps stable FB2 genre codes separate from localized display names, so changing the UI language never changes book/genre relations. `config/language.txt` stores the selected language, `config/available-languages.txt` is generated from detected catalogues, and `config/language-diagnostics.txt` records schema/key coverage diagnostics. Legacy schema-v1 packs remain readable with fallback; packs requiring a newer schema are ignored safely. Signing is optional, never mandatory.

F1 help is context-sensitive through a central `HelpTopicRegistry`. Workspaces/dialogs map to bundled Markdown pages with legacy TXT/HTML fallback; shipped help is available for Ukrainian, English and Bulgarian. See `LANGUAGE_SYSTEM.md` and `Lang/README.md`.

## Versioned backup and restore

Collection backups use SQLite `VACUUM INTO` so committed WAL data is captured consistently without copying a live database file. With **Versioned user data (LibID)** enabled, the backup also contains `user-data.json` schema v2 with ratings/progress/reviews, bookmarks, reading history/statistics, groups/favorites, saved searches, unified filter state, and global/per-book Reader preferences.

Restore supports both a full staged database replacement and a portable **user-data-only** mode. Portable restore maps book-scoped state by stable `LibID` first and uses the previous internal book ID only as a same-catalogue fallback, allowing user state to be applied to a freshly re-imported catalogue. Previous v1 manifests are migrated sequentially; legacy database-only backups remain supported and enter the normal Flyway migration chain after reopen.

## Architecture baseline

The repository uses an enforced modular-monolith baseline. The desktop dependency graph is `shared -> domain -> application`, with infrastructure implementing application ports, JavaFX UI depending on application/domain/reader, and bootstrap acting as the composition root. The embedded reader is isolated from the library application and depends on `shared` only; its portable packages do not depend on JavaFX.

Run the dependency/source guard without Maven:

```bash
python3 tools/architecture-check.py
```

When Maven dependencies are available, also run:

```bash
./mvnw -pl myhomelib-architecture-tests -am test
```

See `ARCHITECTURE.md` and `docs/architecture/ARCHITECTURE_DEBT.md`.

## Cross-platform CI and release

GitHub Actions verifies the full Maven reactor on Windows, Linux and macOS with JDK 21, then builds a self-contained `jpackage --type app-image` portable archive for each platform. The packaged native launcher is exercised with a headless `--release-smoke` path before artifacts are accepted. Tagged `v*` builds publish platform archives, bootstrap/MCP JARs and a consolidated `SHA256SUMS`. Runtime startup does not download Maven dependencies or require network access; explicitly configured online-library features remain optional. See `docs/release/CROSS_PLATFORM_RELEASE.md`.

## Notes

The production storage target of 1.0.0 is SQLite. Android/iOS are future platform targets; this ZIP is the desktop JavaFX source release. A full Maven build was not executed in the packaging container because external Maven dependencies could not be resolved there. The source tree includes the Maven Wrapper; run `build.ps1`/`build.sh` on a machine with dependency access or a populated Maven cache before treating a binary build as externally validated. See `RELEASE_VALIDATION.txt` for the exact local checks and intentionally skipped external gates.
