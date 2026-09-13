# Stage 25A Validation — UI Orchestration Refactor

## Result

**PASS for all available offline/static/regression checks.**

## Stage 25A checks

- `tools/stage25a-ui-orchestration-check.py`: PASS.
- `MainController`: 647 lines (793 before this refactor).
- No production UI class other than `MainController` imports the concrete shell controller.
- `WorkspaceManager` owns observable Back/Forward availability state.
- `MainNavigationCoordinator` and `MainBookCommandCoordinator` contain the extracted orchestration paths.
- `WorkspaceManagerNavigationStateTest` regression fixture is present.

## Full regression sweep

PASS:

- every Stage 3 through Stage 25A guard;
- `tools/large-library-pre-stage7-check.py`;
- `tools/architecture-check.py` — dependency graph intact, UI debt ratchet 18/18 output-port users and 28/28 non-value domain users;
- `tools/static_release_check.py` — 38 XML, 25 FXML, 33 SQLite migrations, 636 production Java sources / 59 test sources;
- language catalogues `uk/en/bg` — schema v2, 200 UI keys and 110 genre keys each;
- OPDS loopback HTTP smoke and Reader portable smoke executed transitively by their stage guards.

## Maven/JUnit limitation in this container

`./mvnw` cannot download Maven 3.9.16 because DNS/network access to `repo.maven.apache.org` is unavailable (`curl: (6) Could not resolve host`). Therefore Maven/JUnit execution is **not** reported as PASS here. The Stage 23 release workflow remains the authoritative clean-build gate on GitHub-hosted runners.
