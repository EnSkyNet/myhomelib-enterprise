# INPX import performance/error fixes — 2026-08-26

Observed on a Flibusta INPX import with 562,307 parsed records:

- import throughput degraded as the `authors` table grew;
- Hikari reported an apparent connection leak after 10 seconds even though the import transaction was still healthy;
- author links could be skipped for names containing the `|` character, e.g. `Дамский клуб LADY | переводы`.

## Root causes and fixes

1. **Author lookup bypassed the existing SQLite index.**
   `JdbcBatchWriter.batchInsertAuthorsAndResolveIds()` wrapped `first_name`/`last_name` in `COALESCE(...)`. The database has `idx_authors_unique_name(first_name, last_name)`, so the expression forced increasingly expensive table scans. The lookup now uses raw `first_name = ? AND last_name = ?` predicates so SQLite can use the unique index.

2. **Serialized author-pair keys were delimiter-unsafe.**
   The code built `first|last` and later split at the first `|`. Legal `|` characters inside a name corrupted the lookup. Author pairs are now represented by a structured `AuthorPair` record.

3. **Warning spam for one unresolved author.**
   Missing author keys are now deduplicated and reported once per batch instead of once per book-author link.

4. **False Hikari leak warning during an intentional long transaction.**
   Leak detection was 10 seconds, while a large atomic SQLite import legitimately holds a connection longer. The threshold is now 300 seconds. Import atomicity is preserved.

5. **Catalog creation/network update used only 1,000 rows per batch.**
   Both catalog creation from a source and online catalog refresh now use 5,000-row batches, matching the proven normal INPX import path and reducing flush/tracking overhead substantially for very large catalogs.

## Regression coverage

- added a test resolving `Дамский клуб LADY | переводы` correctly;
- added `tools/inpx-import-performance-check.py`, including an SQLite query-plan assertion that `idx_authors_unique_name` is used;
- `BUILD-CHECK-FIXES.cmd` verifies the hot-path source before Maven verification.
