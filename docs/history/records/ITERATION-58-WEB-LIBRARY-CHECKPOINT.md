# Iteration 58 — Web Library v1 checkpoint

Date: 2026-09-12
Status: DONE locally
Backlog: MHL-408

## Delivered

- Added `myhomelib-web` as a dedicated outer rendering module depending only on Application APIs.
- Added responsive `/web/` catalogue, `/web/search`, `/web/books/{id}`, `/web/continue` and protected `/web/download/{id}` routes on the existing JDK HTTP/HTTPS sidecar.
- Web catalogue access is always authenticated; Bearer token auth from MHL-407 and configured Basic Auth are supported. Download additionally requires the `DOWNLOAD` scope.
- Non-loopback exposure inherits the existing HTTPS/TLS requirement.
- Catalogue/search/Continue Reading remain bounded through `OpdsBookQuery`/`OpdsPage`; the renderer test covers a logical 500,000-book catalogue while rendering only one 50-item page.
- HTML metadata is escaped and the page includes a responsive mobile viewport/layout.

## Evidence

- `WebLibraryRendererTest`: 3/3 PASS.
- `JdkOpdsServerTest`: 13/13 PASS, including mandatory auth and LAN HTTPS Web Library acceptance.
- Combined targeted MHL-408 acceptance: 16/16 PASS.
- Maven `LayerArchitectureTest`: 13/13 PASS.
- `tools/architecture-check.py`: PASS after adding the explicit `myhomelib-web` module/dependency boundary.
- `tools/implementation-completeness-check.py`: PASS.

A full global reactor is intentionally not claimed for Iteration 58; the latest recorded full-reactor baseline remains Iteration 55. MHL-409 and MHL-410 remain OPEN. External MHL-010/011/012/017/018/019 remain OPEN pending real Windows/GitHub evidence.
