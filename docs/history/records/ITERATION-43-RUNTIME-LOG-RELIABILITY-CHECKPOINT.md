# Iteration 43 — Runtime-log reliability fixes

Date: 2026-09-11
Baseline: formal Iteration 42 source archive.

## Trigger

A real Windows runtime log showed four actionable problems while normal startup/search/Reader flow otherwise remained healthy:

1. Reader failed for some catalogue books stored inside archives with `ui.reader.error.archive_entry_read` even though archive location resolution had already succeeded.
2. `SqliteDateTimeCodec` warned on SQLite-native `CURRENT_TIMESTAMP` values such as `2026-09-05 18:37:45`.
3. Hikari disabled leak detection because the configured 30-minute threshold exceeded the 10-minute `maxLifetime`.
4. A raw stable localization key was visible in the exception message, indicating an older writable shipped language snapshot could miss keys added by a newer application build.

## Changes

### Reader/archive compatibility

- `NewReaderWorkspaceController` no longer reopens a selected archive member by the stored catalogue name after `locateBookFile()` accepted a compatible legacy/server-renamed member.
- Selected archive books are read through `BookResourcePort.readBookData(book)`, which repeats the compatibility resolution and opens the actual member.
- `ResolveBookContentUseCase` uses the same fallback-aware path when the catalogue contains an `archiveEntry`.
- Whole-archive Reader behavior is unchanged when the book has no selected `archiveEntry`; multi-book ZIP parsing still receives the original archive.
- Compatibility reads now log the stored-to-actual archive entry mapping at INFO level.

### SQLite timestamp compatibility

- Canonical writes remain `yyyy-MM-dd HH:mm:ss.SSS`.
- Reads now accept SQLite-native seconds, fractional seconds up to nanoseconds, and the existing ISO local-date-time fallback.

### Hikari leak detection

- Collection/default SQLite pool `maxLifetime` is now 35 minutes while leak detection remains 30 minutes.
- This keeps leak detection enabled instead of letting Hikari silently disable it because the threshold exceeded `maxLifetime`.

### Writable language catalogue upgrades

- Existing shipped `uk/en/bg` external catalogues are augmented with only missing keys from the current bundled defaults during application startup.
- Existing user-edited values and custom keys are preserved.
- Intentionally removed shipped language files are not recreated after first run.
- Invalid user-edited catalogues are never overwritten by the upgrade path; normal language diagnostics remain authoritative.

## Acceptance added

- application fallback-aware archive materialization contract;
- real ZIP compatibility resolution with a server-renamed FB2 member;
- Reader UI source contract preventing direct literal archive re-read;
- SQLite seconds/milliseconds/microseconds/ISO timestamp parsing;
- Hikari leak threshold/max-lifetime relation;
- shipped-language upgrade merge preserving user values while adding the Reader error key.

## Validation policy

No tests are run until all Iteration 43 source/docs/test changes are frozen. Final validation then runs targeted acceptance first, followed by affected module regressions, static/architecture/localization gates, reactor `test-compile`, and Maven-free source packaging.
