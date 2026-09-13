# Iteration 80 — Full Spring runtime wiring hardening

Date: 2026-09-13

## Trigger

Real Windows execution of the Iteration 79 candidate confirmed the previous `ContentIndexingQueueService` fix, then failed farther into startup at `OpdsAccessTokenService` with `No default constructor found`.

## Changes

- explicitly select the production `OpdsAccessTokenService(ApplicationSettingsPort)` constructor for Spring injection;
- add a focused Spring-context regression for `OpdsAccessTokenService`;
- add a full eager Spring Boot context startup smoke in `myhomelib-bootstrap`;
- make `SqliteContinueReadingRepository` and `SqliteBookQueryRepository` proxyable by removing `final`;
- add fail-closed Spring constructor-wiring and proxyability static guards;
- run both guards and the full context smoke in PR CI.
- fix the active Windows acceptance documentation to use the real `-Repo` PowerShell parameter and make readiness fail closed on command drift.

## Validation

- full Spring context: 1/1 PASS;
- OpdsAccessTokenService wiring + functional token tests: 5/5 PASS;
- affected SQLite repository tests: 6/6 PASS;
- Application: 286 tests, 0 failures, 0 errors, 1 skipped;
- Infrastructure: 432 tests, 0 failures, 0 errors, 7 skipped;
- Bootstrap: 18/18; OPDS: 20/20; Architecture: 14/14;
- exhaustive split baseline: 1,038 tests, 0 failures, 0 errors, 12 skipped;
- Spring constructor-wiring policy: PASS;
- Spring proxyability policy: PASS.

## External boundary

Production source changed, so prior candidate-bound external evidence is invalid for final acceptance. MHL-010/011/012/017/018/019 remain OPEN_EXTERNAL until rerun on the exact Iteration 80 candidate SHA.
