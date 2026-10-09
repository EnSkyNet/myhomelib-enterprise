# MyHomeLib — User-Facing Functional Audit V24

Date: 2026-09-29  
Continuation source: `myhomelib-enterprise-optimized-v23-checkpoint-2026-09-29.zip`  
Original derivation baseline: V22 only.  
Product version: MyHomeLib Enterprise 8.0.0.

## Executive conclusion

A complete **user-facing surface inventory** has now been performed. Every current FXML event binding, CoreAction registration, workspace help route, and JavaFX programmatic `setOnAction` surface is accounted for in the machine-readable coverage inventory. The user-facing capability catalogue has been written separately in Ukrainian.

This does **not** equate structural inventory with live execution. Functions that require Windows-only binaries, OS integration, real devices, external readers, system TTS/FFmpeg, or live network/provider credentials remain explicitly marked LIVE/WIN rather than being falsely reported as executed in the Linux audit environment.

One concrete user-facing defect was reproduced and fixed: F1 context help for newer workspaces could silently fall back to generic start help.

## Inventory result

`tools/user-facing-functional-audit.py` passes with:

- 29 FXML files;
- 262 FXML event bindings;
- 244 unique controller-handler entry points;
- 0 missing FXML handler references;
- 14 CoreActions defined and 14/14 registered;
- 25 workspace help routes and 0 unmapped current workspace routes;
- 23 known help topics and 0 missing default help resources;
- 34 JavaFX classes with programmatic action surfaces;
- 185 programmatic `setOnAction` calls inventoried.

The existing functional-regression ratchet also passes:

- 29 FXML files retained;
- 244 unique FXML bindings retained;
- 461 FXML ids retained;
- 31 critical behavior ratchets retained.

## Runtime / automated evidence

Full Maven 3.9.16 `clean verify`, JDK 21, UTF-8 locale, JavaFX under Xvfb:

- **1817 tests**;
- **0 failures**;
- **0 errors**;
- **17 skipped**;
- **20/20 reactor modules SUCCESS**;
- `BUILD SUCCESS`.

Relevant functional layers include:

- `myhomelib-ui`: **241 tests**, 0 failures/errors;
- `myhomelib-e2e-tests`: **15 tests**, 0 failures/errors, across six journey classes;
- all 29 FXML resources are loaded by the real JavaFX `FXMLLoader` in the localization FX suite for UK/EN/BG;
- import/search/reader, annotation lifecycle, backup/restore, collection rollback, OPDS, and reader-corpus journeys are exercised by E2E tests;
- conversion selection/concurrency/cancellation contracts, PDF/comic workflows, synchronization conflicts, health/maintenance, localization/accessibility, metadata, OPDS, plugin and integration behavior have targeted automated coverage elsewhere in the reactor.

The coverage inventory additionally records whether an FXML controller or a programmatic action class has a directly associated test file. That is a diagnostic mapping only, not a substitute for code-coverage instrumentation: service-level and E2E tests can exercise behavior without sharing the controller class name.

## Confirmed defect: context-sensitive F1 help

### Regression evidence

Before the production change, an actual `HelpTopicRegistry` characterization harness reproduced generic `index` fallback for at least the newer `downloaded-author` route when `navigation` was expected. The same missing-registration condition existed for `publisher`, `followed-authors`, `operations`, and `annotations`.

### Regression test first

Added `HelpTopicRegistryTest` requiring:

- `downloaded-author` → `navigation`;
- `publisher` → `navigation`;
- `followed-authors` → `updates`;
- `operations` → `operations`;
- `annotations` → `annotations`;
- `operations` and `annotations` to be recognized bundled topics.

### Minimum fix

Only `HelpTopicRegistry` mappings/topic set were extended. Added localized `annotations.md` and `operations.md` help topics for default/UK, EN and BG resources. No unrelated controller, navigation, reader, conversion or persistence behavior was rewritten.

### Targeted/downstream verification

- pre-fix characterization: FAIL as expected;
- post-fix characterization: PASS;
- `HelpTopicRegistryTest`: **2/2 PASS**;
- `tools/user-facing-functional-audit.py`: PASS with 25/25 current workspace routes mapped and all 23 bundled default topics present;
- full reactor: **1817 tests / 0 failures / 0 errors / 17 skipped**.

## User-facing capability catalogue

Canonical human-readable description:

`docs/release/USER-FUNCTIONS-V24-UA.md`

It covers 30 product areas, including desktop navigation/catalogue, search and smart collections, collections, import, online downloads, metadata, batch selection, conversion, export/device, readers (text/PDF/comics/audio), annotations, followed authors/updates, groups, library health/statistics, duplicates, organizer, Operation Center, backup/restore, folder/WebDAV sync, settings, custom book actions/shortcuts, OPDS, MCP, plugins/AI, localization/accessibility/help, and externally consumed product surfaces.

Machine-readable evidence inventory:

`docs/release/FUNCTIONAL-COVERAGE-V24.json`

It contains every current FXML event binding and every detected programmatic action-surface class, plus structural counts, CoreAction ids and explicit limitations.

## Coverage interpretation

The audit uses these evidence levels:

- **E2E** — multi-component product journey executed automatically;
- **AUTO** — targeted automated behavior/contract test;
- **WIRE** — current UI entry point exists, resolves to a real handler/action, and passes structural/runtime-FXML checks;
- **LIVE** — requires a real external provider/tool/device/session;
- **WIN** — specifically requires Windows x64 acceptance.

Therefore the correct statement is: **all current user-facing functions have been inventoried and audited to an explicit evidence level; not every function has live platform/provider acceptance in this Linux environment.**

## Direct-test mapping diagnostics

For transparency rather than as a release score:

- 199/262 FXML event-binding records belong to controllers with a directly associated test file;
- 181/244 unique FXML controller-handler pairs belong to controllers with a directly associated test file;
- 19/34 classes with programmatic JavaFX action wiring have a directly associated test file;
- those directly associated classes contain 101/185 detected `setOnAction` calls.

The remaining entries are not automatically defects. They are structurally validated and many delegate into tested services/use cases or are covered by broader E2E/runtime tests. Creating shallow controller tests only to make these ratios 100% would violate the no-overengineering guardrail unless a risk or regression justifies them.

## Live / platform acceptance still open

The following cannot be honestly closed by the current Linux/Xvfb audit:

1. Windows x64 Calibre large-FB2 conversion beyond five minutes.
2. Real cancellation/process-tree teardown, handle release and app-close teardown for Calibre.
3. Real checkbox batch conversion with observed maximum external concurrency 2 and partial-failure behavior.
4. Windows DPI 100/125/150/200 and installer/portable lifecycle gates.
5. Real OS send-to-device/eject and configured external-reader executables.
6. Real system TTS and FFmpeg-dependent playback/transcoding paths where environment-dependent.
7. Real WebDAV, translation, metadata and AI provider credentials/endpoints.
8. Real external OPDS/MCP client interoperability beyond automated protocol/E2E tests.

These are carried forward as explicit acceptance tasks, not hidden behind automated PASS status.

## Performance / memory

No performance or memory change was made. No A/B measurement justified one, so V24 makes no new performance claim.
