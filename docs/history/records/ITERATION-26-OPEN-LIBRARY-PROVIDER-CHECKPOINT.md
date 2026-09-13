# Iteration 26 — Open Library metadata provider checkpoint

Date: 2026-09-07
Backlog item: MHL-108 — Open Library provider
Status: DONE (local implementation/evidence)

## Delivered

- Added production `OpenLibraryMetadataProvider` behind the vendor-neutral `MetadataProvider` SPI.
- Supports ISBN/title/author search and maps title, authors, year, publisher, language, ISBN and cover link into `MetadataCandidate`.
- Preserves provider/source attribution for every candidate.
- Adds bounded Caffeine caching and configurable local request throttling.
- Handles HTTP timeout, cancellation, `429 Retry-After`, remote unavailability and malformed payloads through the common provider-neutral error contract.
- ISBN-10/ISBN-13 equivalence is honored when ranking exact ISBN results.
- No remote metadata is applied automatically to the local catalog.

## Verification

- Open Library targeted tests: 9/9 PASS.
- Recommended shared/domain/application/infrastructure/reader/UI reactor: BUILD SUCCESS.
- Reactor tests: 553 total, 0 failures, 0 errors, 7 skipped.
- Final reactor Maven time: 53.243 s; finished 2026-09-07T19:01:33Z.
- Local architecture/static/security gates: PASS for the MHL-108 checkpoint.

Evidence:
- `/mnt/data/iteration26-openlibrary-targeted.log`
- `/mnt/data/iteration26-final-reactor.log`
- `/mnt/data/iteration26-final-reactor.rc`
- `/mnt/data/iteration26-static-precheck.log`

## External acceptance scope

The historical 7.1 GitHub/Windows live-acceptance gates are unchanged by this checkpoint.

## Next

MHL-109 — Google Books provider.
