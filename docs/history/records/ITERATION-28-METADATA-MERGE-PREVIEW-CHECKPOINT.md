# Iteration 28 — Metadata merge preview checkpoint

Date: 2026-09-07
Backlog item: MHL-110 — Metadata merge preview
Status: DONE (local implementation/evidence)

## Delivered

### Application boundary

- Added pure `MetadataMergePreviewService` for `Current → Proposed` projection with no persistence side effects.
- Supports both single preview and batch preview requests.
- Review fields: title, authors, ISBN, year, publisher, language and annotation.
- Every preview carries provider source attribution and confidence from the selected `MetadataCandidate`.
- Added `ApplyMetadataCandidateUseCase`: only explicitly selected fields are changed.
- Empty selected-field set returns the current book and performs no mutation.
- Selected metadata updates preserve genres, series, file/storage data, artifacts/preferred artifact, cover and user state (keywords, review, rating, progress and other local metadata).
- ISBN uses validated `Isbn`; language passes through the domain `LanguageResolver`.

### UI boundary

- Added `MetadataMergeUiService` and `MetadataMergePresenter`.
- Existing Classic edit dialog now exposes the reachable `Онлайн-метадані…` action.
- Online lookup/load is isolated behind application `MetadataReviewService`; the new UI service does not depend on the non-value domain `Book` aggregate.
- UI displays Current and Proposed values, source and confidence.
- Every changed field has its own checkbox; checkboxes start unselected.
- `Застосувати вибране` stays disabled until at least one field is explicitly selected.
- Cancel/close returns no selection and never calls the apply use case.
- The same review contract accepts one or multiple book previews; the batch output is reusable by MHL-111.

## Verification

Final targeted MHL-110 suite:
- application metadata merge/review tests: 6 PASS;
- UI presenter/contract/Classic-entry tests: 5 PASS;
- total targeted: 11/11 PASS, 0 failures/errors.

Pre-check full affected-module reactor (before the last two contract-test additions):
- shared: 12 tests;
- domain: 12 tests;
- application: 155 tests, 1 skipped;
- reader: 40 tests;
- UI: 49 tests;
- total: 268 tests, 0 failures, 0 errors, 1 skipped;
- BUILD SUCCESS.

All 9 architecture/static/security gates PASS after the production implementation:
- architecture baseline intact; UI non-value domain-model debt improved to 24/28 baseline;
- managed executor gate PASS;
- offline static release PASS;
- supply-chain policy PASS;
- implementation completeness PASS (0 TODO/FIXME, 0 unused imports/dependencies, 0 exact cross-file clones >=180 chars);
- UI function reachability PASS;
- user-data consistency PASS;
- user-state/search-index PASS;
- SecretStore policy PASS.

Evidence:
- `/mnt/data/iteration28-metadata-merge-targeted-final.log`
- `/mnt/data/iteration28-noninfra-reactor.log`
- `/mnt/data/iteration28-static-gates-final.log`

## Infrastructure scope

MHL-110 changes only application/UI code. The known Iteration-27 long-suite infrastructure/Lucene order issue remains tracked separately and is not represented as an MHL-110 failure.

## Next

MHL-111 — Batch metadata editor.
