# MyHomeLib Enterprise — Architecture

**Architecture baseline:** Stage 1, 24.08.2026  
**Project version:** `1.0.0`  
**Java:** 21  
**Desktop UI:** JavaFX 21.0.2  
**Runtime container:** Spring Boot 3.5.x  
**Storage:** SQLite + Flyway  
**Search:** Apache Lucene 9.x  
**Reader rendering:** JavaFX Canvas (not WebView)

This document describes the code that exists in the repository now. It is an
architecture contract, not an aspirational design document. Rules that are
already enforceable are marked **hard rule**. Known deviations that still exist
are listed explicitly as architecture debt and are protected by a ratchet so
new violations cannot be added accidentally.

---

## 1. System shape

MyHomeLib Enterprise is a **desktop modular monolith** with separate MCP and OPDS
sidecar modules. The desktop application follows Ports & Adapters / Hexagonal ideas,
but is not yet a perfectly isolated hexagon.

```text
                                 desktop runtime

                         ┌────────────────────────┐
                         │ myhomelib-bootstrap    │
                         │ composition root       │
                         │ JavaFX + Spring Boot   │
                         └───────┬────────┬───────┘
                                 │        │
                                 ▼        ▼
                       ┌──────────────┐  ┌────────────────────┐
                       │ myhomelib-ui │  │ infrastructure      │
                       │ JavaFX       │  │ SQLite/Lucene/etc.  │
                       └──────┬───────┘  └──────────┬─────────┘
                              │                     │ implements
                              │                     ▼
                              │              application ports
                              ▼                     ▲
                     ┌──────────────────────────────┴─┐
                     │ myhomelib-application          │
                     │ use cases / DTO / ports/query  │
                     └──────────────┬─────────────────┘
                                    ▼
                         ┌─────────────────────┐
                         │ myhomelib-domain    │
                         │ model / events / VO │
                         └──────────┬──────────┘
                                    ▼
                         ┌─────────────────────┐
                         │ myhomelib-shared    │
                         │ small primitives    │
                         └─────────────────────┘

                     ┌─────────────────────────┐
                     │ myhomelib-reader        │
                     │ parser/layout/Canvas UI │
                     │ depends on shared only  │
                     └─────────────────────────┘
                              ▲
                              │ embedded by UI
                              │

sidecar runtimes             │
┌─────────────────────────┐  │   ┌─────────────────────────┐
│ myhomelib-mcp           │  │   │ myhomelib-opds          │
│ MCP + direct DB/archive │  │   │ JDK HTTP / OPDS feeds   │
│ depends on shared only  │  │   │ depends on application  │
└─────────────────────────┘  │   └─────────────────────────┘
```

The bootstrap module is the only desktop composition root. It is allowed to
know concrete infrastructure and UI classes because its job is wiring,
startup, shutdown and health monitoring.

---

## 2. Maven modules

Root `pom.xml` contains **12 modules**.

### Product/runtime modules

| Module | Responsibility |
|---|---|
| `myhomelib-shared` | Minimal cross-cutting primitives, archive safety limits, app paths, common events/exceptions |
| `myhomelib-domain` | Domain entities, value objects, reader preferences, saved searches, sync model, domain events |
| `myhomelib-application` | Use cases, queries, DTOs, mappers, application services and output ports |
| `myhomelib-infrastructure` | SQLite/Flyway, Lucene, cache, importers, exporters, filesystem/network adapters, settings |
| `myhomelib-reader` | Independent reading engine, format parsers, layout, caches and JavaFX Canvas renderer |
| `myhomelib-ui` | JavaFX controllers, workspaces, presenters, tables, dialogs, localization, reader integration |
| `myhomelib-bootstrap` | Desktop application composition root, Spring Boot/JavaFX lifecycle and health checks |
| `myhomelib-mcp` | Separate MCP executable/sidecar with direct SQLite/archive access |
| `myhomelib-opds` | Read-only OPDS HTTP delivery sidecar using application query/download APIs; no JavaFX or JDBC |

### Verification/tooling modules

| Module | Responsibility |
|---|---|
| `myhomelib-architecture-tests` | ArchUnit architecture boundary tests |
| `myhomelib-e2e-tests` | End-to-end test shell |
| `myhomelib-benchmark` | Import/performance benchmark code |

---

## 3. Direct production module dependencies

Stage 1 makes direct POM dependencies match direct source usage instead of
relying on accidental transitive dependencies.

```text
shared          -> -
domain          -> shared
application     -> shared, domain
reader          -> shared
infrastructure  -> shared, domain, application
ui              -> shared, domain, application, reader
bootstrap       -> shared, domain, application, infrastructure, ui, opds
mcp             -> shared
opds            -> application
```

