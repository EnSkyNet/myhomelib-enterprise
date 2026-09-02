# MyHomeLib Enterprise — Architecture

**Project version:** `7.1.0`  
**Architecture snapshot:** 31 August 2026  
**Java:** 21  
**Desktop UI:** JavaFX  
**Runtime container:** Spring Boot 3.5.x  
**Primary storage:** SQLite + Flyway  
**Search:** Apache Lucene 9.x  
**Reader rendering:** JavaFX Canvas

This file is the current architecture contract. Historical stage documents are not normative; they are consolidated under `docs/history/` and preserved verbatim under `docs/archive/source-notes/`.

## 1. System shape

MyHomeLib Enterprise is a modular desktop monolith with separate MCP and OPDS sidecar runtimes.

```text
                         myhomelib-bootstrap
                         composition root
                                |
              +-----------------+-----------------+
              |                                   |
              v                                   v
        myhomelib-ui                    myhomelib-infrastructure
        JavaFX presentation             SQLite/Lucene/network/files
              |                                   |
              +-----------------+-----------------+
                                v
                     myhomelib-application
                     use cases / DTO / ports
                                |
                                v
                       myhomelib-domain
                                |
                                v
                       myhomelib-shared

        myhomelib-reader                  myhomelib-opds
        reader engine + JavaFX            JDK HTTP / OPDS
        depends on shared                 depends on application

        myhomelib-mcp
        independent MCP sidecar
        depends on shared
```

`myhomelib-bootstrap` is the desktop composition root. It may know concrete UI and infrastructure classes because it owns startup, wiring and shutdown; feature logic should live below it.

## 2. Maven modules

The root reactor contains 12 modules.

| Module | Responsibility |
|---|---|
| `myhomelib-shared` | Small reusable primitives, archive limits, paths, security/utilities |
| `myhomelib-domain` | Domain entities, IDs/value objects, user/reader state models, events |
| `myhomelib-application` | Use cases, queries, DTOs, application services and ports |
| `myhomelib-infrastructure` | SQLite/Flyway, Lucene, import/export, filesystem/network/archive adapters |
| `myhomelib-reader` | Format parsing, document/layout engine, bounded caches and JavaFX Canvas renderer |
| `myhomelib-ui` | JavaFX controllers, workspaces, dialogs, localization and Reader integration |
| `myhomelib-bootstrap` | Desktop composition root and lifecycle |
| `myhomelib-mcp` | Separate MCP runtime with direct SQLite/archive technology |
| `myhomelib-opds` | Read-only OPDS delivery runtime through application contracts |
| `myhomelib-architecture-tests` | ArchUnit boundary tests |
| `myhomelib-e2e-tests` | End-to-end test module |
| `myhomelib-benchmark` | Import/search/Reader performance probes |

## 3. Direct production dependency graph

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

The graph must remain acyclic.

## 4. Hard layer rules

### Shared

`shared` must not depend on product modules or desktop/storage frameworks. It contains only genuinely reusable low-level code.

### Domain

Domain may depend on `shared` and the JDK. It must not depend on Application, Infrastructure, UI, Reader, MCP, Spring, JavaFX, JDBC/SQL or Lucene.

### Application

Application describes intent and boundaries. It must not depend on Infrastructure, UI, Reader, MCP, JavaFX, JDBC/SQL or Lucene. Output ports are interfaces. Existing Spring core/transaction annotations are tolerated as known framework coupling, not as adapter coupling.

### Infrastructure

Infrastructure owns technology-specific implementations: SQLite/JDBC, Flyway, Lucene, Caffeine, HTTP, filesystems, archives, import/export and synchronization adapters. It must not depend on UI, Reader or JavaFX.

### UI

UI owns JavaFX presentation and user interaction. It must not depend directly on Infrastructure, JDBC/SQL, Spring JDBC or Lucene. Existing direct UI use of some application output ports/domain model types is tracked as architecture debt and may only decrease, not grow silently.

### Reader

Reader depends on `shared` only. Portable Reader packages (`api`, `core`, `format`, `layout`, `model`, `service`, `render.api`) must stay JavaFX-free. JavaFX code is confined to `reader.render.javafx`.

### MCP

MCP is a separate sidecar and deliberately owns its direct SQLite/archive access. It must not import desktop Domain/Application/Infrastructure/UI/Reader/Spring/JavaFX layers.

### OPDS

OPDS depends on Application only. SQL lives behind an application port in Infrastructure; desktop lifecycle/settings use `OpdsServerControl`. OPDS list endpoints are bounded/paginated and downloads are streamed. Default bind is loopback.

## 5. Persistence and collection lifecycle

SQLite is the production storage target. Flyway owns schema evolution.

Rules:

1. released migrations are immutable; schema changes require a new migration;
2. UI never issues SQL;
3. Application never depends on JDBC;
4. Infrastructure maps database rows to domain/application types;
5. collection switch/open must validate the candidate before replacing the active datasource;
6. user-owned data and stable book identity must survive catalogue refreshes;
7. expensive catalogue-wide repairs/statistics/index work must not block JavaFX startup.

Collection metadata and per-collection catalogue databases are separate concerns. Online refreshes update catalogue metadata while preserving valid local file coordinates and user state.

## 6. Import and catalogue synchronization

Application exposes source-neutral catalogue/import contracts; Infrastructure implements format-specific readers and persistence.

Supported import paths include FB2/FBD, EPUB, TXT, ZIP-family, 7z, RAR, INPX and the neutral metabib dataset path. Large imports use streaming/bounded batches rather than building the entire catalogue in memory.

