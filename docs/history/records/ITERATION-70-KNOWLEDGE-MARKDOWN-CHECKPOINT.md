# Iteration 70 — Obsidian/Joplin-compatible Markdown integration (MHL-509)

Date: 2026-09-13
Base: Iteration 69 / MHL-508

## Delivered

- Application-owned `KnowledgeMarkdownExportService` reusing the bounded deterministic MHL-205 `AnnotationExportQueryPort`.
- One UTF-8 Markdown file per logical book with configurable folder, file-name and annotation-item templates.
- Optional YAML frontmatter with stable book metadata.
- Optional stable annotation backlink: `myhomelib://book/<bookId>?annotation=<annotationId>`.
- Cross-platform path sanitation, allow-listed placeholders and root-containment checks.
- Collision-safe re-export policies: `REPLACE_MANAGED`, `SKIP_EXISTING`, `FAIL_IF_EXISTS`.
- Default replacement requires an exact ownership marker for the same book id; unmanaged/differently-owned notes fail closed.
- Same-directory staging and atomic publication; cancellation/error removes staging and does not pre-truncate the destination.
- Annotation Manager UI flow for scope, templates, frontmatter, backlinks, policy and destination folder.
- EN/UK/BG localization and export help updates.

## Validation evidence

- `KnowledgeMarkdownExportServiceTest`: 9/9 PASS
- `AnnotationManagerUiContractTest`: 2/2 PASS
- `SqliteAnnotationExportQueryAdapterIntegrationTest`: 1/1 PASS
- full Application: 277 tests, 0 failures, 0 errors, 1 skipped
- `LayerArchitectureTest`: 14/14 PASS
- `tools/architecture-check.py`: PASS
- `tools/implementation-completeness-check.py`: PASS
- `tools/check-critical-ui-localization.py`: PASS
- `tools/static_release_check.py`: PASS
- `tools/supply-chain-policy-check.py`: PASS
- full 16-project offline `test-compile`: BUILD SUCCESS

## Boundary

The export deliberately does not create numbered duplicate notes and does not overwrite user-created Markdown at a deterministic target. Obsidian/Joplin application-level runtime import is not required for this file-format integration and is not claimed.

Next planned item: **MHL-510 — provider-neutral AI extension point**.
