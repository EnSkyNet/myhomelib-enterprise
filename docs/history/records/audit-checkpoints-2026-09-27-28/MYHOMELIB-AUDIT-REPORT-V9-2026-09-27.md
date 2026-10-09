# MyHomeLib — deep technical audit / V9 checkpoint

Дата: 2026-09-27
Baseline: `myhomelib-enterprise-optimized-v8-checkpoint-2026-09-27.zip`

## 1. Executive summary

V9 продовжує V8 без зміни поведінкових контрактів заради «чистоти». Нові production-зміни приймалися лише після red regression або як один вузький responsibility extraction. GC та heap defaults не змінено: production-scale A/B показав реальний memory trade-off Serial GC, але також суттєво гірший pause profile; `-Xmx512m/768m` не показали достатнього сумарного виграшу проти `-Xmx1024m`.

Фінальні gates:

- `./mvnw -o ... clean verify`: **BUILD SUCCESS**, 20/20 reactor modules, 03:03;
- aggregate Surefire: **1736 tests, 0 failures, 0 errors, 17 skipped**;
- `myhomelib-ui` під Xvfb: **199/199 PASS**;
- `myhomelib-application`: **453 tests, 0 failures/errors, 1 skipped**;
- `myhomelib-reader`: standalone final suite **131 tests, 0 failures/errors, 1 skipped**; consolidated clean verify reports 132 Reader tests;
- `myhomelib-bootstrap`: **47 tests, 0 failures/errors, 2 external-tool skips**;
- `myhomelib-infrastructure` у consolidated verify: **634 tests, 0 failures/errors, 9 skipped**;
- implementation completeness / static release / XML-archive security / supply-chain policy: **PASS**;
- production reactor `package -Pproduction -DskipTests`: **BUILD SUCCESS**;
- Linux `jpackage` app-image: **PASS**;
- stage23 release checker: **PASS**;
- packaged launcher `--release-smoke`: **PASS**.

## 2. Correctness defects closed in V9

### 2.1 Plugin install used stale package inventory

Problem: `DesktopIntegrationBackend.installPlugin()` could decide replacement using stale `pluginPackages` when another valid JAR with the same `pluginId` appeared after startup scan. The call could return success while the following scan exposed the old package/version.

Evidence: regression first reproduced successful install of `1.0.0` followed by descriptor `0.9.0`.

Fix: refresh package inventory immediately before replacement decision; existing hash ownership validation remains authoritative.

Downstream: plugin integration group **21/21 PASS**.

### 2.2 Cumulative INPX decompressed-size bypass

Problem: each INPX ZIP entry was bounded independently, but multiple individually valid entries could cumulatively exceed the shared 1 GiB archive safety budget.

Evidence: regression with several entries under the per-entry 512 MiB and compression-ratio limits failed before the fix because no cumulative rejection occurred.

Fix: overflow-safe declared-size accumulation with early rejection against the shared total limit.

Downstream: INPX resource/compatibility/import-pipeline tests PASS.

### 2.3 Cumulative EPUB importer size bypass

Same defect class existed in infrastructure EPUB import validation. Added independent regression and local overflow-safe cumulative guard; no cross-format generic framework was introduced.

Downstream archive/EPUB/CBZ compatibility group: **46 tests, 0 failures/errors**.

### 2.4 Cumulative Reader EPUB size bypass

`reader`'s `EpubParser.validateArchive()` also validated entries independently without cumulative 1 GiB enforcement.

Evidence: regression with 3 × 400 MiB declared entries, each independently valid, was red before fix.

Fix: local overflow-safe cumulative declared-size guard.

Final Reader suite is green.

### 2.5 Annotation summary could split a surrogate pair

`AnnotationManagerWorkspaceController.summaryText()` truncated at a raw UTF-16 index and could leave an unpaired high surrogate when an emoji crossed the boundary.

Fix: decrement the cut boundary only when it lands between a high/low surrogate pair. Behavioral regression verifies absence of unpaired surrogates.

