# MyHomeLib 8.0.0 — Iteration 85 final validation report

**Date:** 2026-09-15  
**Candidate:** MyHomeLib 8.0.0 / Iteration 85  
**Local status:** **PASS**  
**Environment:** Linux, OpenJDK 21.0.11, Maven 3.9.6, `C.UTF-8`, offline Maven repository.

## 1. Full Maven verification

Final offline `mvn clean verify` completed with `BUILD SUCCESS` across the 16-project reactor.

| Module | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| myhomelib-shared | 22 | 0 | 0 | 0 |
| myhomelib-domain | 24 | 0 | 0 | 0 |
| myhomelib-application | 299 | 0 | 0 | 1 |
| myhomelib-plugin-api | 28 | 0 | 0 | 0 |
| myhomelib-plugin-samples | 2 | 0 | 0 | 0 |
| myhomelib-infrastructure | 448 | 0 | 0 | 7 |
| myhomelib-reader | 83 | 0 | 0 | 1 |
| myhomelib-ui | 108 | 0 | 0 | 0 |
| myhomelib-web | 6 | 0 | 0 | 0 |
| myhomelib-opds | 20 | 0 | 0 | 0 |
| myhomelib-bootstrap | 18 | 0 | 0 | 0 |
| myhomelib-mcp | 7 | 0 | 0 | 0 |
| myhomelib-architecture-tests | 14 | 0 | 0 | 0 |
| myhomelib-e2e-tests | 15 | 0 | 0 | 0 |
| myhomelib-benchmark | 3 | 0 | 0 | 3 |
| **Total** | **1097** | **0** | **0** | **12** |

Skipped tests are opt-in/environment-specific probes. The critical real-file probes were executed separately below.

## 2. Display-capable JavaFX acceptance

Final Xvfb run:

`FX_TEST_GATE_OK: tests=8, skipped=0, suites=6`

Suites:

- `AnnotationManagerFxmlFxTest`: 1;
- `BookFilterDialogFxTest`: 1;
- `ReaderWorkspaceSidebarFxTest`: 1;
- `MainLayoutServiceFxTest`: 3;
- `MainToolbarWrapFxTest`: 1;
- `BookTableActivityFxTest`: 1.

**Failures / errors:** 0 / 0.

## 3. Static / release gates

The Iteration 85 finish/polish gate and the full static/release gate set passed. Final key recheck:

- `ITERATION85_FINISH_POLISH_CHECK_OK`;
- Spring proxyability: PASS — 246 final production classes inspected;
- offline static release check: PASS;
- XML/POM/FXML: 45, errors 0;
- FXML workspaces: 29; handler references: 256; missing: 0;
- SQLite migrations: 60; integrity: OK;
- root shell scripts: 10; static issues: 0;
- release packaging/CI static issues: 0;
- Java sources: 1502;
- test sources: 378.

The broader static suite also passed localization, architecture, implementation-completeness, functional-regression, Reader refactor, annotation, INPX, online-update, XML/archive-security, privacy/temp-lifecycle, managed-executor, Spring wiring/proxyability and startup-orchestration gates.

## 4. Reader JFR / heap profile

Reproducible synthetic FB2 and EPUB fixtures were generated at 20, 50 and 100 MB. Each size included 20 open/close cycles.

| Fixture | FB2 parse | EPUB parse | Peak heap delta | Retained after 20 open/close | GC collections |
|---:|---:|---:|---:|---:|---:|
| 20 MB | 313.2 ms | 349.7 ms | 79.3 MB | 0.0 MB | 126 |
| 50 MB | 606.0 ms | 757.9 ms | 183.6 MB | 0.0 MB | 319 |
| 100 MB | 1107.0 ms | 1107.7 ms | 497.6 MB | 0.0 MB | 397 |

Guardrails:

- parse time < 15 s;
- peak heap delta < 768 MB;
- retained heap after 20 open/close < 256 MB.

**Result: PASS for all three sizes.**

## 5. Real INPX acceptance

Input: user-supplied `flibusta_online_fb2.inpx`.

Production full-snapshot pipeline, batch 5000:

- records: **707154**;
- processed/imported: **707154 / 707154**;
- errors: **0**;
- total duration: **96218 ms**;
- throughput: **7349.50 books/s**;
- books: 707154;
- authors: 166231;
- book-author links: 856591;
- genres: 272;
- book-genre links: 1102219;
- catalogue-state rows: 707154;
- deleted-state rows: 135207;
- DB size: 1,583,419,392 bytes.

Input SHA-256: `75bebb7a7ccf203bd934ef2af986f17d737ba4c4abfc277956f60bb84a6c7655`.

**Result: PASS.**

## 6. Real FB2 Reader acceptance

Both user-supplied large FB2 ZIP archives passed the streaming Reader probe.

### Corpus item 1

- compressed FB2 entry: 14,618,515 bytes;
- text characters: 6,657,913;
- paragraphs: 54,551;
- chapters: 16;
- TOC entries: 316;
- resources: 41;
- parse: **854.3 ms**.

### Corpus item 2

- compressed FB2 entry: 8,181,973 bytes;
- text characters: 3,470,361;
- paragraphs: 28,682;
- chapters: 9;
- TOC entries: 172;
- resources: 38;
- parse: **467.7 ms**.

All title/text/chapter/offset/TOC invariants passed.  
**Result: PASS.**

## 6A. Post-audit hardening validation

Additional regression evidence on the hardened candidate:

- process execution deadlock/timeout regressions: PASS;
- OPDS non-loopback anonymous-access regression and auth challenge behavior: PASS;
- bounded HTTP/ConnectionScript download regressions: PASS;
- content-index queue/power-state regressions: PASS;
- MCP schema/WAL read-only compatibility regressions: PASS;
- architecture ratchets: PASS (`MainController` 674/680; `LuceneSearchService` 439/460);
- supply-chain policy: PASS with immutable GitHub Actions SHA pins;
- Linux `jpackage` app-image + portable archive smoke: PASS;
- `SHA256SUMS`: 3 release artifacts verified;
- `stage23-cross-platform-release-check.py --require-checksums --require-portable`: PASS.

The evidence-bundle verifier regression suite also passed. Its live immutable reviewer bundle remains an external acceptance input and is not fabricated locally.

## 7. Roadmap closure

The remaining local items from the original audit are now implemented and validated:

- book-level notes/highlights/bookmarks activity indication;
- books-with-notes / books-with-highlights filtering;
- fuller display-capable UI acceptance;
- 20/50/100 MB Reader JFR/heap profiling.

Together with Iteration 84, this closes the locally executable P0/P1/P2 roadmap identified in the Iteration 83 audit.

## 8. Remaining external release gates

These are not claimed PASS because this environment is Linux/offline:

- Windows-native TTS and DPAPI runtime paths;
- Windows DPI 100/125/150/200 visual acceptance;
- MSI/EXE/portable install, update, uninstall and desktop smoke as a standard Windows user;
- live GitHub required-check enforcement / PR evidence;
- live SBOM publication evidence;
- live SCA/vulnerability report;
- live exact-candidate CodeQL/SAST evidence.

These are external release acceptance, not unfinished local application functionality.

## Final conclusion

**Iteration 85 closes the local Finish/Polish roadmap and is validated as the final local source candidate.**
