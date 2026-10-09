# MyHomeLib — changes V14 -> V15

Дата: 2026-09-28

## Product fixes

### 1. CONVERSION no longer freezes ordinary collection use

`LibraryOperationCoordinator` now permits an active `CONVERSION` shared root to coexist with:

- `READER_STATE`;
- `DOWNLOAD`.

Collection `SWITCH` remains blocked for the whole shared session.

Regression: `runningConversionDoesNotBlockReaderStateOrBookDownload`.

### 2. Background INDEX no longer rejects online downloads

An active `INDEX` root may now accept a `DOWNLOAD` lease. The reverse direction (`DOWNLOAD -> INDEX`) remains blocked intentionally.

Regressions:

- `backgroundIndexAllowsUserDownloadWithoutOpeningSwitchRace`;
- `backgroundIndexDoesNotRejectUserDownloadAtUiSubmissionBoundary`.

### 3. Post-download Lucene repair is non-blocking during full rebuild

`DownloadBookUseCase` now uses the existing managed `ExecutorPort`. When download occurs under an active `INDEX` root:

- physical download and storage commit return on the normal path;
- selective Lucene synchronization is deferred;
- an extra shared `DOWNLOAD` lease keeps the `INDEX` collection barrier alive until deferred repair completes;
- `SWITCH` therefore cannot race the repair into another collection;
- executor rejection falls back to synchronous repair for correctness.

Regression: `DownloadBookUseCaseIndexConcurrencyTest`.

## Verification

- clean production compile: 1 387 sources -> 2 095 classes;
- clean test compile: 549 sources -> 825 classes;
- application: 462 PASS, 1 skipped;
- search: 28 PASS, 2 skipped;
- download: 100/100 PASS;
- exporter: 15/15 PASS;
- UI: 224/224 PASS;
- Reader: 136 PASS, 1 skipped;
- ProcessExecutionSupport: 5/5 PASS;
- static release: PASS;
- XML/archive security: PASS;
- architecture debt: unchanged single group.

## Not changed

- `ProcessExecutionSupport` process-tree code: Windows shutdown handle issue was visible in user log but not deterministically reproducible in current environment.
- Calibre timeout policy: remains default 300 s, allowed range 10..3600 s.
- Conversion user-cancel UX: not added in this focused defect fix.
- Existing architecture debt: not mixed into concurrency fix.
