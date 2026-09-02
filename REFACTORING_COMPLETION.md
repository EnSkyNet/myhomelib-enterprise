# MyHomeLib Enterprise — refactoring completion baseline

Date: 2026-09-02

This file records the source-level completion baseline after the 2026-09-02 stabilization/refactoring pass. It is intentionally separate from formal binary release acceptance.

## Completed stabilization scope

- centralized `LibraryOperationCoordinator` for conflicting collection operations;
- real import/online-update `OperationProgress` without synthetic `value * 1000` counters;
- explicit import stages including Lucene and statistics refresh;
- Hikari/SQLite transaction telemetry for long INPX imports;
- safe collection create cleanup/rollback and guarded physical deletion;
- staged backup/restore validation with SQLite `quick_check` and restore rollback;
- Lucene read-gate while an index is dirty/rebuilding plus synchronized-index lifecycle;
- stale statistics are explicit and refreshed in background rather than displayed as current;
- bounded Lucene search and bounded Authors navigation/search instead of materializing entire large result sets in JavaFX;
- nondestructive folder sync for temporarily missing local files, with `missing_since` (Flyway V44);
- export last-profile/last-state persistence;
- followed-author workspace, unread catalogue updates and acknowledgement lifecycle;
- compact Reader `PageIndex` with semantic reading position preserved across reflow;
- Reader bookmark/position persistence failures are no longer silently converted to empty/success states;
- reorganized main menu and collection workspace operations moved off the JavaFX Application Thread;
- centralized Operation Center for long-running UI-visible operations;
- Unix wrapper/release script executable permissions fixed and preserved in source ZIPs.

## Source-level validation status

The following project checks pass in the current tree:

- architecture-check;
- functional-regression-check;
- implementation-completeness-check;
- catalog/search/import/index/online lifecycle checks;
- collection delete safety;
- Reader, user-data and search-index consistency checks;
- Stage 3–22 checks applicable to source validation;
- Stage 24 performance baseline check;
- Stage 25A/B/C structural refactoring checks;
- startup nonblocking/transaction checks;
- localization validation;
- static release check;
- UI function reachability.

`stage23-cross-platform-release-check.py` is not a source-only check: it requires a generated `dist/` directory. It is therefore expected to fail before a real Maven/jpackage build.

## Formal release validation still required

The source refactoring is complete to the level verifiable in this environment. Formal release acceptance still requires a machine with Maven dependency access (or a populated local Maven cache):

```bash
./mvnw clean verify -Pproduction
```

Then run the platform packaging/CI matrix and `stage23-cross-platform-release-check.py` against generated `dist/` artifacts.

In the current execution environment Maven Wrapper startup is valid, but dependency bootstrap is blocked by DNS/network access to `repo.maven.apache.org`; this is an environment limitation, not a source-level green-build claim.
