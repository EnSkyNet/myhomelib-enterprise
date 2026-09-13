# Iteration 69 — Audiobook support v1 (MHL-508)

Date: 2026-09-13

## Scope

MHL-508 adds first-class MP3/M4B import and an internal desktop audiobook Reader. It reuses the existing book/artifact, reading-progress, bookmark and sync boundaries rather than creating a separate audio library or progress store.

## Delivered

- MP3/M4B declared in the shared format registry and accepted by the generic local importer.
- Internal `AudioDocumentSession` with play/pause, seek, chapter navigation, 0.5–2.0× speed, sleep timer and multi-track progress.
- `AudioPlaybackBackend` abstraction with production `FfmpegAudioPlaybackBackend` using argv-only `ProcessBuilder` for `ffprobe`/`ffplay`.
- Bounded retained diagnostics with full pipe draining, probe timeout and owned child-process termination.
- M4B embedded chapter parsing; files without chapter metadata receive one synthetic chapter.
- Explicit multi-file grouping by `audiobookGroup` and `trackNumber`; untagged alternative MP3/M4B representations are never concatenated automatically.
- Exact resume/bookmark anchor `audio:<millis>:<track>:<chapter>` stored through the existing reading-progress/bookmark repositories.
- Existing reading-progress sync factory/projector preserves the opaque audio anchor unchanged; no database migration is required.
- Localized audiobook Reader controls/messages in EN/UK/BG.

## Validation evidence

- new MHL-508 audiobook acceptance tests: 16/16 PASS
- affected audiobook + registry/format regression set: 21/21 PASS
- full Application: 268 tests, 0 failures, 0 errors, 1 skipped
- `LayerArchitectureTest`: 14/14 PASS
- `tools/architecture-check.py`: PASS (UI domain-debt ratchet improves to 24/28 baseline users)
- `tools/implementation-completeness-check.py`: PASS
- `tools/check-critical-ui-localization.py`: PASS
- `tools/static_release_check.py`: PASS
- `tools/supply-chain-policy-check.py`: PASS
- real Linux media smoke through production backend: generated/probed MP3 + M4B and started/stopped MP3 playback; FFmpeg 7.1.5 in validation environment

## Environment / scope boundary

Windows/macOS `ffprobe`/`ffplay` runtime smoke is not claimed. FFmpeg is not bundled by this iteration. Multi-track grouping currently targets separate local artifacts; automatic grouping of multiple audio entries nested inside an archive is not claimed.

Next planned item: **MHL-509 — integrations**.
