# MyHomeLib — Current validation state

Date: 2026-09-13

## Current local baseline

- **Iteration 83 is the current source checkpoint** — Windows TTS discovery fail-closed hardening + Annotation Manager FXML compatibility fix after further real-host testing.
- Local feature backlog: **73 MHL tasks DONE**; external acceptance gates remain separate.
- Authoritative exhaustive split regression on the Iteration 83 tree: **1,049 tests; 0 failures; 0 errors; 12 skipped** across all 15 child modules.
- Shared: **15/15**; Domain: **24/24**; Application: **287/0/0/1**; Plugin API: **28/28**; plugin samples: **2/2**.
- Infrastructure: **437 tests; 0 failures; 0 errors; 7 skipped**, including watcher/monitor classes run separately.
- Reader: **77/0/0/1**; UI: **98/98**; Web: **6/6**; OPDS: **20/20**; Bootstrap: **18/18**; MCP: **6/6**; Architecture: **14/14**; E2E: **14/14**; benchmark: **3 environment skips**.
- Selected `FB2_ZIP` export now resolves the selected format before later device-profile fallbacks and produces a real `.fb2.zip` archive; production converter regression: **1/1 PASS**.
- Windows TTS discovery now uses PowerShell `-EncodedCommand`, emits only `MHLVOICE|<utf8-base64>|<locale>` framed rows, keeps stderr separate, rejects non-zero discovery exit codes and ignores every unframed diagnostic line; TTS provider regression: **7/7 PASS**.
- Successful mass export now consumes the exported checkbox selection and asks the active workspace to re-read local/downloaded state; Author Workspace preserves the current author and restores the selected book when still visible. Cancelled/failed export keeps selection for retry.
- Global Annotations/Notes navigation still disposes an active Reader first; additionally, the Annotation Manager no longer encodes `CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN` as an FXML string. The resize callback is configured in Java, with a static FXML contract plus a real JavaFX `FXMLLoader` regression for display-capable environments.
- Full eager `SpringContextStartupSmokeTest`: **1/1 PASS**.
- `tools/spring-constructor-wiring-check.py`: **PASS** across 366 Spring stereotype classes.
- `tools/spring-proxyability-check.py`: **PASS** across 241 final production classes.
- Architecture / implementation completeness / localization / static release / supply-chain: **PASS**.
- External-acceptance readiness: **25/25 checks PASS**, including **9/9 evidence-policy regressions**; overall remains `READY_FOR_LIVE_EVIDENCE`. Offline evidence cannot close the six live gates.
- Clean-source offline `test-compile`: **16/16 Maven projects BUILD SUCCESS**.

## Why Iteration 83 supersedes Iteration 82 for Windows acceptance

Iteration 82 fixed the first observed Windows workflow/state defects, but the next real-host run exposed two deeper issues. The TTS chooser was not merely suffering mojibake: PowerShell parser diagnostics and fragments of the discovery script were reaching merged stdout/stderr and were then being interpreted as voice rows. Iteration 83 uses a PowerShell `-EncodedCommand`, exact `MHLVOICE|` framing, strict row parsing and fail-closed non-zero exit handling so diagnostic text cannot become a UI voice.

The same real-host run also proved that Annotations/Notes still failed after Reader cleanup because `annotation-manager-workspace.fxml` attempted to coerce the symbolic string `CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN` into a JavaFX `Callback`. Iteration 83 removes that FXML attribute and configures the callback directly in the controller.

Because production source changed, any Iteration 82 candidate-bound evidence is no longer valid for final acceptance. External GitHub and Windows evidence must target the exact Iteration 83 candidate SHA.

## Full-reactor claim boundary

A new single uninterrupted monolithic root `mvn test` is **not claimed**. The authoritative result is the exhaustive split baseline above, which covers every test class and totals **1,049 tests**. The full Spring-context startup smoke is separately **PASS**.

## Release identity

The Maven release identity in `pom.xml` remains **7.1.0**. The 8.0 roadmap/feature work does not automatically change the formal release identity. Decide the formal version **before freezing the external candidate SHA**; any later version/source/build-policy change creates a new candidate and invalidates candidate-bound evidence.

## External acceptance still OPEN

The following gates require live evidence and remain **OPEN_EXTERNAL**:

- **MHL-010** — GitHub PR CI required-check enforcement and Fast gate runtime evidence.
- **MHL-011** — real Windows DPI 100/125/150/200 acceptance with screenshot evidence.
- **MHL-012** — real standard-user Windows MSI/EXE/portable install/update/uninstall/desktop acceptance.
- **MHL-017** — live release CycloneDX JSON/XML SBOM evidence.
- **MHL-018** — live dependency vulnerability/SCA report under blocking policy.
- **MHL-019** — live exact-candidate CodeQL/SAST evidence with no blocking findings.
