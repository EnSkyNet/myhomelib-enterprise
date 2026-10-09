# MyHomeLib — зміни V13 → V14 audit checkpoint

Дата: 2026-09-28

## Product fixes

1. **HTTP resume integrity** — `206 Content-Range: bytes X-Y/*` тепер перевіряє фактичну довжину partial body (`Y-X+1`) перед publication. Усічений chunked resume більше не може бути опублікований як готовий файл.
2. **Unicode metadata truncation** — user-visible plugin metadata більше не розрізає UTF-16 surrogate pair на 16 384-char boundary.

## Regression coverage

- `HttpOnlineBookDownloadAdapterTest.rejectsTruncatedChunkedResumeBodyWhenTotalSizeIsUnknown`.
- `LocalMetadataExtractionServiceTest.truncationDoesNotSplitSurrogatePairInUserVisiblePluginMetadata`.
- `tools/architecture-check-regression-test.py`.

## Tooling fix

`architecture-check.py` Stage-6 UPSERT preservation checks переведено зі старого `JdbcBatchWriter.java` на canonical `BookUpsertSql.java`. Реальний UI architecture debt не прихований.

## Deliberately unchanged

Не змінювалися без RED regression: `SearchWorkspaceController`, `ReaderTextProviderCoordinator`, binary/archive core, SQLite busy/retry framework, converter public filename logic.

## Release status

Offline/static/manual test evidence — green у перевірених наборах. Real Maven 3.9.16 / package / jpackage / Windows acceptance — ще не виконані, тому V14 є **audit checkpoint**, а не release-certified baseline.
