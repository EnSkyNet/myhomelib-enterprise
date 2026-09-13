# MyHomeLib Enterprise v7.1 — online download audit

Status date: 2026-08-30. Scope: online book payloads, remote INPX/catalog payloads, retry/cancellation/security behavior.

## Confirmed/implemented

- Shared `HttpClient` instances are reused by both book and remote-catalog adapters.
- TLS stays JVM-validated; optional custom truststores are supported; plaintext proxy/truststore passwords are rejected.
- Book downloads are atomic, resumable only with an entity validator, and use `Range + If-Range` with a sidecar bound to the source URI by SHA-256.
- Remote catalog downloads now follow the same anti-splice rule: an existing `.part` is resumed only when a matching sidecar contains ETag/Last-Modified; otherwise the partial file is discarded and restarted from byte 0.
- Remote catalog resume sends `If-Range`; HTTP 200 after a range request safely restarts instead of appending; invalid `Content-Range` discards poisoned partial state.
- HTTP retryability is centralized in `OnlineRetryPolicy` and is explicit (`408`, `421`, `425`, `429`, `500`, `502`, `503`, `504`) instead of retrying every 5xx response.
- Backoff supports exponential delay, bounded jitter, `Retry-After` delta-seconds and RFC-1123 HTTP-date.
- Retry sleep checks cancellation in short intervals.
- UI book downloads already have a configurable global semaphore (`online.maxParallelDownloads`, default 2). Waiting for a slot now polls with a 100 ms cancellation check instead of blocking indefinitely in `Semaphore.acquire()`.
- Duplicate physical archive requests in `HttpOnlineBookDownloadAdapter` are coalesced by normalized target path so two books from one archive do not trigger two physical downloads.

## Tests/evidence present

- `HttpOnlineBookDownloadAdapterTest`: transient 503 retry, permanent 404 no-retry, validator-safe resume, stale partial restart, concurrent archive dedup, cancellation behavior.
- `HttpRemoteCatalogDownloadAdapterTest`: validator-safe `Range + If-Range` resume and stale partial restart.
- `OnlineHttpPolicyTest`: encrypted custom truststore and plaintext credential rejection.
- `OnlineRetryPolicyTest`: explicit retryability, Retry-After seconds/date parsing, jitter bounds.
- Offline release/static/Stage 6 gates pass after the changes. Full JUnit/Maven execution is still required in a connected build environment.

## Still open before final acceptance

1. Run real local HTTP throughput tests for 10 MB / 100 MB / 1 GB payloads and record CPU/RSS/cancellation latency.
2. Add a **shared** per-host/global HTTP limiter that also covers headless/MCP/OPDS paths. The current UI semaphore is global only for UI-triggered book downloads and is not sufficient evidence for all entry points.
3. Measure connection-pool behavior under concurrent archive/catalog requests.
4. Execute proxy integration tests through a real HTTP proxy, not only policy construction tests.
5. Execute a TLS handshake against a server trusted only by the configured custom truststore.
6. Measure cancellation latency while blocked in socket read and while waiting for concurrency permits.
7. Verify progress throttling under high-throughput payloads does not delay cancellation or flood the UI/event listeners.
