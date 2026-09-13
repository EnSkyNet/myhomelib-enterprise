# Stage 17 Changelog — OPDS core

Date: 2026-08-25

## Implemented

- Added a dedicated `myhomelib-opds` Maven module. It depends only on the application layer and does not import JavaFX, JDBC/Spring persistence or infrastructure implementation classes.
- Added read-only application contracts for OPDS catalog queries and downloads.
- Added bounded SQLite OPDS query adapter with `LIMIT/OFFSET` pagination for authors, series, genres and book/search feeds; list page size is capped at 100.
- Implemented OPDS endpoints for root, authors, series, genres, search, a single book and download.
- Added `/health` endpoint for lifecycle checks.
- Implemented archive-aware downloads through the existing application materialization path.
- Downloads stream with `Files.copy(...)`; the server does not materialize the full book into a `byte[]`.
- Search feed pagination preserves the user query in generated next links.
- Added JUnit tests plus a dependency-free loopback HTTP validation harness.
- Updated root reactor/bootstrap composition, README, architecture documentation and architecture tests for the twelfth module.

## Architectural guarantees

- OPDS core does not depend on JavaFX.
- OPDS core does not know SQLite/JDBC/infrastructure implementation classes.
- UI does not depend on `myhomelib-opds`; bootstrap remains the composition root.
- HTTP catalog reads are bounded and do not call repository-wide `findAll()` paths.
