# MyHomeLib 8.0.0 — Iteration 84 final validation report

**Дата:** 2026-09-14  
**Candidate:** MyHomeLib 8.0.0 / Iteration 84  
**Локальний статус:** **PASS**  
**Середовище:** Linux, OpenJDK 21.0.11, Maven 3.9.6, `C.UTF-8`, offline Maven repository.

## 1. Full Maven verification

Команда класу: offline `mvn clean verify` по всьому 16-project reactor.

**Результат:** `BUILD SUCCESS`.

| Модуль | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| myhomelib-shared | 19 | 0 | 0 | 0 |
| myhomelib-domain | 24 | 0 | 0 | 0 |
| myhomelib-application | 294 | 0 | 0 | 1 |
| myhomelib-plugin-api | 28 | 0 | 0 | 0 |
| myhomelib-plugin-samples | 2 | 0 | 0 | 0 |
| myhomelib-infrastructure | 441 | 0 | 0 | 6 |
| myhomelib-reader | 83 | 0 | 0 | 1 |
| myhomelib-ui | 107 | 0 | 0 | 0 |
| myhomelib-web | 6 | 0 | 0 | 0 |
| myhomelib-opds | 20 | 0 | 0 | 0 |
| myhomelib-bootstrap | 18 | 0 | 0 | 0 |
| myhomelib-mcp | 6 | 0 | 0 | 0 |
| myhomelib-architecture-tests | 14 | 0 | 0 | 0 |
| myhomelib-e2e-tests | 15 | 0 | 0 | 0 |
| myhomelib-benchmark | 3 | 0 | 0 | 3 |
| **Разом** | **1080** | **0** | **0** | **11** |

Skipped-тести — opt-in performance/environment probes (real INPX/FB2/Lucene/statistics/author search), Windows DPAPI integration та benchmark guardrails. Критичні real INPX і real FB2 probes були запущені окремо й пройшли — див. нижче.

## 2. Display-capable JavaFX gate

Після виправлення Surefire/headless policy JavaFX gate запускався через Xvfb із display-capable JVM.

**Результат:** `FX_TEST_GATE_OK: tests=5, skipped=0, suites=3`.

- `AnnotationManagerFxmlFxTest`: 1
- `MainLayoutServiceFxTest`: 3
- `MainToolbarWrapFxTest`: 1

**Failures/Errors:** 0/0.

Це підтверджує, що UI gate більше не є zero-test false green.

## 3. Static / release gates

Виконано **21/21** незалежних gate-команд, усі завершилися `EXIT=0`.

Ключові результати:

- Critical UI localization: PASS — 597 stable keys / 20 source files.
- Architecture baseline: PASS; UI debt ratchet покращено до 15/18 output-port users і 24/28 non-value domain-model users.
- Implementation completeness: PASS; 0 TODO/FIXME/unsupported markers, 0 empty public/protected methods, 0 exact large cross-file clones.
- Functional regression: PASS — 29 FXML, 238 retained bindings, 454 retained ids, 31 critical behavior ratchets.
- Reader refactor: PASS — `ReaderCanvas` 940 lines; selection/history and overlay painting extracted.
- Language catalogues: PASS — UK/EN/BG, по 1031 UI keys + 335 genre keys.
- Annotation stages 35/36/37: PASS.
- INPX hot-path/search-index consistency: PASS.
- Online runtime/download/rollback: PASS.
- XML/archive security: PASS.
- Privacy/temp lifecycle: PASS.
- Managed executors / Spring wiring / proxyability / startup orchestration: PASS.
- Offline static release check: PASS — 29 FXML workspaces, 256 handler references, 0 missing; 60 SQLite migrations, integrity OK; 10 root shell scripts, 0 static issues.

## 4. Real INPX acceptance — user supplied Flibusta index

Файл: `flibusta_online_fb2.inpx`.

Production pipeline, full snapshot, batch 5000:

- records processed/imported: **707154 / 707154**;
- errors: **0**;
- transaction commit: **78179 ms**;
- total import duration: **78750 ms**;
- throughput: **8979.73 books/s**;
- books: 707154;
- authors: 166231;
- book-author links: 856591;
- genres: 272;
- book-genre links: 1102219;
- catalog state rows: 707154;
- DB size: 1,584,816,128 bytes.

**Result:** PASS.

## 5. Real FB2 Reader acceptance

Запущено opt-in `RealFb2ReaderProbeTest` на двох наданих користувачем FB2 ZIP archives.

### Corpus item 1

- compressed FB2 entry: 14,618,515 bytes;
- text characters: 6,657,913;
- paragraphs: 54,551;
- chapters: 16;
- TOC entries: 316;
- resources: 41;
- parse: **874.9 ms**.

### Corpus item 2

- compressed FB2 entry: 8,181,973 bytes;
- text characters: 3,470,361;
- paragraphs: 28,682;
- chapters: 9;
- TOC entries: 172;
- resources: 38;
- parse: **467.0 ms**.

All title/text/chapter/offset/TOC invariants passed.  
**Result:** PASS.

## 6. Important regressions found and fixed during final validation

Final validation was not only confirmatory. It found and fixed:

- Unix ASCII filesystem/native encoding handling in `RuntimeEncodingGuard` while avoiding false failures on Windows native code pages.
- Stale User-Agent test expecting 7.1 after production identity moved to 8.0.0.
- Missing offline PDFBox/JBIG2 test dependencies in the local validation repository (test environment only; source unchanged).
- Stale UI source-contracts after `ReaderAnnotationCoordinator` extraction.
- Missing accessible label for annotation issue control.
- Surefire `java.awt.headless=true` configuration that previously prevented Xvfb Fx tests from actually running.
- Critical UI localization/architecture/completeness/refactor gate findings without weakening the gates.
- A Smart Collection accessor regression introduced during architecture cleanup.

After remediation, the entire Maven reactor and independent gates were rerun successfully.

## 7. Remaining external gates

The following are **not claimed PASS** because this environment is Linux/offline and cannot provide honest external evidence:

- live GitHub required-check enforcement / PR CI evidence;
- Windows DPI 100/125/150/200 screenshot acceptance;
- real Windows standard-user MSI/EXE/portable install/update/uninstall/desktop smoke;
- Windows native TTS/DPAPI paths not executable on Linux;
- live release SBOM publication evidence;
- live SCA/vulnerability report under release policy;
- live exact-candidate CodeQL/SAST evidence.

These remain external release gates and must not be inferred from the local green build.

## Final conclusion

**Iteration 84 source is locally validated and ready as the final source candidate.**  
All executable local gates available in this environment are green. Windows/live-service acceptance remains explicitly external.
