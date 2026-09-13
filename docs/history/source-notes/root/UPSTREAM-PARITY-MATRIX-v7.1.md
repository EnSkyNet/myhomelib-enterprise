# MyHomeLib Enterprise v7.1 — Upstream Parity Matrix

Status date: 2026-08-28  
Baseline: `myhomelib-enterprise-v7.zip`  
Behavioral reference: `MyHomeLib-2.7.0_pre2`  
Structural/reference dataset: `metabib-main` (GPLv3; no literal GPL code copied)

## Status legend

- `IMPLEMENTED` — code is present and the available local checks cover the mechanism.
- `PARTIAL` — foundation/code is present, but behavior, test coverage, performance evidence, or integration verification is incomplete.
- `MISSING` — required behavior is not implemented yet.
- `NOT APPLICABLE` — upstream behavior does not apply to the Java architecture.
- `INTENTIONALLY NOT PORTED` — behavior is deliberately rejected, with a documented replacement.

> Release rule: `IMPLEMENTED` below does **not** imply GitHub/Maven green. The environment cannot currently resolve `repo.maven.apache.org`; therefore full Java compilation/test execution remains unverified until `./mvnw clean verify -Pproduction` and GitHub Actions run successfully.

## P0

| Mechanism | Upstream/reference | v7 baseline | v7.1 current | Risk / remaining work | Tests / evidence | Status |
|---|---|---|---|---|---|---|
| ConnectionScript persistence | MyHomeLib collection properties / `collection.info` | Missing | `Collection.connectionScript`, metadata migration V4, repository/lifecycle/property DTO wiring | Run full migration/lifecycle regression | New code + static migration integrity | PARTIAL |
| ConnectionScript parser | `unit_Downloader.pas`: CHECK/REDIR/PAUSE/GET/POST/ADD | Missing | Strict declarative parser; unknown/malformed commands rejected; no shell/JS/Groovy/dynamic Java | Full Maven/JUnit suite still external | Standalone JDK smoke compiles/runs the production parser; unknown command rejected | IMPLEMENTED |
| GET / POST / ADD | `TDownloader.DoDownload`, `Query`, multipart params | Direct URL only | Declarative executor with cookies/session, Basic Auth, multipart POST and bounded GET retries | Full Maven/JUnit suite still external | Standalone JDK embedded HTTP run executes `ADD → POST → redirect → CHECK` and separate GET | IMPLEMENTED |
| CHECK | `CheckResponce`: rejects bad response and validates archives | Missing | Shared `DownloadPayloadValidator`: empty/HTML/text error, FB2 root, archive readability + expected entry; optional deep ZIP integrity | Full malformed/truncated corpus still belongs to Maven/JUnit | Standalone JDK smoke executes CHECK on FB2 and deep archive validation, including duplicate-entry rejection | IMPLEMENTED |
| REDIR / `%RESURL%` | `HTTPRedirect`, `CheckRedirect`, `StrReplace('%RESURL%',...)` | Missing | Redirect result retained from prior request; REDIR requires actual redirect; `%RESURL%` available to following command | Full Maven/JUnit suite still external | Standalone embedded HTTP run follows POST redirect, validates REDIR and final response URI; macro smoke covers `%RESURL%` | IMPLEMENTED |
| PAUSE | `Pause(milliseconds)` | Missing | 0..60,000 ms safety cap; cancellable sliced sleep; executor runs in application/background flow | JavaFX runtime smoke still external | Standalone JDK run executes PAUSE and verifies cancellation propagation | IMPLEMENTED |
| Book/collection macros | RTTI over string/integer `TBookRecord` fields + `%USER%/%PASS%/%URL%` | Missing | Required v7.1 aliases + upstream string/int fields; uppercase; path normalization; deterministic one-pass replacement | `InsideNo` has no exact Java model equivalent (documented as 0); run tests | `DownloadMacroResolverTest` authored | PARTIAL |
| Nested macro/code injection prevention | v7.1 safety requirement | Missing | Replacement values are never rescanned as macros; CR/LF/NUL rejected | Property/fuzz expansion remains optional hardening | Standalone JDK smoke verifies one-pass replacement; JUnit regression authored | IMPLEMENTED |
| `collection.info` codec | MyHomeLib: Name/file/type/Notes/URL/Script | Missing | Import/export codec preserves Name/file/type/Notes/URL and multiline Script | Legacy non-UTF8 collections remain a compatibility corpus item | Standalone JDK production-code round-trip preserves multiline script; export/import paths wired | IMPLEMENTED |
| `collection.info` trust policy | MyHomeLib manual update preserves local properties | Missing | Explicit `APPLY_SOURCE_PROPERTIES`, `PRESERVE_LOCAL_PROPERTIES`, `MERGE_SAFE_PROPERTIES` foundation; update path avoids credential/script overwrite | Expand regression matrix for manual/automatic update | Code review/static checks | PARTIAL |
| Online book download ordering | v7.1: validate → atomic move → persistence commit | Wrong order possible | Payload validation occurs before atomic replace; failed refresh keeps old local copy | Maven execution pending | Tests for success/failure refresh authored | PARTIAL |
| Force refresh | MyHomeLib “update downloaded book” semantics | Existing UI flag ineffective | `forceRefresh` propagated through coordinator/use case/adapter; existing file no longer short-circuits refresh | Runtime test pending | Regression test authored | PARTIAL |
| Partial/resume semantics | v7.1: resumable network `.part` retained | Cancellation deleted `.part` in affected path | Cancellation/interruption retains `.part`; sidecar stores only source hash + ETag/Last-Modified; resume uses `Range` + `If-Range`; unvalidated stale partial is restarted; `Content-Range` start is verified | Maven/runtime execution pending | Range/If-Range + stale-part + cancel tests authored | PARTIAL |
| Archive download deduplication | v7.1 requirement | Partial/uncertain | Concurrent requests coordinated by physical archive; per-book entry validated after shared download | Cross-process dedup is not required; stress test pending | Existing concurrent test | PARTIAL |
| INPX member filtering | Upstream importer explicitly iterates only `.inp` | Broad `.inp` selection | Auxiliary `version.info`, `collection.info`, `structure.info`, unknown files never enter book parser | Nested/odd-name corpus tests desirable | Compatibility test includes auxiliary files | IMPLEMENTED |
| `extra.inp` policy | `unit_ImportInpxThread.pas`: skip `extra.inp` when not online | Always treated as ordinary `.inp` | INPX reader has explicit `onlineCollection` policy; offline skips `extra.inp`, online includes it; count/read use same policy | Full/delta deletion behavior test through real DB still pending | New reader parity test + existing delta/full tracking tests | PARTIAL |
| Full/delta deletion safety | MyHomeLib online update semantics | Existing update tracker | Full snapshot marks missing; delta does not mark all missing; stable IDs remain | Real DB end-to-end tests required | Pipeline tests authored | PARTIAL |
| Search fingerprint/selective Lucene update | v7.1 P0 | Full rebuild pressure | V38 versioned searchable fingerprint state; unchanged searchable metadata can be skipped; delta uses change set | JVM/Lucene 700k full/selective measurements and Maven suite still required | Stage 24 contract + static guards; SQLite 100k/500k/700k/1M scale evidence is separate from Lucene | PARTIAL |
| Lucene N+1 / streaming | v7.1 P0 | OFFSET/count/page risk | Keyset streaming in bounded pages (400); author/genre enrichment batched; no `List<700000>` | Connected JVM/Lucene profiling still required | Stage 24/25C checks + SQL-scale probes verify bounded/query-plan contract | PARTIAL |
| Lucene atomic rebuild/cancellation | v7.1: old index remains until success | Atomic rebuild existed but no structured cancel callback | Cancellation-aware rebuild rolls back writer to previous committed index; progress emitted every 1k | Maven/runtime Lucene rollback test required | Implementation complete, tests pending | PARTIAL |
| Update progress telemetry | v7.1 `OperationProgress` | Fraction only | JavaFX-independent immutable telemetry with stages/counters; catalog HTTP bytes propagate as `bytesProcessed/bytesTotal`; import/index counters wired | Server may legitimately omit total bytes; full JavaFX runtime smoke remains external | Static release/build guards + byte-progress JUnit fixture | IMPLEMENTED |
| Full update progress UI | v7.1 stages/counters/throttle | Status-bar fraction only | Modal progress view with stages, FULL/DELTA, counters, byte progress, Cancel and ~180 ms coalescing | Packaged JavaFX runtime smoke remains external; unknown Content-Length is displayed as processed-only | Static FXML/controller/build guards | PARTIAL |
| Statistics false zero | Known v7 defect | Exceptions could collapse to empty/zero DTO | Error no longer masquerades as zero; Loading/Unavailable separated; background query | Run lifecycle regression against multiple collections | Static + existing tests | PARTIAL |
| Metabib dataset structural validation | `metabib.dataset/1` header/records/refs | Shallow schema check | Header/count/EOF/trailing/observation IDs/claim refs/artifact ranges/ignored/dummy foundations added | Continue field-by-field dataset corpus tests | Code review; Maven tests pending | PARTIAL |
| Ambiguous DB authors | metabib header metadata | Missing | Header metadata foundation parsed/used in resolution path | Need representative ambiguity corpus | Tests pending | PARTIAL |
| Credentials / logging | v7.1 P0 | AES-GCM existed; URL/error leakage possible | Shared sanitizer, no raw ConnectionScript network cause, query secret redaction, `%PASS%` RAM-only, plaintext proxy password rejected | Audit every diagnostic sink with automated secret canary | Secret regression tests authored | PARTIAL |
| TLS | v7.1: no trust-all | No central policy | Normal JVM validation by default; explicit JKS/PKCS12 trust store supported; password encrypted; no trust-all path; settings UI exposed | TLS integration against a real custom-CA HTTPS endpoint remains | Build guard forbids trust-all/X509TrustManager and verifies TrustManagerFactory; JUnit fixture authored | PARTIAL |
| Migration safety | V1–V36 immutable | V1–V36 | V37–V40 + metadata V4–V5 only; V1–V36 are SHA-256 immutable; representative existing-v7 DB upgrade preserves user state | Full Flyway/Maven runtime remains external | Direct SQLite migration/integrity + v7→v7.1 user-data upgrade + baseline hash gate | IMPLEMENTED |
| Real GitHub CI | Required Ubuntu/Windows/macOS | Workflow files exist | Not verified in this environment | Must run Actions and record commit/run IDs/results | Local static workflow checks only | MISSING |

