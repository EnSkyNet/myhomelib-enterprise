# Iteration 67 — BookConverter SPI / conversion capabilities (MHL-506)

Date: 2026-09-13

## Scope

MHL-506 turns the existing export-oriented `BookConverter` hook into a provider-neutral conversion contract without introducing a second Send to Device pipeline. Application owns conversion job selection, limits, cancellation, staging, publication and catalogue registration; providers only transform one source stream into the supplied staged target.

## Delivered

- `BookConversionCapability`: normalized source/target capability edges with explicit target extensions.
- `BookConversionContext`: job-aware provider context carrying book/source/target, cancellation and output limit.
- Backward-compatible `BookConverter`: existing adapters retain the legacy `convert(Book, InputStream, Path)` method while new providers may expose capabilities and job-aware conversion.
- `ConvertBookUseCase`: preferred/alternate source-artifact selection, provider selection, bounded cancellable input, output-size guard, application-owned staging, atomic publication where supported, SHA-256/content fingerprint and `BookArtifact` registration.
- Artifact persistence is exposed through the application mutation boundary and implemented transactionally by SQLite.
- Failure/cancellation/limit/registration-error cleanup removes staging and any unregistered final output.
- External-command conversion observes cancellation by terminating the launched process.
- Existing FB2/FB2.ZIP/EPUB/TXT providers publish conversion capabilities.
- Send to Device consumes the same conversion capability matrix and continues to prefer an already compatible registered artifact before conversion.

## Validation evidence

- final MHL-506/device targeted regression: 20 tests, 0 failures/errors/skips
- full `myhomelib-application` suite: 265 tests, 0 failures/errors, 1 skipped
- affected SQLite artifact persistence: 2/2 PASS
- `LayerArchitectureTest`: 14/14 PASS
- full 16-project offline `test-compile`: BUILD SUCCESS
- clean-source `tools/architecture-check.py`: PASS
- clean-source `tools/implementation-completeness-check.py`: PASS
- clean-source `tools/check-critical-ui-localization.py`: PASS
- clean-source `tools/static_release_check.py`: PASS
- clean-source `tools/supply-chain-policy-check.py`: PASS
- clean-source `tools/build-check-v7.py`: PASS

## Evidence boundary

The full monolithic `myhomelib-infrastructure` test suite is **not** claimed as Iteration 67 evidence. The available execution window ended after Application completed and Infrastructure compilation began. The affected SQLite artifact persistence suite completed successfully and is the persistence-specific acceptance evidence for MHL-506.

Next planned item: **MHL-507 — optional calibre CLI adapter**.