The graph must stay acyclic.

### Why UI still depends on Domain

The long-term preference is for UI to consume application DTOs and input/use
case APIs. Today the UI still uses domain IDs/value objects and a bounded set of
non-value domain model types. Removing all those references would be a broad UI
refactor, not an architecture-baseline task.

Therefore Stage 1 does two things:

1. makes the dependency explicit in Maven so the build graph is honest;
2. freezes the current non-value-model usage with a debt ratchet so it cannot
   grow silently.

---

## 4. Hard architecture rules

The following rules are enforced both by ArchUnit where bytecode is available
and by `tools/architecture-check.py` at source/POM level.

### 4.1 Shared

**Hard rule:** `myhomelib-shared` must not depend on product modules or desktop
frameworks.

Allowed role: genuinely reusable low-level primitives only.

Do not place feature services, repositories, controllers or business entities
in `shared` simply to avoid a dependency decision.

### 4.2 Domain

**Hard rule:** Domain does not depend on:

- application;
- infrastructure;
- UI;
- reader;
- MCP;
- Spring;
- JavaFX;
- JDBC/SQL;
- Lucene.

Domain may depend on `shared` and the Java standard library.

### 4.3 Application

**Hard rule:** Application does not depend on concrete adapters/storage/UI:

- no infrastructure package;
- no UI package;
- no reader module;
- no MCP module;
- no JavaFX;
- no JDBC/`java.sql`/`javax.sql`;
- no Lucene.

Spring Core/transaction annotations are currently allowed because the existing
application services use them. This is framework coupling, but it is not a
storage-adapter coupling.

All classes in `application.port.out` must be interfaces.

### 4.4 Infrastructure

Infrastructure implements application output ports and owns technology-specific
code:

- SQLite/JDBC;
- Flyway;
- Lucene;
- Caffeine;
- network/filesystem adapters;
- archive importers;
- persistence mappings;
- export and synchronization adapters.

**Hard rule:** Infrastructure must not depend on UI, Reader or JavaFX.

### 4.5 UI

UI owns JavaFX presentation and interaction.

**Hard rule:** UI must not depend directly on:

- `myhomelib-infrastructure`;
- JDBC/SQL;
- Spring JDBC;
- Lucene.

Stage 1 removed the unused Maven dependency `UI -> Infrastructure`; there were
no Java source imports requiring it.

UI currently has two debt categories tracked by a ratchet:

- direct use of `application.port.out` from 18 baseline classes;
- use of non-value domain model types from 28 baseline classes.

A future refactor should move those interactions behind application input/use
case services. Until then, adding a new violating class fails the offline
architecture guard.

### 4.6 Reader

`myhomelib-reader` is intentionally independent from the library application.
It depends on `shared` only.

**Hard rule:** Reader does not depend on Domain, Application, Infrastructure,
UI, Spring, JDBC or Lucene.

The module currently contains both portable engine code and JavaFX rendering.
The portable packages are:

```text
reader.api
reader.core
reader.format
reader.layout
reader.model
reader.service
reader.render.api
```

**Hard rule:** those packages must not import JavaFX.

JavaFX is confined to:

```text
reader.render.javafx
```

This boundary makes a future physical split into `reader-core` and
`reader-javafx` possible without first untangling the engine.

### 4.7 MCP

`myhomelib-mcp` is a separate sidecar/runtime, not a desktop UI adapter.
Currently it deliberately uses SQLite/archive technologies directly and shares
only low-level primitives with the desktop code.

**Hard rule:** MCP must not depend on Domain, Application, Infrastructure, UI,
Reader, Spring or JavaFX.

If MCP later needs the same business rules as desktop, that should be an
intentional architecture change rather than importing desktop adapters ad hoc.

### 4.8 OPDS

`myhomelib-opds` is an HTTP delivery sidecar. It uses JDK `HttpServer` and depends only on the Application API. Catalogue SQL remains an Infrastructure adapter behind `OpdsCatalogQueryPort`; JavaFX lifecycle/settings UI talks only to `OpdsServerControl`. This keeps HTTP, SQL and desktop presentation independent.

**Hard rule:** OPDS must not depend on Infrastructure, UI, Reader, MCP, JavaFX, JDBC/SQL or Lucene. All list endpoints are bounded/paginated, and book downloads are streamed rather than loaded completely into memory. The default bind address is loopback.

### 4.9 Bootstrap

