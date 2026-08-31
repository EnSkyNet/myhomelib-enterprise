# Flibusta / MyHomeLib online catalog update fix (2026-08-26)

## Root cause

The Java build previously treated the value entered in **Update collection from network** as if it were always a direct INPX file URL.
For `https://alex80.github.io/mhl/download/inpx/` that is wrong: this address is the **INPX server root**, and the HTTP response for the root itself is an HTML page. Saving that HTML as `catalog-*.inpx` caused `java.util.zip.ZipException: zip END header not found`.

There was a second error-amplifier: `InpxReader.count()` converted an invalid ZIP/INPX into a negative/empty result, so the update path could report a successful import of 0 books and still rebuild the Lucene index. Invalid remote archives must fail before any database/index mutation.

## MyHomeLib 2.5 principle reproduced

The original MyHomeLib separates two roles:

1. **INPX server** — complete baseline used when a collection is created/unversioned.
2. **Update server** — version markers and update packages for existing collections.

The alex80 layout used by this Java build is:

- INPX server: `https://alex80.github.io/mhl/download/inpx/`
  - `flibusta_online_fb2.info`
  - `flibusta_online_fb2.ver`
  - `flibusta_online_fb2.inpx`
- Update server: `https://alex80.github.io/mhl/update/`
  - `flibusta_online_fb2.info`
  - `flibusta_online_fb2.ver`
  - `flibusta_online_fb2.zip` — full update/snapshot
  - `extra_flibusta_online_fb2.info`
  - `extra_flibusta_online_fb2.ver`
  - `extra_flibusta_online_fb2.zip` — incremental/delta update

For each Java collection we persist an analogue of MyHomeLib `DataVersion` under:

`collection.<collection-id>.catalogVersion`

### Selection algorithm

- No local version: download complete `flibusta_online_fb2.inpx`, then apply a newer `extra` package if available.
- Local version older than the update server's full version: apply `flibusta_online_fb2.zip`, then a newer `extra` package.
- Local version already at the full baseline but older than `extra`: download only `extra_flibusta_online_fb2.zip`.
- Local version is current: do not download/import/rebuild the index.
- The local version is advanced only after a package imports successfully.

## Critical delta semantics

`extra_flibusta_online_fb2.zip` is a delta. It is **not** treated as a complete catalog snapshot.
Therefore books absent from `extra` are not marked missing/deleted.
Only full INPX/full update imports enable the full-snapshot missing-book reconciliation.

## Download validation

Before the importer receives a remote file, the downloader now:

- requires HTTP 2xx;
- rejects `text/html`;
- checks the downloaded payload for an HTML signature;
- opens it as ZIP;
- verifies that at least one `*.inp` entry exists;
- reads `version.info` when present;
- deletes invalid temporary files and aborts the update.

This prevents a directory/error page from becoming a fake `.inpx` file.

## Other related fixes

- `InpxReader.count()` now fails on damaged/non-INPX archives instead of silently converting them into a zero-book successful import.
- Full and delta packages use the same stable source identity `remote-collection:<collection-id>`.
- Lucene is rebuilt once after all selected packages are imported; if a later package fails after an earlier package committed, an index rebuild is attempted before the error is propagated.
- Online import uses batch size 5000.
- Blank/invalid ISBN values read from imported data are mapped to `null` instead of throwing `Invalid ISBN format` when opening an author/book view.
- Update progress covers both download and import phases.

## First update after upgrading to this build

Older Java builds did not persist a MyHomeLib-compatible catalog version. Therefore an already-populated remote collection may perform one full baseline synchronization the first time it is updated with this build. After the version is stored, subsequent checks should normally download only the newer `extra` package (or nothing if already current).

Use this server value in the update dialog:

`https://alex80.github.io/mhl/download/inpx/`
