# R18 V21 — copy-stage observability and late cancellation

## Problem A: missing organizer INFO in production

`myhomelib-bootstrap/src/main/resources/logback-spring.xml` has a `prod` root logger at `WARN`, while the organizer's `ORGANIZER_STAGE`/`ORGANIZER_PREFLIGHT` checkpoints are `INFO`. Without a package override, these milestones are suppressed by ordinary Logback level inheritance.

V21 adds `com.myhomelibcorp.organizer` at `INFO` **only inside the `prod` profile**, leaving root at `WARN`. New `tools/tests/test_organizer_production_logging_v21.py` checks this contract. File appender routing remains unchanged.

## Problem B: staged file after cancellation once copy has finished

In V20, `OrganizerFileIntegritySupport.copyAndVerify` called `NoLinkPathSafety.copyFreshFileVerified` (which closed its `CREATE_NEW` destination), then separately checked cancellation and re-read destination SHA-256 **outside** the low-level cleanup block. A late cancellation left the complete staging file on disk. A negative JUnit regression reproduces this on V20.

V21 keeps the independent destination re-read and a final cancellation checkpoint inside the same ownership-aware `try/catch` as the verified copy. The cleanup still deletes only a regular file whose `fileKey` matches that captured immediately after `CREATE_NEW`. If the filesystem provides no `fileKey`, the code deliberately preserves the artifact rather than guessing ownership. The copy uses bounded reads and SHA-256 of bytes passed to output, then verifies the destination SHA-256 independently. No SQLite migrations or recovery manifest format changed.

## Copy diagnostics emitted through organizer SLF4J

For the move/rename `commitExisting` path V21 logs `ORGANIZER_STAGE stage=COPY_START`, then `ORGANIZER_COPY` milestones `OPENED`, `BYTES` (first successful chunk and no more often than every 5 seconds), `STREAM_DONE`, `VERIFY_TARGET_START`, `VERIFY_TARGET_DONE`, followed by `ORGANIZER_STAGE stage=COPY_VERIFIED`.

Each copy milestone includes `operationId`, `itemId`, `bytesCopied`, and `elapsedMs`. A failed copy logs `ORGANIZER_COPY_FAILED` with the exception and latency. These checkpoints help determine whether a stall occurs before opening the destination, during source read/write, or during target verification. The low-level helper remains JDK-only (no SLF4J dependency) to preserve autonomous `javac` smoke tests.

## Boundaries

- No attempt to forcibly terminate an OS-blocked `read` or `write`: cancellation is cooperative between reads/writes.
- A stream that finishes all writes may still need time to re-read target SHA-256; a 4 GiB source has substantial I/O costs.
- Crash recovery, Windows/NTFS/SMB, antivirus locks, DPAPI, and a reproduction on a copy of the *real* problematic book remain release gates.
- This additional logging is wired to the `commitExisting` action; conversion/extraction paths require their own diagnostics.
- A null `fileKey` requires manual recovery handling; V21 prefers retained evidence over possible loss of somebody else's file.