Bootstrap is the composition root. It may depend on UI, Infrastructure,
Application, Domain and Shared to wire the runtime.

Implementation-specific health checks should still prefer application ports
when possible. Stage 1 changed the search health probe from Lucene `Directory`
to the `SearchIndexer` application port, preventing Lucene from leaking into
bootstrap monitoring.

---

## 5. Reader architecture

The current reader is Canvas-based.

```text
BookSource
   │
   ▼
BookFormatRegistry
   │
   ├─ FB2/FBD parser
   ├─ EPUB parser
   ├─ TXT parser
   └─ ZIP wrapper
   │
   ▼
ReaderDocument / TextStorage / TOC
   │
   ▼
TextLayoutEngine
   │
   ▼
PageLayout / LineLayout / TextRunLayout
   │
   ▼
JavaFxReaderRenderer / ReaderCanvas
```

It does **not** use JavaFX WebView, HTML rendering or Jsoup as the primary
reader pipeline.

Important boundaries:

- parsing does not know JavaFX;
- layout uses `FontMetricsProvider` abstraction;
- JavaFX-specific font metrics live in the JavaFX render adapter;
- position/search/bookmark services do not know library persistence;
- `ReaderSettingsStateService` resolves global defaults vs per-book overrides while the reader engine remains persistence-agnostic;
- `TextLayoutEngine` receives document language and applies dictionary-aware visual hyphenation without changing source offsets;
- `ReaderPositionAutosaver` bounds unexpected-process position loss with periodic background persistence, while normal close performs a final flush;
- library-specific persistence is performed from UI/application adapters.

The reader module remains one Maven module for now, but its package boundary is
designed so it can later be split safely.

---

## 6. Library data and persistence

The production desktop database is SQLite. Flyway owns schema evolution.

Infrastructure persistence contains repositories/adapters for:

- books;
- authors;
- series;
- genres;
- groups;
- collections;
- bookmarks/progress/preferences;
- saved searches and related user data.

Rules:

1. schema changes require a new Flyway migration; do not edit an already
   released migration in place;
2. UI must never issue SQL;
3. application must never depend on JDBC;
4. infrastructure maps DB rows to domain/application types;
5. collection switching and resource lifecycle stay in infrastructure/bootstrap,
   not domain.

A PostgreSQL package exists in infrastructure but SQLite is the release storage
target unless a future stage explicitly promotes another backend.

---

## 7. Search

Search contracts live in application (`SearchIndexer`, `SearchQueryService`,
query/request/result types). Lucene lives in infrastructure.

```text
UI / Use Case
     │
     ▼
application search contract
     │
     ▼
LuceneSearchService (infrastructure)
     │
     ▼
Lucene index
```

Stage 1 removes the unused Lucene dependency from `myhomelib-application`.
This matches the architecture rule that application describes search intent,
while infrastructure owns the search engine.

---

## 8. Import and archive processing

Import orchestration is split between application use cases/contracts and
infrastructure format/archive adapters.

Current supported paths include FB2/FBD, EPUB, TXT, ZIP-family archives, 7z,
RAR and INPX catalogue import.

Archive safety limits belong in `shared` because they are also used by the MCP
sidecar. Concrete archive libraries remain in infrastructure/MCP, not domain or
application.

Nested archives are intentionally not recursively expanded by default.

---

## 9. Localization architecture

Localization is file-based and externally extensible.

```text
Lang/<code>.json                schema-versioned UI + genre catalogues
config/language.txt             selected language
config/available-languages.txt  generated discovered-language list
config/language-diagnostics.txt schema/key coverage diagnostics
help/<locale>/<topic>.md        bundled context help
```

At startup and when the language menu is opened, the UI localization service
rescans `Lang`. New valid language catalogues become available without Java or
FXML changes. Schema v2 adds a `genres` map keyed by stable FB2 genre code.
Legacy schema-v1 catalogues remain readable through fallback; catalogues that
require a newer schema are ignored safely and reported in diagnostics. Signing
is optional and is not a runtime requirement.

`HelpTopicRegistry` is the only workspace/dialog -> help-topic mapping. F1 asks
the registry for a topic and `HelpService` loads Markdown first, with TXT/HTML
and Ukrainian fallbacks for compatibility. Controllers do not embed help file
paths.

Localization remains a UI concern. Domain/database relations store stable codes
(language/genre IDs), never translated display labels.

See `LANGUAGE_SYSTEM.md` for catalogue details.

---

## 10. Startup and shutdown

`MyHomeLibApp` in bootstrap owns the desktop lifecycle:

