# MyHomeLib — Changes V24 → V25

Дата: 2026-09-29.

## Production / architecture

- Organizer: `MetadataExtractionLevel`, explicit recognized-format coverage, ODT/PDF/MOBI-family/comic extraction, recursive mixed-library acceptance, archive-entry UTF-16 boundary hardening.
- Backup/Restore: directory chooser routed through `FileChooserService`.
- Maven graph: explicit Application dependency for plugin samples and Shared dependency for Calibre plugin.
- Architecture guards/ArchUnit coverage expanded to all 16 production/runtime modules.

## Tests / release tooling

- added Organizer mixed-library acceptance tests;
- added Backup/Restore controller action/boundary tests;
- added architecture module coverage meta-test;
- added fail-closed 447-action UI coverage registry with body fingerprints;
- added fail-closed 447-action → documented-flow checker and integrated it into `build-check-v7.py`;
- current validation/readiness tokens updated to exact-current V25 evidence.

## Documentation cleanup

- root Markdown normalized to `README.md` + `ARCHITECTURE.md`;
- current descriptive docs classified in `docs/current/`;
- release evidence kept in `docs/release/`;
- old checkpoints, handoffs, deep-audits and descriptive artifacts moved under `docs/history/`;
- added canonical Ukrainian `docs/history/HISTORY-UA.md` and task-closure/architecture-audit documents;
- README, architecture, development, release, user-functions, functional audit and remaining-work docs updated to current state.

## Validation

Last successful full Maven clean reactor after production/test Java changes: **1842 tests / 0 failures / 0 errors / 17 skipped; 20/20 SUCCESS**. Final doc/Python-tool changes have targeted/static/architecture/build-check validation and do not alter Maven production/test sources.
