# Continuation — Iteration 29 / MHL-111 Batch metadata editor

Start from the Iteration-28 source checkpoint.

## Completed immediately before this handoff

- MHL-108 Open Library provider — DONE.
- MHL-109 Google Books provider — DONE.
- MHL-110 Metadata merge preview — DONE.
- Metadata preview/apply is explicit and non-destructive by default.
- `MetadataMergeUiService.review(...)` already accepts multiple previews and returns per-book selected field sets; reuse this for batch work instead of creating a second preview contract.

## Next backlog item

MHL-111 — Batch metadata editor.

Expected direction:
- select many books;
- perform bounded/concurrent metadata lookup without blocking JavaFX;
- reuse provider-neutral `MetadataReviewService`/`MetadataLookupService`;
- aggregate per-book proposals and reuse MHL-110 batch review UI;
- apply accepted per-book field selections through an application transaction/batch mutation boundary;
- explicit progress/cancel behavior;
- provider errors stay fail-soft and attributable;
- no metadata field is changed unless selected by the user;
- preserve local user state, artifacts and files;
- add batch limits/backpressure appropriate for large libraries.

## Regression caveat to preserve

Do not claim the Iteration-27 monolithic infrastructure reactor as green: a pre-existing Lucene test-order hang was observed. Provider targeted suites and non-infrastructure reactors are green, and the immediately preceding MHL-108 full reactor was fully green.
