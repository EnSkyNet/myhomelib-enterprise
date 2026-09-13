# Stage 1 — Architecture Baseline Changelog

Date: 24.08.2026

Scope of this stage: establish an accurate architecture contract, clean the
module dependency graph, add enforceable architecture guards and avoid changing
user-facing library/reader behavior.

## Completed

### 1. Architecture documentation rebuilt from the real codebase

- Replaced the stale WebView/Jsoup-era `ARCHITECTURE.md` with the current
  JavaFX Canvas reader architecture.
- Corrected module count to 11 Maven modules.
- Documented the 8 product/runtime modules and 3 verification/tool modules.
- Documented the exact direct production dependency graph.
- Documented Reader portable-vs-JavaFX package boundary.
- Documented MCP as a separate sidecar/runtime.
- Documented SQLite/Flyway, Lucene, localization and startup boundaries.
- Added `docs/architecture/DEPENDENCY_RULES.md` as a short reference.
- Added `docs/architecture/ARCHITECTURE_DEBT.md` with a ratchet baseline.

### 2. Maven dependency cleanup

Removed dependencies that were unused or contradicted architecture rules:

- `myhomelib-ui -> myhomelib-infrastructure`;
- Lucene from `myhomelib-application`;
- Spring Modulith from application and root dependency management;
- unused Spring Boot/autoconfigure/configuration-processor declarations from
  application;
- unused `jakarta.annotation-api` from application;
- JavaFX from infrastructure;
- unused SLF4J from domain.

Added explicit direct module dependencies where Java source already referenced
those modules through transitive dependencies:

- application -> shared;
- infrastructure -> domain;
- UI -> domain, shared;
- bootstrap -> application, domain, shared.

This makes Maven dependencies reflect actual source dependencies instead of
relying on transitive classpath leakage.

### 3. Bootstrap search-health decoupling

`LibraryHealthIndicator` no longer imports Lucene `Directory`. It now checks the
search subsystem through the application `SearchIndexer` port and exposes the
indexed document count as a health detail.

### 4. ArchUnit baseline rewritten

`LayerArchitectureTest` now tests rules that are true and enforceable today:

- Shared independence;
- Domain outer-layer/framework independence;
- Application isolation from adapters/JDBC/Lucene/JavaFX;
- application output ports are interfaces;
- Infrastructure isolation from UI/Reader/JavaFX;
- UI isolation from Infrastructure/JDBC/Lucene;
- Reader independence from the desktop application;
- Reader portable packages are JavaFX-free;
- MCP sidecar isolation;
- top-level product package cycle check.

The architecture-test module now includes MCP explicitly in its classpath so
MCP rules are actually scanned.

### 5. Offline architecture guard added

New file:

```text
tools/architecture-check.py
```

It requires only Python 3 and checks:

- exact internal production POM graph;
- graph cycles;
- source references without direct Maven dependencies;
- forbidden framework/layer dependencies;
- JavaFX leakage into portable Reader packages;
- dependency-cleanup invariants;
- architecture debt ratchets.

### 6. Architecture debt ratchet established

Baseline debt is deliberately visible rather than hidden:

- 19 UI classes directly use `application.port.out`;
- 29 UI classes use non-value domain model types.

The checker allows these sets to shrink but fails if a future stage introduces
a new violating class. This provides a stable baseline for Stage 2 and later UI
refactors.

### 7. Build-script permissions

Unix shell scripts, Maven Wrapper and Python tool scripts are marked executable
in the Stage 1 source tree.

## Intentionally not changed

- No navigation redesign (Stage 2).
- No DB schema changes.
- No Reader feature behavior changes.
- No localization behavior changes.
- No broad refactor of the 19/29 UI debt classes; Stage 1 freezes that debt so
  later feature work can reduce it safely.
