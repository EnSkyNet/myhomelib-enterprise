# Stage 8 — Unified Filter Engine — Validation

Дата: 2026-08-25

## PASS

- `tools/stage8-9-filter-table-check.py` — PASS.
- Усі 33 SQLite migrations V1..V33 застосовані до fresh in-memory SQLite — PASS.
- V33 upgrade/backfill from V32-era state — PASS (`FB2`, `abramov ivan`).
- V33 author edit trigger — PASS.
- 25 FXML files XML-parse — PASS.
- Pure Java compile/run harness для `BookFilterSpec` + `BookFilterSqlAdapter` — PASS.
- SQL `LIKE ... ESCAPE '\\'` literal `%/_` behavior — PASS.
- Changed/new Java syntax scan через `javac 21.0.11` — 35 files, 0 syntax-pattern failures.
- `tools/stage3-navigation-check.py` — PASS.
- `tools/stage4-navigation-check.py` — PASS.
- `tools/stage5-history-check.py` — PASS.
- `tools/stage6-online-update-check.py` — PASS.
- `tools/large-library-pre-stage7-check.py` — PASS після оновлення guard для aggregate Stage-8 navigation.
- `tools/stage7-online-updates-ui-check.py` — PASS.

## Maven limitation

Повний `mvn verify` у цьому runtime не виконаний: системного Maven немає, а Maven Wrapper намагається завантажити Maven 3.9.16 з `repo.maven.apache.org`; DNS/network access у runtime недоступний (`Could not resolve host`). Це обмеження середовища, а не прихований PASS. Повний CI/Maven verify потрібен у мережевому build environment.
