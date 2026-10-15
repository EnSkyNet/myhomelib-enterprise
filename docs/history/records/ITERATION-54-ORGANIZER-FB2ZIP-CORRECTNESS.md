# Iteration 54 — Organizer FB2.ZIP correctness / 2026-10-09

## User-reported regression

Library organization generated `Что-то кроме магии.fb2zip` and `Затмение.fb2zip` instead of the interoperable `.fb2.zip` compound suffix. Source: `Зауваження 4.docx`, embedded in the supplied diagnostics ZIP.

## Root cause and changes

1. `CanonicalBookPathService.safeExtension`: converter/format identifier `fb2_zip` was sanitized by removing punctuation, producing `fb2zip`. Recognize `fb2_zip`, `fb2zip`, and `fb2.zip` as the canonical filename suffix `fb2.zip`; retain the existing restrictive sanitation for all other tokens.
2. `PlanLibraryOrganizationUseCase.collisionTarget`: append collision hashes before the entire `.fb2.zip` extension (not just `.zip`). No overwrite or collision-check semantics changed.
3. `ApplyLibraryOrganizationUseCase.extension` and `fileStem`: preserve `.fb2.zip` for temporary staging and derive book titles without the trailing `.fb2` portion of this compound extension.
4. `AnalyzeLibraryOrganizationUseCase.formatExtensionMismatch`: treat the legacy `.fb2zip` extension as compatible with actual `fb2_zip` content, avoiding a false format-mismatch warning.
5. Added `Fb2ZipOrganizerNamingRegressionTest`: ZIP signature / single-FB2 metadata, original-format policy, both legacy spellings, compound output suffix, exact name collision and no overwrite; does not mutate input.

## Validation

- Java 21.0.12.1, Maven 3.9.16, offline dependency repository furnished by user.
- Organizer module and its reactor upstream dependencies, selected 15 organizer test classes: **47 tests passed, 0 failures, 0 errors, 0 skipped**.
- Full 21-project Maven reactor: `mvn -o -DskipTests test`: **21/21 modules compiled, BUILD SUCCESS**. This does **not** mean all project-wide tests ran.
- `python tools/architecture-check.py`: PASS, architecture baseline intact.
- `python tools/architecture-check-regression-test.py`: PASS.
- Provided two `.fb2.zip` book archives each have one FB2 member and pass ZIP CRC integrity checks; original books remain untouched.
- No Windows JavaFX GUI acceptance performed in this Linux environment.

## Performance review of user diagnostic log (2026-10-09)

- `COVER_START` / `COVER_DONE`: 159 each; multiple requests for identical book IDs occur. The cover subsystem has a same-request guard and cached extraction paths, so these records alone cannot prove a duplicate file read or identify the caller. Review triggers and request-key changes before altering UI lifecycle.
- One logged FB2→EPUB conversion: FBC step 2,270 ms; EPUB post-processing 28,587 ms; total 32,143 ms. EPUB post-processing reported 1.49% size reduction, EPUBCheck/reader re-open successful. No before/after performance benchmark is available; optimizer behavior was not changed because its validation/semantic safety gates cannot be relaxed based on one case.

## Residual verification work

- Run the complete test reactor on a Windows/CI host, including JavaFX/UI and conversion pipelines.
- Inspect EPUB optimizer per-stage timings on representative sample files before attempting risky hot-path shortcuts or downgrading strict verification.
- Verify organizer UI end-to-end on original libraries (preview, apply, rollback) after backing up user data.