Full snapshots and delta updates have different semantics. Remote INPX/catalogue updates are validated before database/index mutation. Per-source fingerprints and per-book revision state support idempotent update classification.

Nested archives are intentionally not recursively expanded by default.

## 7. Online book download boundary

Online book download is application-driven and Infrastructure-executed:

```text
UI / BookDownloadCoordinator
        |
        v
DownloadBookUseCase
        |
        v
OnlineBookDownloadPort
        |
        v
HttpOnlineBookDownloadAdapter
        |
        +--> ConnectionScript parser/executor
        +--> shared HTTP/proxy/TLS policy
        +--> DownloadPayloadValidator
        +--> atomic file commit
        v
storage metadata persistence
```

`ConnectionScript` supports declarative `GET`, `POST`, `ADD`, `CHECK`, `REDIR` and `PAUSE`; it never executes dynamic code. Downloads use semantic validation and atomic commit. A valid server ZIP may rename the internal FB2 member: exact entry is preferred, then an unambiguous basename/LibID token match, then a single-FB2 fallback. The resolved real archive entry is persisted so Reader/open/cover lookup use actual ZIP contents. Ambiguous multi-FB2 archives are not guessed.

The persistent queue is credential-free. Resume is allowed only when source identity and HTTP validators make continuation safe.

## 8. Search and navigation

Search contracts live in Application; Lucene lives in Infrastructure. Full rebuilds and selective updates are designed to keep the previous committed index available until replacement succeeds.

Navigation is application-query based and includes Authors, Series, Genres, Years, Languages, Archives, Keywords, Groups, Reviews, Already Read, History and All Books. Facets are aggregated in the database and book lists remain paginated/bounded.

Reader-open history is separate from reading progress/bookmarks, so clearing history does not erase resume state.

## 9. Reader architecture

The integrated reader is Canvas-based, not WebView-based.

```text
BookSource
  -> BookFormatRegistry
  -> FB2/FBD | EPUB | TXT | ZIP parser
  -> ReaderDocument / TextStorage / TOC / resources
  -> TextLayoutEngine
  -> PageLayout
  -> JavaFxReaderRenderer / ReaderCanvas
```

Parsing/layout are persistence-agnostic and JavaFX-independent. JavaFX-specific font metrics/rendering stay in the render adapter. Reader position/settings/bookmarks are persisted by UI/application adapters. Position autosave keeps failed persistence dirty for retry and normal workspace close performs a final flush.

ZIP Reader support can merge multiple supported documents, while ordinary library book resolution remains entry-aware so a single catalogue book opens the intended member.

## 10. User data, backup and restore

Backup/restore is orchestrated at the application boundary. SQLite snapshots use `VACUUM INTO` so committed WAL content is captured consistently. Portable user-data backup uses stable `LibID` first and can restore ratings/progress/reviews, bookmarks, history/statistics, groups/favorites, saved searches, filters and Reader preferences to a re-imported catalogue.

A full restore stages the replacement before closing active handles, reopens the collection in `finally`, then continues through the normal Flyway migration path.

## 11. Localization and help

UI localization is external-file driven:

```text
Lang/<code>.json
config/language.txt
config/available-languages.txt
config/language-diagnostics.txt
```

Bundled defaults are Ukrainian, English and Bulgarian. Additional compatible catalogues (for example `ru.json`) are discovered dynamically without recompilation. Stable language/genre codes are stored in data; translated labels remain a UI concern.

Context help uses `HelpTopicRegistry` and bundled Markdown pages with legacy TXT/HTML fallback. Runtime help Markdown is not project-history documentation and remains in UI resources.

## 12. Startup, shutdown and threading

`MyHomeLibApp` owns startup/shutdown orchestration. Blocking database, network, file and index work runs outside the JavaFX Application Thread. Startup must not perform unnecessary catalogue-wide scans. Resource close order covers workspaces/Reader, executors, Lucene, collection resources and Spring context.

## 13. Architecture verification

Fast offline guard:

```bash
python3 tools/architecture-check.py
```

Compiled boundary tests when dependencies are available:

```bash
./mvnw -pl myhomelib-architecture-tests -am test
```

The source guard and ArchUnit enforce the dependency graph, forbidden layer/framework references, Reader JavaFX isolation and architecture-debt ratchets.

## 14. Tracked architecture debt

Current intentional debt is limited and guarded:

- some UI classes still consume application output ports directly;
- some UI classes still use larger domain model types rather than application view DTOs;
- bootstrap still contains substantial startup orchestration;
- Reader portable and JavaFX packages are separated by package rule, not yet by physical Maven modules.

Do not create a parallel framework merely to hide these items. Refactor debt only when the related feature is being changed, and tighten the ratchet when violations are removed.

## 15. Rule for future changes

Prefer application use cases/queries over direct adapter access, keep technology in Infrastructure, keep Reader core JavaFX-free, add Flyway migrations rather than editing history, preserve user data/local books during catalogue work, keep large operations bounded/cancellable, and update architecture tests together with any intentional boundary change.

## 16. 2026-09-02 stabilization baseline

The current architecture now treats collection-changing work as coordinated lifecycle operations. `LibraryOperationCoordinator` prevents incompatible import/update/index/backup/restore/VACUUM/switch/delete flows from overlapping, while `OperationCenterService` provides UI-visible runtime telemetry. Search index reads are gated while Lucene is dirty/rebuilding, statistics carry explicit stale state, and large interactive result sets use bounded paging rather than full materialization. Local file availability is distinct from remote catalogue tombstones (`missing_since`, Flyway V44), so a temporarily unavailable disk/NAS does not destroy book/user metadata.
