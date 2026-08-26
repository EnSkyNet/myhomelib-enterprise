# Stage 18 Validation

Date: 2026-08-25

## PASS

- `tools/stage17-18-opds-check.py` verifies lifecycle/settings/UI wiring, localhost default, Basic Auth, autostart and exposure warning.
- Real loopback HTTP Basic Auth smoke: unauthenticated catalog request returns 401 and authenticated request returns 200.
- `/health` remains available with authentication enabled.
- `@PostConstruct` / `@PreDestroy` lifecycle hooks are present.
- Main menu action is routed through `ActionRegistry`.
- UI has no direct dependency on `com.myhomelibcorp.opds` implementation classes.
- Architecture/static/language checks: PASS.
- Stage 3–16 regression guards and large-library guard: PASS.

## Environment limitation

A full Maven/JUnit/JavaFX packaged build is not available offline because Maven Wrapper cannot reach Maven Central. The real JDK HTTP loopback harness validates the server contract independently of Maven dependencies.