## P1

| Mechanism | v7.1 current | Remaining work | Status |
|---|---|---|---|
| Persistent download queue | Metadata migration V5; statuses/retry/destination/archive/resume/error; startup `IN_PROGRESS→PENDING`; no credentials; `.part` sidecar stores source hash + ETag/Last-Modified and resume uses If-Range | Full Spring/Maven restart/retry integration UI flow remains | PARTIAL |
| Proxy | System/direct/HTTP proxy, encrypted proxy password and settings UI; SOCKS is explicitly rejected in the portable `java.net.http.HttpClient` profile and users can use system proxy | Live proxy integration test remains | PARTIAL |
| Provenance | Existing catalog source/book tracking + metabib structured metadata foundation | Full observation/claim/artifact normalized provenance model is incomplete | PARTIAL |
| Relations | V40 `book_source_relations`; metabib relation array is preserved and flattened to normalized rows without changing stable BookId | Rich diagnostics/source-replacement behavior and representative corpus tests remain | PARTIAL |
| Manifest compatibility | V39 adds manifest/importer/source-format/normalization/fingerprint/flags/features compatibility keys; importer persists/compares them and invalidates incompatible cache state | Connected large-source cache-reuse benchmark remains | IMPLEMENTED |
| Performance telemetry | `SearchIndexPerformanceReport` exposes DB read, document build, Lucene write, merge wait, commit, docs/s, peak heap, GC delta, index size and segments; SQLite scale probes recorded | JVM/Lucene figures still require connected Maven benchmark | PARTIAL |
| Extended metabib metadata | Structured raw/source metadata foundation avoids irreversible loss for part of the schema | Complete field-by-field `metabib.dataset_record/1` audit | PARTIAL |
| Archive integrity | Opt-in high-reliability download validation performs full ZIP entry read, CRC, uncompressed-size, case-insensitive duplicate-name, empty/invalid FB2 and expected-entry checks | Manifest-driven avoidance of repeat deep scans for separately managed local archives remains P1 hardening | PARTIAL |

