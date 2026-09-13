# Iteration 68 — Optional calibre CLI adapter (MHL-507)

Date: 2026-09-13

## Scope

MHL-507 adds an optional calibre `ebook-convert` provider on top of the MHL-506 conversion SPI. Calibre is never bundled and never required for normal startup. When no executable is available, all calibre converter beans report unavailable and the existing application/conversion paths continue normally.

## Delivered

- `CalibreCliBookConverter` with explicit provider id per target and curated conversion capabilities.
- Separate infrastructure beans for EPUB, MOBI, AZW3, PDF, FB2 and TXT targets.
- Optional executable discovery: `converter.calibre.executable` first, otherwise `PATH`, plus the standard Windows `Calibre2/ebook-convert.exe` location.
- No shell/template execution: `ProcessBuilder` receives executable, input path and output path as separate argv entries.
- Application-owned temporary source sandbox under `AppPaths.cacheDir()/calibre`; the converter writes only to the application-supplied staging target.
- Bounded child-output capture, bounded diagnostics on non-zero exit, clamped timeout and cooperative cancellation with forced child-process termination.
- Temporary source cleanup on success, failure and cancellation.
- Existing MHL-506 lifecycle remains authoritative for final validation, publication, hashing and `BookArtifact` registration.

## Validation evidence

- `CalibreCliBookConverterTest`: 7/7 PASS
- includes a real local fake `ebook-convert` executable launched through the production `ProcessBuilder` path
- `LayerArchitectureTest`: 14/14 PASS
- `tools/architecture-check.py`: PASS
- `tools/implementation-completeness-check.py`: PASS
- `tools/check-critical-ui-localization.py`: PASS
- `tools/static_release_check.py`: PASS
- `tools/supply-chain-policy-check.py`: PASS
- full 16-project offline `test-compile`: BUILD SUCCESS

## Environment boundary

The validation environment does not have a real calibre installation, so no claim is made about a specific calibre release/version. The production process-launch path is covered by a local executable contract test; real-release acceptance with an installed calibre build remains an environment-specific smoke rather than a bundled dependency.

Next planned item: **MHL-508 — Audiobook support v1**.