### 2.6 Support-bundle setting truncation could split a surrogate pair

`SupportBundleService.safeValue()` truncated at 4096 UTF-16 units. An emoji starting at index 4095 produced a dangling high surrogate before `…<truncated>`.

Evidence: refined regression was red before production change and green after it.

Fix: preserve the existing 4096-unit maximum prefix policy, but move the cut one unit earlier only when the boundary splits a surrogate pair.

`SupportBundleServicePrivacyTest`: **5/5 PASS**.

## 3. Structural decomposition

### 3.1 `PluginRuntimePackageStager`

Extracted only the security-critical runtime package staging boundary:

- copy verified plugin bytes into private runtime workspace;
- re-hash staged bytes to close package TOCTOU;
- reject changed package;
- clean private staging workspace on failure/close.

Approval policy, enable/disable semantics, discovery and extension orchestration remain in `DesktopIntegrationBackend`.

### 3.2 `OrganizerTargetPublisher`

Extracted only fresh-target publication / temporary-copy rollback from `ApplyLibraryOrganizationUseCase`.

Filesystem planning, organization policy and higher-level orchestration remain in the use case.

Organizer full module: **29/29 PASS**.

### 3.3 Structural clone decisions

`implementation-completeness-check.py` reports **0 exact cross-file method clones >=180 chars**. V9 therefore continued normalized/semantic review rather than literal clone chasing.

Not extracted in V9:

- simple repeated cumulative archive-limit loops across format layers: common numeric policy is already centralized, while local validation contexts/error contracts differ;
- UI generation/Future cancellation snippets: shared abstraction was not introduced because current consumers have materially different context/ownership semantics and a generic wrapper would not yet reduce risk enough;
- portable restore transaction flow: audit did not confirm a correctness hole; current-schema preflight, DB transaction and compensation paths are already present.

## 4. Production-scale performance / memory evidence

### 4.1 Dataset and method

INPX dataset: `flibusta_online_fb2.inpx`, 39,184,820 bytes, **707,154 records**.

Every completed import run reconciled:

- imported/processed: 707,154;
- errors: 0;
- books: 707,154;
- authors: 166,231;
- bookAuthors: 856,591;
- genres: 272;
- bookGenres: 1,102,219;
- catalogState: 707,154;
- deleted: 135,207;
- SQLite `quick_check`: OK.

GC A/B used equal `-Xms64m`, `-Xmx1024m` and identical workload; only collector changed. Values below are medians of 3 completed runs unless stated otherwise.

### 4.2 INPX import — G1 vs Serial

| Metric | G1 | Serial | Serial delta |
|---|---:|---:|---:|
| Import duration | 78,798 ms | 80,042 ms | +1.58% |
| Peak RSS | 775,264 kB | 703,836 kB | -9.21% |
| Sum GC pauses | 1,449.716 ms | 7,805.142 ms | +438.39% |
| Max GC pause | 37.866 ms | 169.962 ms | +348.85% |

Conclusion: Serial saves memory, but with a materially worse pause profile and no import-throughput advantage.

### 4.3 Lucene rebuild — G1 vs Serial

571,947 active documents; output index ~185 MB. Three runs per GC.

| Metric | G1 | Serial | Serial delta |
|---|---:|---:|---:|
| Wall time | 68,169 ms | 52,100 ms | -23.57% |
| Peak RSS | 543,876 kB | 363,300 kB | -33.20% |
| Observed peak heap | 235,838,400 B | 91,105,624 B | -61.37% |
| Sum GC pauses | 2,060.758 ms | 6,085.162 ms | +195.29% |
| Max GC pause | 38.669 ms | 141.670 ms | +266.37% |

Serial is attractive for this batch workload's memory and wall-clock, but pause regression is large enough that this result cannot justify changing a desktop default by itself.

### 4.4 Large-book Reader search — G1 vs Serial