1. start Spring context;
2. show JavaFX splash;
3. initialize active collection/database;
4. synchronize dictionaries/series;
5. initialize import/search components;
6. warm caches;
7. load `MainView.fxml`;
8. on shutdown, dispose workspace/reader and close executors, Lucene,
   collection resources and Spring context.

Bootstrap may reference concrete infrastructure components because it is the
composition root. Feature logic should still move into use cases/services when
it can be called independently of startup.

---

## 11. Threading

General rule:

- JavaFX scene graph operations -> JavaFX Application Thread;
- blocking filesystem/network/database/index work -> background executor;
- application/domain objects must not require the JavaFX thread;
- long import/index loops should provide bounded batches and cancellation where
  supported.

Reader JavaFX renderer is UI-thread-bound; parser/layout/storage components are
not JavaFX-bound by architecture.

---

## 12. Architecture verification

### Fast offline guard

Run from repository root:

```bash
python3 tools/architecture-check.py
```

It requires no external libraries and checks:

- exact direct internal Maven dependency graph;
- graph cycles;
- source references without direct POM dependencies;
- forbidden framework/layer references;
- Reader portable-package JavaFX isolation;
- Stage 1 dependency-cleanup invariants;
- UI architecture-debt ratchets.

This check is intended to run even before Maven can download dependencies.

### ArchUnit

When Maven dependencies are available:

```bash
./mvnw -pl myhomelib-architecture-tests -am test
```

ArchUnit checks the same hard package/layer boundaries against compiled
bytecode and checks top-level package cycles.

Both checks should pass before merging a feature stage.

---

## 13. Stage 1 dependency cleanup

The baseline removes dependencies that were inconsistent with actual source
usage or architecture:

- removed `myhomelib-ui -> myhomelib-infrastructure`;
- removed Lucene from `myhomelib-application`;
- removed unused Spring Modulith from application/root dependency management;
- removed unused Spring Boot/autoconfigure/configuration-processor declarations
  from application;
- removed unused `jakarta.annotation-api` from application;
- removed JavaFX from infrastructure;
- removed unused SLF4J dependency from domain;
- added explicit direct `shared/domain/application` dependencies where source
  code already used those modules through transitive dependencies.

No user-facing feature behavior is intentionally changed by this cleanup.

---

## 13.1 Navigation query boundary (Stage 2)

Desktop catalogue navigation now has one application-level query boundary:

```text
JavaFX NavigationPanelController
        |
        v
NavigationQueryService
        |
        +--> AuthorRepository
        +--> SeriesRepository
        +--> GenreRepository
        +--> BookQueryRepository
        +--> NavigationFacetRepository
                 |
                 v
          SQL GROUP BY facets
        |
        v
NavigationNodeDto
```

`NavigationMode` belongs to the application layer and currently defines
`AUTHORS`, `SERIES`, `GENRES`, `YEARS`, `LANGUAGES`, `ARCHIVES`, `KEYWORDS`,
`GROUPS`, `REVIEWS`, `ALREADY_READ`, `HISTORY` and `ALL_BOOKS`. The JavaFX controller only renders/filters
nodes and reports the selected `NavigationNodeDto`; it does not construct domain
entities or generate catalogue IDs.

Stage 3 adds `NavigationFacetRepository` for database-side aggregation of year,
language and physical archive facets. Stage 4 extends the same port with keyword,
group and rating/review facets. These modes never materialize the whole catalogue
merely to build navigation. Selection is converted back to a normal paginated
`BookQuery`; year/archive, exact keyword, group and rated/reviewed filters are
first-class query state. Archive history uses the application-level
`ArchiveNavigationKey`, while review subsets use stable `ReviewNavigationFilter`
identifiers. Stage 5 adds synthetic `ALREADY_READ` and `HISTORY` nodes.
`ALREADY_READ` reuses the existing `BookQuery.onlyRead` contract (`progress = 100`).
`HISTORY` uses a dedicated `reading_history` table, so clearing the user-visible
history does not delete `reading_progress`, bookmarks or read status. Reader opens
are recorded only after the book has opened successfully, and history workspaces
are ordered by `last_opened_at DESC`.

Series nodes use persisted `SeriesId` values from `SeriesRepository.findAll()`;
the previous UI behavior that generated random IDs during navigation loading
has been removed.

## 13.2 Online catalogue revision boundary (Stage 6)

Remote INPX updates carry a stable application-level source key based on the persisted
collection ID instead of the temporary cache filename returned by the HTTP downloader.
`InpxImportPipeline` fingerprints the source and each logical book; infrastructure
implements `CatalogUpdateTrackingPort` with `catalog_sources`, `catalog_book_state`,
`followed_authors` and `catalog_update_events` from Flyway V31.

