# MyHomeLib V34 — real-data acceptance evidence

**Дата:** 2026-10-02  
**Candidate:** MyHomeLib Enterprise 8.0.0, V30–V34 post-real-data fixes  
**Середовище:** Linux, JDK 21, Maven 3.9.16 offline, UTF-8, Xvfb для JavaFX.

## 1. Великий реальний каталог INPX

Вхідний файл: `flibusta_online_fb2.inpx`, SHA-256 `75bebb7a7ccf203bd934ef2af986f17d737ba4c4abfc277956f60bb84a6c7655`.

- INPX records: **707 154**.
- Fresh import: **707 154 processed / 707 154 imported / 0 errors**.
- Active books: **571 947**; tombstones/deleted: **135 207**.
- Authors: **166 231**; `book_authors`: **856 591**.
- Genres: **272**; `book_genres`: **1 102 219**.
- Import pipeline: **58.152 s**, ~**12 160 books/s**.
- Deferred SQLite index restore: **14.890 s**.
- Re-import same INPX: **707 154 processed / 0 imported / 707 154 skipped / 0 errors**, **598 ms** — idempotent.
- Current DB size after index/backfill work: **1 594 474 496 bytes**.

## 2. Migration / integrity

- Fresh schema migration: V1 → **V67** PASS.
- Real-size upgrade probe: **V65 → V67**, 2 migrations, **451 ms**.
- During V65→V67 probe: books **707 154**, authors **166 231**, DB size unchanged, `quick_check=ok`.
- Final `PRAGMA quick_check`: **ok** (~14.6 s).
- Final `PRAGMA integrity_check`: **ok** (**40.656 s**).
- Final schema version: **67**.

## 3. Search / Lucene / restart

- Lucene rebuild input: **571 947 active books**.
- Rebuild: **38.599 s**, ~**14 834 docs/s**.
- Index size: **185 039 367 bytes**.
- Peak measured heap during rebuild: **190 316 544 bytes**.
- Representative search queries returned results in ~**11.7–41.6 ms** in the recorded probe.
- Restart/reopen acceptance on 707 154-book DB:
  - first startup orchestration with one-time normalization/backfill: **48.107 s**;
  - background Lucene rebuild completes and freshness is sealed;
  - second startup: **214 ms**;
  - existing **571 947-document** Lucene index is reused; `SearchStartupTask=SKIPPED`; `freshImmediately=true`.

The initial restart probe incorrectly closed only the Spring context and therefore did not match production shutdown ordering. The corrected probe uses the application's real sequence `Lucene close → SQLite close/checkpoint → freshness seal`; the corrected run is PASS. No production freshness workaround was added.

## 4. V33 local recommendations — large-library performance

Real-data testing exposed an unbounded candidate scan. Production was changed to a bounded metadata candidate projection while retaining the deterministic `BookSimilarityPolicy` scoring. Additional correctness fixes include keyword-only candidate projection and normalized series matching.

A second performance pass found that the series predicate did not satisfy the existing partial-index predicate. The SQL now includes the partial-index conditions and uses `idx_books_active_series_normalized`. Candidate bounds were reduced for normal 8–10 result requests without changing the scoring policy.

Latest 5-sample run on the same 1.59-GB DB:

- sample 1: **1 387 ms**;
- sample 2: **329 ms**;
- sample 3: **451 ms**;
- sample 4: **546 ms**;
- sample 5: **572 ms**;
- worst: **1 387 ms**;
- all returned ≤10 results; build PASS.

The Book Details UI loads recommendations asynchronously, so this work does not block the initial rich-details render. Earlier unbounded runs on this database were materially slower (one recorded run ~21.5 s). A later deliberately cold same-process full-scan baseline exceeded the probe timeout and was stopped, so no artificial exact speedup ratio is claimed.

## 5. V34 comic exact-duplicate stress

A generated 1000-image CBZ was used because no real CBZ/CBR/CB7 archive was supplied:

- archive size: **7 319 402 bytes**;
- pages scanned: **1 000 / 1 000**;
- exact duplicate groups: **100**;
- failures: **0**;
- wall time: **6.270 s**;
- measured process max RSS: ~**596 296 KiB**.

Corrupt-entry and cancellation behavior remain covered by deterministic automated tests. A physical/real large CBR/CB7 corpus is still a separate acceptance input because none was supplied in this session.

## 6. Supplied FB2 archives

All three user-supplied FB2 ZIP archives were copied as real files (not symlinks) and passed the opt-in reader probe. The first failed attempt used symlinked fixtures and was correctly rejected by path-safety logic; the corrected real-file run passed.

## 7. Browser / external gates

PWA server/unit integration remains green, but real Chromium acceptance cannot be completed in this environment: Chromium 144 is managed with `URLBlocklist: ["*"]`, which blocks even localhost and produces `ERR_BLOCKED_BY_ADMINISTRATOR`. This is an environment policy, not an application failure.

Still external / unavailable here:

- physical Kobo interoperability;
- real Windows installer/portable/updater/file-lock/signing acceptance;
- real Readwise account/provider behavior;
- unrestricted supported-browser PWA install/offline acceptance;
- real CBR/CB7 stress corpus.

These must not be reported as live PASS until executed in the required environment/device/account.