Ephemeral benchmark harness: ~2.469M characters / 20,000 paragraphs, 10 search iterations; 3 runs per GC. The harness was removed before checkpoint because it is not suitable as an always-on unit test.

Median search time: G1 **14.3 ms**, Serial **12.4 ms**. Median RSS: G1 **194,060 kB**, Serial **190,488 kB**. This workload does not show a decisive regression or benefit.

### 4.5 `-Xmx` matrix, G1, production INPX

512 and 768 MiB have 2 completed runs each; 1024 MiB has 3.

| Xmx | Median import | Median peak RSS | Median sum pauses |
|---|---:|---:|---:|
| 512 MiB | 84,047 ms | 729,248 kB | 2,015.892 ms |
| 768 MiB | 83,522.5 ms | 737,412 kB | 1,925.079 ms |
| 1024 MiB | 78,798 ms | 775,264 kB | 1,449.716 ms |

Relative to 1024 MiB:

- 512 MiB: ~5.94% lower RSS, ~6.66% slower import, ~39.05% more summed GC pause time;
- 768 MiB: ~4.88% lower RSS, ~6.00% slower import, ~32.79% more summed GC pause time.

Decision: **keep `-Xmx1024m`**. The smaller heaps work functionally, but the measured trade-off is not compelling enough for a default change.

### 4.6 Packaged desktop G1 vs Serial

Same prepared `user.home`, same 1200×800 main-window readiness condition, same 5 s idle interval, full GC before memory sampling. Two valid runs per GC.

Median values:

| Metric | G1 | Serial | Serial delta |
|---|---:|---:|---:|
| Main UI ready | 7,982 ms | 9,504 ms | +19.07% |
| RSS after full GC | 399.66 MiB | 379.93 MiB | -4.94% |
| PSS after full GC | 385.72 MiB | 365.07 MiB | -5.35% |
| OS threads | 57 | 49 | -14.04% |
| Live Java heap | ~60.7 MiB | ~61.0 MiB | approximately equal |

This closes the previous contaminated startup comparison: Serial genuinely reduces packaged native footprint, but also slows startup and already has clearly worse pauses in production batch workloads. **G1 remains the default.**

### 4.7 New V9 packaged memory baseline / NMT

Separate diagnostic G1 run with NMT after main-window readiness + 5 s idle + full GC:

- RSS: **397,128 kB (~387.82 MiB)**;
- PSS: **381,913 kB (~372.96 MiB)**;
- private clean: 41,852 kB;
- private dirty: 326,564 kB;
- live heap: ~62,276 kB;
- OS threads: 57.

NMT summary:

- total committed native accounting: 264,631 kB;
- Java Heap committed: 79,872 kB;
- Class committed: 12,330 kB;
- Thread committed: 3,276 kB;
- Code committed: 23,473 kB;
- GC committed: 43,921 kB;
- Metaspace committed: 69,862 kB.

NMT itself adds tracking overhead; use the normal packaged G1 runs for primary RSS/PSS comparison and NMT only for category attribution.

## 5. Release / packaging verification

Current app-image invariants verified:

- 27 runtime modules; development tools excluded;
- `jdk.management` present through the packaged runtime contract;
- EPUBCheck 5.4.0 packaged as external tool, outside the main application classpath;
- bundled `libmobi` absent and desktop packaging continues in the supported optional-tool mode;
- Spring `prod` profile and existing `-Xmx1024m` launcher configuration retained;
- stage23 artifact validation PASS;
- packaged launcher returns `MYHOMELIB_RELEASE_SMOKE_OK`.

Real Windows x64 packaging/runtime smoke was not possible in this Linux environment and remains explicitly open.

## 6. Checkpoint decision

V9 is suitable as the next source baseline because:

- every V9 correctness change has a regression test;
- refactors were limited to one responsibility boundary each;
- no GC/heap default was changed without sufficient evidence;
- full reactor `clean verify` is green;
- Linux packaged runtime gates are green;
- source tree has no benchmark harness/temp runtime artifact intended for normal tests;
- static/security/completeness gates are green.
