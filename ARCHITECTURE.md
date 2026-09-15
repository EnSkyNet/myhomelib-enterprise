# MyHomeLib Enterprise — Architecture

**Project version:** `8.0.0`  
**Architecture snapshot:** 31 August 2026  
**Java:** 21  
**Desktop UI:** JavaFX  
**Runtime container:** Spring Boot 3.5.x  
**Primary storage:** SQLite + Flyway  
**Search:** Apache Lucene 9.x  
**Reader rendering:** JavaFX Canvas

This file is the current architecture contract. Historical stage documents are not normative; they are consolidated under `docs/history/` and preserved verbatim under `docs/history/source-notes/`.

## 1. System shape

MyHomeLib Enterprise is a modular desktop monolith with separate MCP and OPDS/Web sidecar runtimes.

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
                         ^             |
                         |             v
                myhomelib-plugin-api  myhomelib-domain
                stable extension SPI         |
                                             v
                                      myhomelib-shared

        myhomelib-reader                  myhomelib-opds
        reader engine + JavaFX            JDK HTTP / OPDS + Web routes
        depends on shared                 depends on application, shared, web

        myhomelib-web
        server-rendered browser UI
        depends on application

        myhomelib-mcp
        independent MCP sidecar
        depends on shared
```

`myhomelib-bootstrap` is the desktop composition root. It may know concrete UI and infrastructure classes because it owns startup, wiring and shutdown; feature logic should live below it.

## 2. Maven modules

The root reactor contains 15 modules.

| Module | Responsibility |
|---|---|
| `myhomelib-shared` | Small reusable primitives, archive limits, paths, security/utilities |
| `myhomelib-domain` | Domain entities, IDs/value objects, user/reader state models, events |
| `myhomelib-application` | Use cases, queries, DTOs, application services and ports |
| `myhomelib-plugin-api` | Versioned public plugin SPI, manifests, compatibility/security contracts and developer test harness |
| `myhomelib-plugin-samples` | Buildable SDK reference plugins; developer material, not wired into desktop runtime |
| `myhomelib-infrastructure` | SQLite/Flyway, Lucene, import/export, filesystem/network/archive adapters |
| `myhomelib-reader` | Format parsing, document/layout engine, bounded caches and JavaFX Canvas renderer |
| `myhomelib-ui` | JavaFX controllers, workspaces, dialogs, localization and Reader integration |
| `myhomelib-bootstrap` | Desktop composition root and lifecycle |
| `myhomelib-mcp` | Separate MCP runtime with direct SQLite/archive technology |
| `myhomelib-web` | Server-rendered Web Library / Web Reader presentation through application DTOs |
| `myhomelib-opds` | JDK HTTP sidecar serving OPDS plus authenticated Web routes through application contracts |
| `myhomelib-architecture-tests` | ArchUnit boundary tests |
| `myhomelib-e2e-tests` | End-to-end test module |
| `myhomelib-benchmark` | Import/search/Reader performance probes |

## 3. Direct production dependency graph

```text
shared          -> -
domain          -> shared
application     -> shared, domain
plugin-api      -> application
plugin-samples  -> plugin-api
reader          -> shared
infrastructure  -> shared, domain, application
ui              -> shared, domain, application, reader
bootstrap       -> shared, domain, application, infrastructure, ui, opds
mcp             -> shared
web             -> application
opds            -> shared, application, web
```

The graph must remain acyclic.

## 4. Hard layer rules

### Shared

`shared` must not depend on product modules or desktop/storage frameworks. It contains only genuinely reusable low-level code.

### Domain

Domain may depend on `shared` and the JDK. It must not depend on Application, Infrastructure, UI, Reader, MCP, Spring, JavaFX, JDBC/SQL or Lucene.

### Application

Application describes intent and boundaries. It must not depend on Infrastructure, UI, Reader, MCP, JavaFX, JDBC/SQL or Lucene. Output ports are interfaces. Existing Spring core/transaction annotations are tolerated as known framework coupling, not as adapter coupling.


### Plugin API

`plugin-api` is the public extension boundary for third-party providers. It may depend on stable Application contracts but must not depend on Infrastructure, UI, Reader, MCP, OPDS/Web, JavaFX, JDBC, Spring or Lucene. Plugin manifests declare an API compatibility range, the services provided, every intentional core-service override, and required network/filesystem capabilities; undeclared overrides and approval/manifest mismatches are rejected before activation. Trust is assigned by the host, never by plugin code. Untrusted plugins are not executed in-process. Trusted in-process plugins are not presented as an OS sandbox: host code must route calls through `PluginManager` when it needs disable/quarantine fault containment or capability-gated invocation. `PluginTestHarness` exposes the same structural checks to plugin CI without changing runtime trust state. `myhomelib-plugin-samples` depends only on the public plugin boundary and is intentionally excluded from desktop composition/runtime wiring.

### Device profiles

Device profile definitions, persistence and preferred-format ordering live in Application. They may describe a relative destination below a user-selected mount root but must not own OS-specific mount enumeration, raw USB access or UI dialogs. A saved profile must never resolve outside that root. `ExportToDeviceUseCase` is the orchestration boundary that combines device preferences with the existing artifact catalogue, converters, collision policy and crash-safe publication. Existing `BookArtifact` representations are preferred before conversion so device-aware export does not create unnecessary duplicate artifacts. Completion policy also remains an Application concern: `ExportCompletionService` may require a committed-file durability flush before success is reported, while host-specific physical eject remains outside the Java export contract. Directory-metadata force is best-effort for portability, and UI may only instruct the user to invoke the operating system safe-removal action; it must not model a successful file flush as equivalent to hardware eject.


### Infrastructure

Infrastructure owns technology-specific implementations: SQLite/JDBC, Flyway, Lucene, Caffeine, HTTP, filesystems, archives, import/export and synchronization adapters. It must not depend on UI, Reader or JavaFX.

### UI

UI owns JavaFX presentation and user interaction. It must not depend directly on Infrastructure, JDBC/SQL, Spring JDBC or Lucene. Existing direct UI use of some application output ports/domain model types is tracked as architecture debt and may only decrease, not grow silently.

### Reader

Reader depends on `shared` only. Portable Reader packages (`api`, `core`, `format`, `layout`, `model`, `service`, `render.api`) must stay JavaFX-free. JavaFX code is confined to `reader.render.javafx`.

### MCP

MCP is a separate sidecar and deliberately owns its direct SQLite/archive access. It must not import desktop Domain/Application/Infrastructure/UI/Reader/Spring/JavaFX layers.

### OPDS

OPDS depends on Application contracts plus low-level Shared utilities. SQL lives behind an application port in Infrastructure; desktop lifecycle/settings use `OpdsServerControl`. OPDS list endpoints are bounded/paginated and downloads are streamed. Default bind is loopback. Plain HTTP is restricted to loopback; non-loopback binds require JDK HTTPS/TLS. Managed self-signed certificates are generated inside the JVM and stored as PKCS12; imported PEM certificate/private-key material is converted to the same managed store. The sidecar applies bounded listen/request concurrency, per-client authentication throttling and an explicit health-endpoint exposure policy.

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

Programmatic JavaFX text on critical screens uses stable `ui.*` / `common.*` keys through `LocalizationService.text(...)` and `format(...)`. Legacy `tr(sourceText)` remains only for incremental FXML/source-text compatibility outside the critical migrated surfaces. `tools/check-critical-ui-localization.py` is the source-level ratchet: it rejects new user-facing Cyrillic literals and legacy `tr(...)` on the guarded Search/Reader/Import/OPDS/Backup code paths, verifies every referenced key in UK/EN/BG, and verifies `%`-format signatures across languages.

Context help uses `HelpTopicRegistry` and bundled Markdown pages with legacy TXT/HTML fallback. Runtime help Markdown is not project-history documentation and remains in UI resources.

## 12. Startup, shutdown and threading

`MyHomeLibApp` is the JavaFX composition entry point, but backend startup policy is owned by `StartupOrchestrator`. The explicit ordered pipeline is `RecoveryStartupTask -> MigrationStartupTask -> SearchStartupTask -> BackupStartupTask -> OPDSStartupTask`. Recovery and migration are `REQUIRED`; search, backup cleanup and OPDS autostart are `BEST_EFFORT`. Required failure aborts the remaining sequence with the failing task identity; best-effort failure is recorded as degraded startup and the sequence continues.

Blocking startup work is submitted through the managed application executor before the main JavaFX window is shown. Recovery runs before SQLite open; migration/collection activation does not hide a Lucene rebuild; search reuse/rebuild policy is a separate phase. Startup must not perform unnecessary catalogue-wide scans. Resource close order covers workspaces/Reader, executors, Lucene, collection resources and Spring context.

Backend asynchronous work uses bounded managed executor roles from `AsyncConfig`: `task`, `io`, `import`, and `search`; UI background work uses the bounded `UiBackgroundExecutor`. Production `CompletableFuture.supplyAsync/runAsync` calls must always provide an explicit executor. `CallerRunsPolicy` is forbidden because overload must never move blocking background work onto the FX/caller thread. Saturation is an explicit rejection with queue-depth telemetry; callers that return futures convert admission rejection into failed futures where practical. `FolderSyncService` is routed through the managed I/O executor and its returned future propagates cooperative cancellation.

`MemoryMonitor` owns only a daemon scheduler, validates its interval before state changes, supports `start -> stop -> start`, and closes through Spring/`AutoCloseable`. Shared application executors are stopped centrally by `AsyncConfig`; compatibility facades do not own duplicate pools.

## 13. Architecture verification

Fast offline guard:

```bash
python3 tools/architecture-check.py
```

Compiled boundary tests when dependencies are available:

```bash
mvn -pl myhomelib-architecture-tests -am test
```

The source guard and ArchUnit enforce the dependency graph, forbidden layer/framework references, Reader JavaFX isolation and architecture-debt ratchets.

## 14. Tracked architecture debt

Current intentional debt is limited and guarded:

- some UI classes still consume application output ports directly;
- some UI classes still use larger domain model types rather than application view DTOs;
- bootstrap remains the desktop composition root, but startup policy is decomposed into explicit task components;
- Reader portable and JavaFX packages are separated by package rule, not yet by physical Maven modules.

Do not create a parallel framework merely to hide these items. Refactor debt only when the related feature is being changed, and tighten the ratchet when violations are removed.

## 15. Rule for future changes

Prefer application use cases/queries over direct adapter access, keep technology in Infrastructure, keep Reader core JavaFX-free, add Flyway migrations rather than editing history, preserve user data/local books during catalogue work, keep large operations bounded/cancellable, and update architecture tests together with any intentional boundary change.

## 16. 2026-09-02 stabilization baseline

The current architecture now treats collection-changing work as coordinated lifecycle operations. `LibraryOperationCoordinator` prevents incompatible import/update/index/backup/restore/VACUUM/switch/delete flows from overlapping, while `OperationCenterService` provides UI-visible runtime telemetry. Search index reads are gated while Lucene is dirty/rebuilding, statistics carry explicit stale state, and large interactive result sets use bounded paging rather than full materialization. Local file availability is distinct from remote catalogue tombstones (`missing_since`, Flyway V44), so a temporarily unavailable disk/NAS does not destroy book/user metadata.

## 17. JavaFX view-instance lifecycle and async workspace loads

FXML controllers are view instances, not application singletons. `FxmlLoaderFactory` creates a fresh Spring-autowired controller for each FXML load with `AutowireCapableBeanFactory.createBean(...)`. Reloadable workspaces/dialogs that subscribe to long-lived application state implement `WorkspaceLifecycle`; `WorkspaceManager` or the owning window calls `dispose()` when the view is replaced/closed.

`UiSubscriptions` is the shared listener registry for long-lived JavaFX/application observables. Controllers register external listeners through it and close the registry during `dispose()`. Self-owned scene-node listeners do not require a second global registry because the node/controller graph becomes unreachable together.

Potentially blocking workspace loads use the bounded `UiBackgroundExecutor`. `BookWorkspaceController` and `GroupWorkspaceController` use `UiAsyncRequestGuard` generation + collection tokens so late completions cannot mutate a newer view or a view for a different active collection. Pending book loads are cancellable; group-list loads expose explicit loading/empty/error states and preserve a requested group across asynchronous population.

## Iteration 11 — catalog edit consistency

Classic metadata editing crosses the UI/application boundary through `EditBookUseCase`. UI code must not issue `BookCommandRepository` writes or Lucene commits directly. Authoritative book mutations use `CommittedCatalogMutationService`; derived search state is synchronized after commit by `SearchIndexSynchronizer`, leaving the index dirty when synchronization cannot be completed. `Book` relationship collections are immutable to callers and may be populated only through aggregate methods during reconstruction.\n\n## Iteration 12 — diagnostics privacy and external-reader materialization\n\nSupport-bundle generation is a privacy boundary. `SupportBundleService` never copies logs or release text directly into the archive: text entries pass through `SupportBundleSanitizer`, known secrets are replaced, user/home/application paths are pseudonymized, and absolute settings paths are redacted as whole values. `environment.txt` exposes stable aliases instead of physical `dataDir`/`launchDir`, while the application version is resolved from packaged build metadata. The UI presents a preview/options dialog before export so optional logs, thread dump and release documents are explicit user choices.\n\nExternal-reader temporary books are owned by `ExternalReaderMaterializationCache` in the application layer. Materialized files use opaque names, per-file/total-size and age bounds, startup crash cleanup and active lease protection. A detached `Process` retains its lease until `onExit`; `Desktop.open` has no reliable process handle, so its lease is deliberately preserved for the remainder of the session and removed on the next startup rather than risking premature deletion while another process is reading the file. Application shutdown does not blindly delete active external-reader files.\n


## Iteration 15 — startup orchestration

Desktop startup is modeled as testable tasks implementing `StartupTask`. `StartupCollectionResolver` resolves the target collection once and `StartupContext` carries the active collection plus reusable-search state between phases. `RecoveryStartupTask` invokes filesystem/crash recovery before any SQLite open. `MigrationStartupTask` activates/migrates the collection without forcing a search rebuild and closes a partially opened collection if a post-switch startup component fails. `SearchStartupTask` independently reuses a valid index or schedules a managed background rebuild. `BackupStartupTask` removes only interrupted `.snapshot.tmp` staging files. `OPDSStartupTask` applies optional autostart as a best-effort phase.

`StartupOrchestrator` is the single source of task order and failure policy. Do not move migration, search rebuild, backup cleanup or OPDS autostart back into `MyHomeLibApp`.

## Annotation boundary (Iteration 35)

Annotations are domain user data, not Reader-canvas state. `myhomelib-domain` owns the immutable `Annotation` / `AnnotationAnchor` model and pure relocation rules. `myhomelib-application` owns lifecycle orchestration through `AnnotationRepository`. SQLite/Flyway and portable backup live in infrastructure. Reader/UI integrations added by later iterations must call the application boundary and must not import infrastructure annotation persistence classes.

An artifact-bound anchor may reuse exact offsets only for that same artifact. Cross-artifact migration is an explicit re-anchor operation; no renderer or restore path may silently apply offsets from one representation to another.

## Iteration 36 — Reader annotation interaction boundary

MHL-203 consumes the Iteration 35 annotation foundation without moving persistence into the Reader module. `myhomelib-reader` exposes only renderer-neutral `ReaderSelection` and `ReaderAnnotationOverlay` values. `ReaderSelectionController` owns source-text offsets, hit-testing, selection handles and keyboard extension; `ReaderCanvas` owns transient JavaFX interaction/rendering and delegates Highlight/Note requests outward.

`NewReaderWorkspaceController` is the integration boundary: it converts `ReaderSelection` to `AnnotationAnchor`, invokes `AnnotationService` asynchronously, and resolves persisted anchors to Reader overlays after a book is opened. Reader never imports annotation domain/repository/SQLite types. Overlay ranges are source-text offsets, so font, margin, toolbar and pagination changes only trigger a redraw and do not mutate stored anchors. Artifact-bound anchors are rendered only for the exact opened artifact; ambiguous/foreign artifact bindings are not guessed.

## Annotation Manager boundary (Iteration 37)

MHL-204 keeps global annotation browsing separate from renderer and persistence details. `myhomelib-application` owns `AnnotationManagerFilter`, bounded pages/facets, edit/delete/undo orchestration and the `AnnotationManagerQueryPort`. Infrastructure implements that port with parameterized SQLite queries and bounded pagination. `myhomelib-ui` consumes only those application contracts; the annotation-manager package must not import annotation-domain entities, repositories or SQLite adapters.

Jump-to-location crosses the existing workspace/Reader boundary using only book id + annotation id. Reader reloads its application annotation projections, resolves the anchor against the actually opened artifact, performs a one-shot source-offset jump, and clears the target. This preserves the MHL-203 rule that layout/reflow state is renderer-local while persisted anchors remain application/domain user data.

## Rich annotation export boundary (Iteration 38 / MHL-205)

The export serializer is an application use case. UI may construct `AnnotationExportRequest` and invoke `AnnotationExportService`, but it must not query SQLite/JDBC or annotation-domain repositories to build Markdown/JSON/HTML. Storage-specific selection, joins and deterministic bounded paging implement `AnnotationExportQueryPort` in Infrastructure. Export serialization is UTF-8 and page-streamed; publication occurs only after a complete temporary artifact exists. This keeps templates and external interchange semantics out of persistence while keeping database knowledge out of UI.

## PDF Reader renderer boundary (Iteration 38 / MHL-206)

PDF is a renderer adapter inside `myhomelib-reader`, parallel to the existing text Reader rendering path. PDFBox must not be imported by `myhomelib-ui`, Application or Domain. `PdfDocumentSession` encapsulates the native document lifecycle, page rasterization and bounded cache. `PdfReaderView` may depend on JavaFX and the PDF session adapter, but heavy parsing/rasterization executes on its background renderer executor. `NewReaderWorkspaceController` chooses the adapter by Reader-supported format and continues to own workspace lifecycle, saved position, session history and cancellation. A generation/session guard is mandatory for every background raster result so a stale page cannot mutate the current workspace after switch/close.

MHL-207 extends that boundary without exposing PDFBox outside Reader. `PdfDocumentSession` resolves bounded outline entries and performs cancellable text-layer search; `PdfReaderView` exposes only `PdfOutlineEntry`, `PdfSearchOutcome` and page navigation callbacks to UI. Page bookmarks reuse the existing Reader persistence path. The application module owns `PdfAnnotationSelectionData`, whose offsets are explicitly page-local so future PDF text selection can integrate with the existing annotation lifecycle without inventing global offsets or creating parallel persistence. Image-only PDFs do not trigger OCR implicitly.

## Iteration 45 — archive traversal boundary

Book resource resolution depends on the application `ArchiveReader` output port, not on the concrete multi-format archive adapter. Archive entry compatibility resolution uses one bounded name enumeration per resolver operation; exact normalized logical-path matches are preferred before legacy unique-token/single-FB2 fallback. `findFirstEntry` is a direct format-specific traversal: sequential archives must not be expanded into a name list and then reopened merely to select the first matching member. Existing archive count/size/compression/memory limits and owner/temp cleanup remain mandatory.

### Iteration 46 — bounded Reader archive materialization

Reader archive-member opening uses a two-step application boundary: `locateBookContainer` resolves only the physical container without enumerating members, then `materializeArchiveBookEntry` performs exactly one compatibility-name resolution and delegates bounded streaming publication to `ArchiveReader.materializeEntry`. The archive adapter writes into a sibling staging file, enforces the caller byte ceiling together with centralized archive safety limits, observes thread/supplier cancellation between bounded reads, and only publishes the target after the member is complete. ZIP, 7z, RAR/CBR and sequential tar/compressed-tar/CPIO paths therefore avoid full-entry `byte[]` materialization and avoid the Reader-side copy of an adapter-owned temporary stream. Reader owns the published temporary book and deletes it on failed preparation, stale/cancelled handoff, book switch, Back and workspace disposal. Reader-open observability records only phase timings, format and byte size (`resolve -> materialize -> parse -> render-ready`); book contents are never included in that timing event.

## Iteration 47 — Comic Reader boundary

CBZ/CBR are native `BOOK` formats in the shared format registry, not generic multi-book archive imports. `ComicArchiveImporter` creates one catalogue `Book` for the physical comic container; the legacy ZIP/RAR importers no longer claim `.cbz`/`.cbr`, so contained images cannot become accidental catalogue books. Physical archive access still uses the existing `BookResourcePort`/`ArchiveReader` boundary.

`myhomelib-reader` owns `ComicDocumentSession`, `ComicPageSource`, page-position mapping and `ComicReaderView`. Session open enumerates only safe supported image names, applies deterministic natural ordering, and does not decode image bytes until a page is requested. Per-page compressed bytes, source raster pixels and the decoded LRU cache are explicitly bounded; ImageIO source subsampling is used for viewport/thumbnails. JavaFX rendering runs on the Reader renderer executor and stale results are generation/session guarded. UI adapts `BookResourcePort.listArchiveEntries/readArchiveEntry` to `ComicPageSource`, while workspace lifecycle, saved position, bookmarks, history and Back/dispose remain in `NewReaderWorkspaceController`. No archive implementation type leaks into Reader or UI.

Comic layout state (fit page/width, continuous scroll, dual-page and RTL manga ordering) is renderer-local. Persisted progress/bookmarks reuse `ReaderPosition` as a page index and therefore remain independent from zoom/layout changes. Text-only annotations, TOC and search are not applied to image comics.


## Iteration 50 — persistent content-index queue and throttling boundary

`ContentIndexingQueueService` is application-owned orchestration. It depends only on three output ports: durable checkpoint storage, per-task processing and power state. The infrastructure layer implements file checkpoint persistence, bounded/rate-limited extraction-to-content-index processing and cached system power detection. Queue control methods remain non-blocking; work executes on daemon worker/coordinator/checkpoint executors.

Restart durability is collection-scoped. Active work remains in the outstanding snapshot and is re-queued after process restart. Shutdown first stops dispatch, drains already-enqueued checkpoint writes, writes one final synchronous snapshot, and only then interrupts workers. This ordering prevents an older async checkpoint from racing after the final save. Eco/Balanced/Fast resource policy belongs to application configuration; platform power detection and byte-rate enforcement remain infrastructure concerns.

## Iterations 52–54 — sync transport boundary

Sync records and ChangeSets are domain/application data contracts. They carry stable sync IDs, entity type, schema version, `baseVersion/version`, UTC timestamps, source device identity and tombstones. Transport adapters must never synchronize a live collection SQLite file.

Local-folder and WebDAV adapters live in infrastructure and exchange immutable ChangeSet bundles. Publication is staged and atomic where the backend permits it. WebDAV protects payloads with authenticated AES-256-GCM; credentials and shared key material are retrieved only through `SecretStore`. Non-loopback WebDAV requires HTTPS. PROPFIND parsing goes through the hardened XML boundary, and remote href resolution is same-origin/path-bounded before any credential-bearing follow-up request.

Conflict-resolution semantics and key-rotation/migration are application/security policies tracked independently by MHL-404/MHL-405; transport success must not silently imply those policies are complete.

## Iteration 69 — audiobook Reader boundary (MHL-508)

Audiobook playback is a Reader concern, while book identity, artifacts and synchronized user state remain outside the Reader module. `myhomelib-reader` owns `AudioDocumentSession`, the renderer-facing `AudioReaderView` and the `AudioPlaybackBackend` abstraction. The concrete FFmpeg-backed implementation launches `ffprobe`/`ffplay` with argv-only `ProcessBuilder`; it never builds a shell command, bounds retained diagnostics, drains child output to avoid pipe deadlocks, enforces probe timeout and owns child-process termination.

The shared format registry declares MP3/M4B as importable Reader formats. Generic import remains an infrastructure adapter. Explicit multi-file grouping lives in the Application layer (`AudiobookTrackGrouping`) because artifact selection depends on the domain aggregate; UI must not add new direct domain-model helper debt. Untagged alternate audio artifacts remain separate representations.

Desktop audiobook progress reuses the existing reading-progress/application persistence boundary. The stable opaque anchor is `audio:<millis>:<track>:<chapter>`; sync transports/projectors preserve it without interpreting audio semantics. Audio bookmarks use the same exact anchor. No Reader code imports SQLite/sync infrastructure and no new database migration is required.



## Iteration 70 — knowledge Markdown integration boundary (MHL-509)

Obsidian/Joplin export is an Application-layer integration over the existing `AnnotationExportQueryPort`; UI only gathers scope/template/policy choices and never queries SQLite or annotation-domain repositories. `KnowledgeMarkdownExportService` consumes the same stable, bounded, per-book-contiguous projection as MHL-205 and writes one UTF-8 Markdown document per logical book through sibling staging + atomic publication.

Path templates are treated as untrusted user configuration: placeholders are allow-listed, path segments are sanitized for cross-platform filesystem rules, traversal cannot escape the chosen root, and deterministic file names are used instead of automatic ` (1)` collision suffixes. The default `REPLACE_MANAGED` policy overwrites only a target containing the exact MyHomeLib ownership marker for the same book id. `SKIP_EXISTING` and `FAIL_IF_EXISTS` are explicit alternatives. Optional YAML frontmatter remains standard Markdown metadata; backlinks use the stable `myhomelib://book/<bookId>?annotation=<annotationId>` URI without embedding secrets or database identifiers beyond existing stable ids.
