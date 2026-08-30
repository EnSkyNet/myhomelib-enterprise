# MyHomeLib Enterprise v7.1 — Online Library / ConnectionScript

## Compatibility target

The behavioral reference is MyHomeLib 2.7 (`Program/DwnldImpl/unit_Downloader.pas`). v7.1 implements the scenario behavior independently in Java and deliberately applies stricter validation/security where the legacy application was permissive.

## Grammar

One command is written per line. Blank lines are ignored. Command names are case-insensitive in the Java parser and are normalized to uppercase.

Supported commands:

- `GET <url>` — HTTP/HTTPS request using the shared cookie/session/network policy;
- `ADD <name> <value>` — appends a multipart field to the scenario form state;
- `POST <url>` — sends accumulated multipart fields; form state lives for the scenario, matching the upstream downloader lifetime;
- `CHECK` — validates the most recent payload;
- `REDIR` — requires that the previous request produced a redirect result;
- `PAUSE <milliseconds>` — cancellable delay, limited to 0…60,000 ms.

Unknown commands, missing/extra parameters and malformed `PAUSE` values are validation errors. CR/LF/NUL control characters in command parameters after macro expansion are rejected.

## Macro model

Collection macros include `%URL%`, `%USER%`, `%PASS%` and `%RESURL%`. `%RESURL%` is the final response URI of the previous request and is available to subsequent commands.

Book/path aliases include `%ID%`, `%LIBID%`, `%FILE%`, `%FILENAME%`, `%FOLDER%`, `%ARCHIVE%`, `%ARCHIVEENTRY%`, `%EXT%`, `%COLLECTIONROOT%`, plus title/series/language/ISBN/publisher/year/rating/progress and other string/integer fields exposed by the Java book DTO. `%INSIDENO%` is retained as a compatibility macro with value `0` because the Java model represents the archive member by name rather than the legacy numeric field.

Replacement is deterministic and one-pass: tokens are matched in the original template, replacements are inserted as data, and replacement text is never rescanned. A password containing `%URL%` therefore cannot trigger nested substitution. Unknown macros remain literal so unsupported upstream fields fail visibly rather than silently becoming empty commands.

`%PASS%` is never written back into a persisted script, queue record, diagnostic text or generated error. It is decrypted only into the in-memory operation context.

## HTTP/session behavior

The same `OnlineHttpPolicy` is used for online catalog and book traffic:

- Java HTTP client with normal JVM TLS verification;
- redirect following with final response URI retained;
- cookie manager/session;
- connect/read timeout;
- cancellable body streaming;
- GET retry with bounded exponential backoff for transient status/transport failures;
- configurable User-Agent;
- system/direct/HTTP proxy;
- encrypted proxy credentials;
- optional explicit JKS/PKCS12 trust store; no trust-all mode.

Basic Auth is added when the collection has a user credential. Sensitive request values are not included in propagated network exception text.

## CHECK and commit semantics

A successful HTTP status alone is not a successful book download. Validation rejects at least:

- empty payload;
- HTML/login page;
- obvious text error payload;
- malformed/empty archive;
- archive that does not contain the requested member;
- empty requested archive member;
- FB2 without a FictionBook root marker.

The commit order is: download → semantic validation → archive/member validation → atomic replace → storage/local-state persistence. If validation fails during forced refresh, the previous local file remains available and `local=true` is not granted to invalid bytes.

## Resume and queue

The persistent queue contains credential-free request state. A stale process-level `IN_PROGRESS` item becomes `PENDING` after restart. COMPLETED is not automatically downloaded again; FAILED can be retried; CANCELLED is not automatically restarted without policy/user action.

`.part` resume is protected by source identity and HTTP validator metadata. Resume sends `Range` and `If-Range`, requires a matching `Content-Range` start, and restarts cleanly if the server ignores or invalidates the range. Semantic-invalid partial content is deleted; a transport interruption may keep a validated resumable partial.

## `collection.info`

The codec preserves the upstream field order:

1. Name
2. file
3. type
4. Notes
5. URL
6. ConnectionScript (may span the remaining lines)

Export/import is round-trip oriented. A trusted source can seed URL/notes/script when creating a new collection. A manual update uses the preserve/merge trust policy and must not overwrite local URL, ConnectionScript, login, encrypted password, user-modified notes or local root/path configuration without explicit intent.

## Test contract

The source tree contains parser/macro/embedded-HTTP tests for GET/POST/ADD, multiple fields, REDIR/`%RESURL%`, Unicode, malformed commands, timeout/cancellation, HTTP auth errors, invalid payloads and secret non-leakage. These tests must be executed by `./mvnw clean verify -Pproduction`; the current isolated environment cannot resolve Maven Central, so source presence is not reported as runtime PASS.

## Archive integrity mode

`online.archive.highReliabilityValidation=true` enables an opt-in full integrity scan for ZIP/FB2ZIP/CBZ/JAR payloads after a new download. It rejects case-insensitive duplicate entry names, reads each entry to EOF, checks declared uncompressed size and CRC, rejects empty/invalid FB2 entries and verifies the expected archive entry. The default remains off to avoid repeatedly scanning large archives; this validation is tied to download validation rather than application startup.
