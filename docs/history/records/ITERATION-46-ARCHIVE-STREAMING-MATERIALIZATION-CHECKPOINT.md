# Iteration 46 — Bounded streaming materialization checkpoint

**Date:** 2026-09-12  
**Base:** Iteration 45  
**Implementation status:** COMPLETE  
**Validation status:** **DONE** — affected/static/security gates PASS; 13-module offline `test-compile` PASS after adding the separately supplied PDF offline dependency bundle; full 13-module offline `mvn test` PASS after one audit-only headless test-harness correction.

## Implemented

### Bounded application archive contract

`ArchiveReader.materializeEntry(...)` streams a selected archive member into a caller-owned target with an explicit byte ceiling and cooperative cancellation. The multi-format adapter writes to a sibling staging file and publishes only a completed member.

### One-resolution Reader path

Reader resolves the physical container without enumerating archive members. `BookResourceResolver.materializeArchiveBookEntry(...)` performs exact-first compatibility resolution once and passes the resolved actual member directly to the archive adapter. This removes the previous Reader-side `InputStream -> second temp file` copy and avoids resolving the same catalogue entry once for locate and again for read.

### Format coverage and safety

Direct materialization is implemented for ZIP-family, 7z, RAR/CBR and stream archive formats (TAR and compressed TAR variants, CPIO). Caller max bytes are capped by `ArchiveSafetyLimits.MAX_ENTRY_BYTES`; existing ZIP compression-ratio, entry-count and 7z memory ceilings remain active. Cancellation is checked before and between bounded reads.

Commons Compress requires Tukaani XZ for the exercised 7z/LZMA2 and `.tar.xz` paths. The supplied offline Maven repository contains `org.tukaani:xz:1.9`, so Iteration 46 declares that dependency explicitly through root dependency management and `myhomelib-infrastructure`.

### Temp lifecycle and observability

Adapter staging is removed unless successfully published. Reader-owned materialized books are removed on preparation failure, abandoned/cancelled handoff, switch, Back and dispose. Reader-open timing records resolve/materialize/parse/render-ready durations plus format and byte size, without book content.

## Freeze and gate correction

The source tree was frozen before the first test cycle. The first targeted run exposed one dependency defect before any production assertion failed: the new 7z fixture could not start because Tukaani XZ was not declared at runtime. The only post-freeze source correction was the explicit XZ dependency described above; the tree was frozen again before the repeated validation cycle.

No persistence/schema change, recursive nested-archive expansion, Maven/wrapper binaries, or weakening of the centralized archive safety ceilings was introduced.

## Final validation — 2026-09-12

### Affected automated tests

- `BookResourceResolverArchiveCompatibilityTest`: **6/6 PASS**.
- `ZipArchiveReaderTransparencyTest`: **10/10 PASS**.
- `DownloadPayloadValidatorTest`: **4/4 PASS**.
- `ZipImporterMultiEntryTest`: **4/4 PASS**.
- `ArchiveImportSupportTest`: **2/2 PASS**.
- Infrastructure affected total: **26/26 PASS**, Maven **BUILD SUCCESS**.
- `ReaderArchiveCompatibilityUiContractTest`: **3/3 PASS** in the original standalone contract run; the later full reactor also reaches and passes the UI module.

### Iteration/static/security gates

- `tools/iteration46-archive-streaming-check.py`: **11/11 PASS**.
- `tools/architecture-check.py`: **PASS**.
- `tools/implementation-completeness-check.py`: **PASS**.
- `tools/check-critical-ui-localization.py`: **PASS**.
- `tools/static_release_check.py`: **PASS**.
- `tools/supply-chain-policy-check.py`: **PASS**.
- `tools/xml-archive-security-check.py`: **PASS**.
- `tools/stage34-artifact-health-check.py`: **PASS**.
- `tools/iteration45-archive-efficiency-check.py`: **PASS**.
- `tools/v71-standalone-java-smoke.py`: **PASS**.

### Real uploaded samples

- `Возвращение_Великого_Я_великий_друид_которому_400_лет!_1_15.zip`: ZIP integrity **PASS**; one FB2 entry; incremental XML root is `FictionBook`.
- `Хранитель Древа Мира 1-8.fb2.zip`: ZIP integrity **PASS**; one FB2 entry; incremental XML root is `FictionBook`.
- `flibusta_online_fb2.inpx`: ZIP integrity **PASS**; contains `online.inp`, `collection.info`, `version.info`.

### Full reactor gates — PASS after dependency completion and audit correction

The separately supplied `MyHomeLib-Iteration40-PDF-offline-deps.zip` contains the artifacts that were missing from the first offline repository, including `org.apache.pdfbox:pdfbox:3.0.8`, `org.apache.pdfbox:jbig2-imageio:3.0.5`, `pdfbox-io` and `fontbox`. They were used only as an external local Maven repository input; dependency JARs remain excluded from the source tree/release ZIP.

1. Offline 13-module `test-compile`: **13/13 modules PASS, BUILD SUCCESS**.
2. Full offline `mvn test`: **13/13 modules PASS, BUILD SUCCESS**. Across Surefire reports: **807 tests, 0 failures, 0 errors, 10 skipped**.
3. Infrastructure alone: **375 tests, 0 failures, 0 errors, 7 skipped**.
4. E2E module: **10/10 PASS**.

The first full test audit exposed one test-harness issue, not a production PDF failure: on Linux with a stale/unreachable `DISPLAY=:0`, `ReaderViewToolbarLayoutTest` initialized AWT in the main Surefire JVM, leaving `GraphicsEnvironment$LocalGE` failed and causing the following `PdfDocumentSessionTest` to error. The display reachability probe now runs in an isolated child JVM; on an unreachable display the parent test JVM switches to headless mode and aborts only the JavaFX runtime layout test. The paired JavaFX/PDF regression then passed **8/8**, and the complete reactor passed.

This audit correction does not change production behavior or the Iteration 46 archive-streaming contract.

## Release rule

The Iteration 46 source release is built from a clean staging copy. `target/`, `.mvn/`, Maven binaries/wrappers, `.jar`, `.class`, `verification/`, `__pycache__/` and other generated build/cache payloads are excluded. Release verification must include ZIP CRC, path-safety, staging-vs-ZIP manifest equality and SHA-256.

## External evidence

MHL-010/011/012/017/018/019 remain **OPEN** pending real Windows/GitHub evidence. Iteration 46 does not close them using synthetic local evidence.
