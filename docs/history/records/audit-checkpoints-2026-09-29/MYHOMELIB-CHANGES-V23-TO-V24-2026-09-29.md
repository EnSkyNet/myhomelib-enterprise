# MyHomeLib — Changes V23 → V24

Date: 2026-09-29  
Continuation source: `myhomelib-enterprise-optimized-v23-checkpoint-2026-09-29.zip`.

## Scope

V24 is a **user-facing functional-audit checkpoint**. It adds exhaustive UI-surface inventory/description and fixes one reproduced context-help defect. No converter timeout/process-lifecycle/batch semantics, persistence schema, search algorithm, reader algorithm, or performance/memory implementation was changed.

## Production change

`HelpTopicRegistry` now maps current specialized workspaces instead of falling back to generic `index` help:

- `downloaded-author` → `navigation`;
- `publisher` → `navigation`;
- `followed-authors` → `updates`;
- `operations` → `operations`;
- `annotations` → `annotations`.

Added bundled `annotations` and `operations` help topics for default/UK, EN and BG.

## Regression coverage

Added `HelpTopicRegistryTest` with two regression tests. The defect was characterized before the fix and the same scenario passed afterward.

## Functional audit tooling/documentation

Added:

- `tools/user-facing-functional-audit.py`;
- `docs/release/USER-FUNCTIONS-V24-UA.md`;
- `docs/release/FUNCTIONAL-COVERAGE-V24.json`;
- `docs/release/FUNCTIONAL-AUDIT-V24-UA.md`.

The structural gate verifies current FXML handlers, CoreActions registration, workspace-help coverage, help resources, and programmatic JavaFX action surfaces.

Updated `docs/release/CURRENT-VALIDATION.md` and the exact-current external-readiness structural token from 1815 to the measured V24 total of **1817** tests.

## Verification

- user-facing functional audit: PASS;
- legacy functional-regression gate: PASS;
- targeted `HelpTopicRegistryTest`: 2/2 PASS;
- full Maven `clean verify`: **1817 tests, 0 failures, 0 errors, 17 skipped; 20/20 modules SUCCESS**;
- E2E: **15 tests**, 0 failures/errors;
- UI module: **241 tests**, 0 failures/errors.

## Intentionally unchanged

- Calibre 1800 s timeout / legacy-300 migration;
- 10 s process termination grace;
- batch selection/common-format/concurrency/cancellation semantics;
- Flyway migrations;
- library/search/reader algorithms;
- performance/memory behavior.