## P2

| Mechanism | Decision | Status |
|---|---|---|
| Archive rollup | Foundation only; not required to block v7.1 | INTENTIONALLY NOT PORTED |
| Compilation detection | Fingerprint/occurrence foundation retained; no title+author-only dedup assumption added | INTENTIONALLY NOT PORTED |
| Advanced content deduplication | Versioned fingerprint foundation only | INTENTIONALLY NOT PORTED |
| Further artifact/occurrence normalization | Deferred to later major/minor work after provenance foundation is stable | INTENTIONALLY NOT PORTED |

## Current external release blockers and artifact status

1. `./mvnw clean verify -Pproduction` has not run because Maven Central cannot be resolved from the current environment.
2. GitHub Actions has not been executed; Ubuntu/Windows/macOS are therefore not green yet.
3. SQLite V1–V40 scale probes for 100k/500k/700k/1M are complete and PASS; the remaining performance blocker is the connected JVM/Lucene before/after benchmark.
4. Persistent queue/entity-safe resume is implemented; the remaining proof gap is full Spring/Maven restart/retry integration execution.
5. Metabib provenance/relations/extended metadata remain P1 partial.
6. Offline source-artifact packaging/re-extraction gate: **PASS**. `tools/package-v71-source.py` verifies source tree, clean copy and freshly extracted ZIP; the external `RELEASE-ARTIFACT-VALIDATION-v7.1.txt` and `.sha256` record the final artifact result.
