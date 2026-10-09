# V27 local validation — 2026-10-01

- Full Maven reactor: `clean verify` under `xvfb-run`, offline repository, UTF-8 locale — **BUILD SUCCESS**.
- Reactor: **20/20 modules SUCCESS**.
- Infrastructure: **658 tests, 0 failures, 0 errors, 9 skipped**.
- Reader: **150 tests, 0 failures, 0 errors, 1 skipped**.
- UI: **356 tests, 0 failures, 0 errors**.
- E2E: **15 tests, 0 failures, 0 errors**.
- ArchUnit: **19 tests, 0 failures, 0 errors**.
- UI action inventory: **459/459 mapped**.
- UI action behavior: **458 automated + 1 justified exclusion = 459/459**.
- User-function mapping: **459/459 actions → 29 documented flows**.
- Help/localization/static-release/supply-chain/portable-user-data checks: **PASS**.

Environment notes:
- Linux JavaFX tests require headless DISPLAY (`xvfb-run`).
- Linux JavaFX 21.0.12 classifier artifacts were provided by the offline dependency bundle.
- V26 Windows/live/connected acceptance remains external and is not claimed as locally closed.