The per-book catalogue representation is deliberately separate from physical local
storage. Remote INPX UPSERTs may refresh catalogue metadata but must preserve local
file coordinates for an already downloaded row together with rating, progress, review,
bookmarks and other user data. A successful download captures the current catalogue
revision/fingerprint as the downloaded baseline. This lets the data layer distinguish
`NEW_BY_FOLLOWED_AUTHOR` from `UPDATED_DOWNLOADED_BOOK` without treating a repeated
identical sync as a new update.

`CatalogUpdateService` is the application facade intended for the Stage 7 UI; JavaFX
should not consume the SQLite adapter directly. See
`docs/architecture/ONLINE_UPDATE_MODEL_STAGE6.md`.


## 13.3 Versioned user-data backup boundary (Stage 22)

Backup/restore is split at the application boundary. `BackupRestoreService` orchestrates `CollectionBackupPort` for collection lifecycle/snapshots and `UserDataTransferPort` for portable user state; JavaFX does not copy SQLite files or access JDBC. Infrastructure implements a WAL-safe database snapshot with SQLite `VACUUM INTO` and a streamed JSON manifest.

```text
Backup/Restore UI
      |
      v
BackupRestoreService
      |
      +--> CollectionBackupPort --> active CollectionManager / VACUUM INTO
      |
      +--> UserDataTransferPort --> user-data.json schema v2
                                  |
                                  +--> stable LibID-first remap
                                  +--> bounded identity cache
                                  +--> transaction for DB user state
                                  +--> atomic Reader/settings files
```

A full restore first copies the backup database to a sibling staging file while the live catalogue remains open, closes SQLite handles only for the final replacement, reopens the collection in `finally`, and then runs the normal sequential Flyway migration chain. Portable restore leaves catalogue metadata in place and applies ratings/progress/reviews, bookmarks, reading history/statistics, groups/favorites, saved searches, unified filters and Reader preferences by `LibID`. The old internal book ID is only a same-catalogue fallback. Manifest v1 is migrated sequentially to schema v2; future schema versions are rejected rather than guessed. Legacy database-only backups remain valid.


## 13.4 Cross-platform release boundary (Stage 23)

Release validation is separated from application runtime. GitHub Actions runs the same Maven reactor on Windows/Linux/macOS and only then invokes the platform JDK `jpackage` tool. Portable app-images contain their runtime and application dependencies; no dependency resolver is part of normal application startup. A dedicated `--release-smoke` branch executes before JavaFX launch and verifies packaged cross-module resources, allowing CI to validate native launchers on headless workers without introducing test-only dependencies into the UI or application layers. Tagged releases are assembled only after all matrix jobs succeed and are accompanied by SHA-256 checksums.

## 14. Known architecture debt

The baseline intentionally does not hide existing violations by pretending the
system is cleaner than it is.

### UI -> output ports

18 UI classes currently call application output ports directly. Adapters are
normally supposed to be consumed by application services/use cases, not by the
UI. This will be reduced incrementally when related features are refactored.

### UI -> non-value domain model

28 UI classes currently use domain entities/models directly. Domain IDs and
small value objects are acceptable at the current boundary; larger entities
should gradually be replaced with application DTO/view models.

### Bootstrap orchestration size

`MyHomeLibApp` currently performs substantial startup/index initialization and
explicit shutdown orchestration. It is allowed to know adapters as composition
root, but some orchestration can later move into lifecycle services.

### Reader physical module split

Reader portable and JavaFX code are package-separated but still packaged in one
Maven module. A physical module split is optional future work, not required for
current correctness.

See `docs/architecture/ARCHITECTURE_DEBT.md` for the ratchet baseline and
reduction policy.

---

## 15. Rules for future stages

When implementing new functionality:

1. prefer a new application use case/query over injecting a repository/output
   port directly into a JavaFX controller;
2. place technology-specific work in infrastructure;
3. do not add `UI -> infrastructure` even for a "small" convenience call;
4. do not add Lucene/JDBC/JavaFX to application/domain;
5. keep Reader engine packages JavaFX-free;
6. add direct Maven dependencies for modules referenced directly in source;
7. update Flyway for schema changes;
8. run the offline architecture guard before packaging;
9. run ArchUnit when Maven dependencies are available;
10. if a hard rule truly needs to change, update this document and tests in the
    same change rather than bypassing the rule.

This architecture baseline now covers navigation/history through Stage 5 and the online catalogue revision data boundary introduced in Stage 6.
