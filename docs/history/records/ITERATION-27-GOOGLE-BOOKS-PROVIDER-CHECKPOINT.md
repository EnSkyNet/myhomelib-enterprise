# Iteration 27 — Google Books metadata provider checkpoint

Date: 2026-09-07
Backlog item: MHL-109 — Google Books provider
Status: DONE (local implementation/evidence)

## Delivered

- Added production `GoogleBooksMetadataProvider` behind the same `MetadataProvider` SPI.
- Supports Google Books volume search by ISBN/title/author with deterministic result mapping/ranking.
- Maps title, authors, publisher, published year, ISBN-10/ISBN-13, language, annotation/description, cover URL and record attribution.
- Provider is disabled by default until `metadata.googleBooks.apiKey` is configured.
- API key is transmitted in the `X-Goog-Api-Key` header rather than embedded in request URLs.
- Adds bounded Caffeine cache and configurable application-local throttling.
- Handles 429/Retry-After, 408/504, 400/401/403, 5xx, invalid JSON, timeout and cooperative cancellation without leaking remote response bodies.
- Extracted shared stateless HTTP/ISBN/normalization mechanics into package-level `MetadataProviderSupport`; no cross-provider clone debt remains.

## Verification

- Google Books targeted tests: 13/13 PASS.
- Open Library + Google Books adapter suites: 22/22 PASS.
- Non-infrastructure reactor (shared/domain/application/reader/UI): BUILD SUCCESS, 262 tests, 0 failures, 0 errors, 1 skipped.
- All 9 local architecture/static/security gates: PASS.
- Final static release checkpoint at this iteration: 996 Java sources / 215 test sources before later MHL-110 additions.

Evidence:
- `/mnt/data/iteration27-metadata-providers-targeted2.log`
- `/mnt/data/iteration27-noninfra-reactor.log`
- `/mnt/data/iteration27-static-gates-final.log`

## Infrastructure-suite caveat

A monolithic infrastructure run is not claimed as PASS for this iteration. The pre-existing `LuceneClassicSearchCompatibilityTest` can hang when executed after earlier Lucene tests in the same long-lived fork, while the class passes standalone (4/4). Runs excluding that class progressed further and then reached the execution-window limit. There was no metadata-provider test failure in these runs. MHL-108's immediately preceding full reactor, including infrastructure, was green with 291 infrastructure tests.

Evidence:
- `/mnt/data/iteration27-lucene-flake.log`
- `/mnt/data/iteration27-final-reactor.log`
- `/mnt/data/iteration27-final-reactor2.log`
- `/mnt/data/iteration27-split-reactor-excluding-lucene-classic.log`

## External specification references used

Implementation follows the official Google Books API query model (`volumes?q=...`, ISBN/title/author operators, relevance ordering, max-results bounds) and Google Cloud API-key transport guidance. These references document the API contract only; project-local throttling values are application safeguards, not claims about Google quota.

## Next

MHL-110 — Metadata merge preview.
