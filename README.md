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

The reader uses JavaFX Canvas rather than WebView. FB2/FBD and FB2 ZIP are parsed incrementally, pages are laid out on demand and bounded caches are used to keep memory consumption predictable. It supports rich text, themes, font/layout settings, search, TOC, bookmarks, swipe/tap navigation, autoscroll and reading-position persistence. EPUB/TXT are supported by the reader pipeline as well.

## Navigation and reading history

Catalogue navigation includes Authors, Series, Genres, Years, Languages, Archives, Keywords, Groups, Reviews, Already Read, History and All Books. The View menu also exposes timestamped Recent books. Reader opens are journaled separately from resume-position data, so clearing reading history does not remove reading progress or bookmarks. Toolbar Back/Forward and `Alt+Left` / `Alt+Right` restore workspace navigation.

## Localization

UI translations are file-based. Each language is a standalone UTF-8 `Lang/<code>.json` catalogue. `config/language.txt` stores the selected language, while `config/available-languages.txt` is generated automatically from the language files currently detected. The language directory is rescanned at startup and whenever the language menu is opened, so adding a valid JSON catalogue adds the language without Java/FXML changes. See `LANGUAGE_SYSTEM.md` and `Lang/README.md`.

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

## Notes

The production storage target of 1.0.0 is SQLite. Android/iOS are future platform targets; this ZIP is the desktop JavaFX source release. A full Maven build was not executed in the packaging container because external Maven dependencies could not be resolved there. The source tree includes the Maven Wrapper; run `build.ps1`/`build.sh` on a machine with dependency access or a populated Maven cache before treating a binary build as externally validated. See `RELEASE_VALIDATION.txt` for the exact local checks and intentionally skipped external gates.
