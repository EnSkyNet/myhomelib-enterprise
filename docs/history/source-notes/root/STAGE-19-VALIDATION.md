# Stage 19 Validation

Date: 2026-08-25

## PASS

- `tools/stage19-20-reader-check.py`:
  - categorized settings dialog;
  - four built-in presets;
  - live preview + category resets;
  - global/per-book settings state service;
  - atomic per-book JSON persistence;
  - legacy preference-field migration;
  - configurable tap zones;
  - dedicated Reader status bar.
- Portable Java 21 compile/run smoke for `ReaderSettings`, presets and dictionary loader: PASS.
- `tools/architecture-check.py`: PASS; no new UI dependency debt.
- `tools/static_release_check.py`: PASS; 38 XML, 25 FXML, 33 SQLite migrations.
- Language catalogs uk/en/bg: PASS.
- Stage 3–18 regression guards + large-library guard: PASS.

## Environment limitation

A full `./mvnw clean verify` and packaged JavaFX runtime test cannot run in this offline environment because Maven Wrapper requires Maven Central access. The offline regression/compile checks above are additive safeguards, not a replacement for the connected release build.
