# Stage 21 Validation

Date: 2026-08-25

## PASS

- `tools/stage21-help-genre-localization-check.py`:
  - central help-topic registry wiring;
  - Markdown-first help with compatibility fallbacks;
  - 63 Markdown help pages;
  - schema-v2 language diagnostics;
  - navigation/details genre localization by stable code;
  - Settings diagnostics UI;
  - synchronized shipped catalogues and 110 stable genre keys per `uk/en/bg`.
- `tools/validate-language-catalogs.py`: PASS — `uk/en/bg` schema 2, 200 UI keys and 110 genre keys each.
- `tools/static_release_check.py`: PASS — 38 XML/POM+FXML files, 25 FXML workspaces, 161 handler references, 33 SQLite migrations, 628 production Java sources.
- `tools/architecture-check.py`: PASS; UI debt ratchet remains at the accepted baseline (18 output-port users, 28 non-value domain-model users).
- `tools/large-library-pre-stage7-check.py`: PASS.
- All Stage 3–20 regression guards: PASS, including OPDS HTTP and Reader portable smoke checks.

## Environment limitation

The Maven/JUnit suite cannot be launched in this container because Maven is not installed (`mvn: command not found`) and the wrapper cannot rely on Maven Central/network access. The project includes the JUnit tests for a connected build; all available offline/static/runtime smoke checks were executed here.
