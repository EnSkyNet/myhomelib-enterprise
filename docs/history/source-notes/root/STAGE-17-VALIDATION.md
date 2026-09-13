# Stage 17 Validation

Date: 2026-08-25

## PASS

- `tools/stage17-18-opds-check.py`:
  - separate OPDS module and dependency boundaries;
  - bounded `LIMIT/OFFSET` catalog access;
  - root/authors/series/genres/search/book/download routes;
  - streaming download contract;
  - query-preserving pagination;
  - real loopback HTTP smoke test.
- Loopback HTTP smoke: OPDS root returns 200, bounded author feed works, search next link preserves `q`, missing download returns 404.
- `tools/architecture-check.py`: PASS; UI debt ratchet unchanged (18/18 output-port users, 28/28 non-value domain users).
- `tools/static_release_check.py`: PASS; 38 POM/FXML XML files, 25 FXML workspaces, 161 handler references, 33 SQLite migrations, 614 production Java sources.
- Language catalogs uk/en/bg: PASS.
- Stage 3–16 regression guards and the large-library guard: PASS.

## Environment limitation

A full `./mvnw clean verify` / packaged runtime test cannot be executed in this environment because Maven Wrapper requires external Maven Central access. Offline compile-oriented, static and real loopback HTTP checks are additive safeguards, not a substitute for the connected build before public distribution.
