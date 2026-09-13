# Iteration 35 — MHL-201 / MHL-202 annotation foundation

## Scope
- **MHL-201** Annotation domain model.
- **MHL-202** Persistence, migrations and portable backup/restore for annotations.
- MHL-203 Reader selection/highlight UI is intentionally deferred to the next iteration.

## Implementation
- [x] Renderer-independent `Annotation`, `AnnotationType`, `AnnotationAnchor`.
- [x] Stable anchor data: book/artifact/chapter/paragraph, absolute offsets, normalized position, quote + prefix/suffix.
- [x] Explicit artifact switch rule: exact offsets require an exact binding match; unbound anchors cannot silently reuse offsets for a concrete artifact.
- [x] Pure quote/context relocator for layout/text-offset shifts inside the same artifact.
- [x] Serializable immutable model and domain validation; highlights require a non-empty selected quote.
- [x] Application `AnnotationRepository` port and `AnnotationService` lifecycle boundary, including note/color/tag updates and explicit re-anchor.
- [x] V57 tables/indexes: `annotations`, `annotation_anchors`, `annotation_tags`.
- [x] Transaction-bound SQLite save/delete with cascade cleanup.
- [x] Portable user-data manifest **schema v4** with `annotations` and `annotationTags`; export/import results report annotation counts.
- [x] v1-v3 restore compatibility retained.
- [x] Portable restore validates artifact binding against the target logical book; absent/foreign binding becomes unbound rather than wrong.
- [x] Repeated portable restore replaces annotation tags exactly and removes stale local tags.
- [x] Backup help and versioned-user-data source gate updated.

## Acceptance tests authored (run only after all changes are complete)
- [x] Domain serialization/invariant tests.
- [x] Exact + shifted quote/context relocation tests.
- [x] SQLite restart roundtrip/cascade test.
- [x] Large 5,000-annotation book-scoped read regression.
- [x] Flyway V57 migration matrix update.
- [x] Portable schema-v4 annotation/tag roundtrip + idempotence + foreign-artifact safety.
- [x] Stage 35 static source gate.
- [x] Portable backup E2E journey follows `UserDataTransferPort.CURRENT_SCHEMA_VERSION` instead of a stale hardcoded schema value.

## Out of scope
- Reader context actions Highlight/Note, selection handles and visual rendering (MHL-203).
- Annotation Manager workspace/edit/delete undo/export (MHL-204/MHL-205).
- Cross-device revisions/tombstones/conflicts (MHL-401+).

## Validation policy
- [x] All implementation and documentation changes were completed before the final test cycle.
- [x] Final validation is run on the frozen tree; infrastructure is split into bounded test-class groups to avoid runner timeout without omitting classes.

