# Iteration 35 checkpoint — Annotation foundation (MHL-201/MHL-202)

Iteration 35 establishes annotations as user data independent of any JavaFX renderer.

## Domain contract
An annotation is immutable and serializable. Highlights require both a non-empty range and the selected quote text so relocation evidence is never silently absent. Its anchor stores both an exact location and relocation evidence: book/artifact identity, chapter/paragraph hints, absolute text offsets, normalized position, selected quote and bounded surrounding context. Exact offsets are valid only when the current artifact binding exactly matches the stored binding. An anchor that became unbound during portable restore cannot implicitly trust offsets for a concrete artifact; a different or newly chosen artifact must be explicitly rebound. The foundation never silently applies source offsets to another representation.

`AnnotationAnchorRelocator` first verifies the stored range against the quote. If text shifted while the artifact remained the same, it searches exact quote occurrences and scores prefix/suffix context, using normalized position only as a tie-breaker.

## Persistence
Flyway V57 adds `annotations`, `annotation_anchors`, `annotation_tags` and lookup indexes. SQLite save/delete is executed in one collection-bound transaction. Book deletion cascades annotations; annotation deletion cascades anchors/tags. Artifact identity is intentionally not an FK: an annotation may survive artifact replacement and be explicitly rebound later.

## Portable user data
Portable `user-data.json` is schema v4. It adds deterministic `annotations` and `annotationTags` sections while preserving v1-v3 readers. Restore resolves logical books by LibID as before. An exported artifact id is retained only when the same id belongs to the resolved target book; otherwise the annotation is restored without artifact binding to prevent accidental offset reuse. Repeated restore replaces each annotation's tag snapshot, so tags deleted in the backup source do not survive as stale local residue. Export/import result summaries include annotation counts.

## Next dependency
MHL-203 can now consume the application service from Reader/UI and translate live text selection into `AnnotationAnchor`. It should not bypass the application port or write annotation tables directly.

## Final validation — 2026-09-09

- Portable backup E2E schema assertions use `UserDataTransferPort.CURRENT_SCHEMA_VERSION`, preventing the journey test from remaining pinned to legacy schema v2.
- Offline reactor `test-compile`: 13/13 modules, BUILD SUCCESS.
- MHL-201/MHL-202 targeted domain/application: 5 tests, 0 failures, 0 errors.
- MHL-201/MHL-202 SQLite/backup/migration: 13 tests, 0 failures, 0 errors.
- Flyway migration matrix reaches catalog V57.
- Stage 35 annotation source gate, architecture ratchet, implementation completeness and static release checks: PASS.
- The final release validation is repeated after all Iteration 35/36 source and documentation changes are frozen.

