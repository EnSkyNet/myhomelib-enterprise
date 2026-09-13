# MyHomeLib Enterprise 7.1.0 RC3 — acceptance checkpoint, 2026-09-04

This checkpoint records the current source state after the release-hardening pass.

## Completed in this pass

- Added and executed the V1/V10/V20/V30/V40/V44 → V48 migration matrix.
- Added durable online-update crash checkpoint/marker recovery before SQLite/Hikari opens the DB.
- Made user Restore crash semantics explicit with `.restore.pending`; a stale
  `.restore.previous` after a committed Restore can no longer trigger an accidental rollback.
- Kept the previous committed Lucene index intact until an atomic full rebuild commits.
- Added synthetic fixed Reader golden fixtures for rich FB2 and multi-book ZIP.
- Fixed a Reader parser defect discovered by the new golden fixture: a poem title could overwrite
  its enclosing chapter title.
- Revalidated the 13-module offline Maven reactor with `verify -DskipTests`.
- Executed 17 selected release-critical tests against the compiled test classes: 17 passed.

## Remaining release gates

The exact customer/problem FB2/ZIP files are not present in this source package, so the synthetic
fixtures do **not** replace final acceptance with those real books. Windows runtime/DPI,
installer/portable, bundled runtime, upgrade/uninstall, live online update failure+Retry and full
GUI acceptance remain mandatory before RC3 is promoted to final 7.1.0.

## Hardening checkpoint 03 addendum

The next hardening pass added the explicit online-update marker commit boundary, rollback → immediate Retry
regression, ambiguous multi-DB Restore rejection, Export overwrite crash-safety tests, cross-platform deterministic
checksums, stable per-user Windows installer identity, and a one-publisher cross-platform release workflow.
The current release-critical compiled suite is **23 PASS / 0 FAIL** and the Linux published portable archive itself
was extracted and runtime-smoked successfully. See `RC3-HARDENING-2026-09-04-03.md` for the detailed evidence.

## Hardening checkpoint 04 addendum

A semantic migration audit found two historical data-loss edges that were not visible in the original
"migration reaches V48" matrix: V7 could collapse distinct authors sharing first/last name, and V26 could
discard chapter/read-time fields written while a database was on V25. `LegacyMigrationDataGuard` now protects
future pre-V7/V25 upgrades without rewriting immutable Flyway files, survives an interrupted migration via durable
guard tables, restores/verifies the protected rows after the final schema, and then removes the guard state.
The repaired offline v7.1 gate now validates V1..V48 and immutable V1..V36 checksums. Current release-critical
compiled suite: **37 PASS / 0 FAIL**. See `RC3-HARDENING-2026-09-04-04.md`.
