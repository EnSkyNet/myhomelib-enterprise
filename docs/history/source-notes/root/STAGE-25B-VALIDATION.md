# Stage 25B Validation — Reader Internals Refactor

## Result

**PASS for all available offline/static/regression checks.**

## Structural results

- `ReaderCanvas`: **701 lines** (772 before Stage 25B).
- `TextLayoutEngine`: **366 lines** (638 before Stage 25B).
- `Fb2StreamingParser`: **600 lines** (738 before Stage 25B).
- Extracted helpers:
  - `ReaderSelectionController`;
  - `ReaderPageHistory`;
  - `TextLineLayoutSupport`;
  - `Fb2ParseSupport`.

`tools/stage25b-reader-refactor-check.py` compiles and runs the extracted pure-JDK history, line-layout/hyphenation and FB2 text-support components without Maven.

## Full regression sweep

PASS:

- every Stage 3 through Stage 25B guard;
- Reader Stage 19/20 portable smoke (settings, dictionaries, EPUB anchor contract, selection/copy markers and large-document fixtures);
- OPDS HTTP smoke;
- `large-library-pre-stage7-check.py`;
- `architecture-check.py` — dependency graph and UI debt ratchet unchanged;
- `static_release_check.py` — 38 XML, 25 FXML, 33 SQLite migrations; no FXML handler/migration/static-shell errors;
- language catalogue validation (`uk/en/bg`, schema v2, 110 genre keys each).

## Behaviour/performance contract

Stage 24 remains the performance reference point. Stage 25B intentionally changes class boundaries only: no SQL, parser format, source-offset, reader-position or pagination public contract was changed.

## Maven/JUnit limitation

`./mvnw` cannot download Maven 3.9.16 in this container because DNS/network access to `repo.maven.apache.org` is unavailable. Maven/JUnit execution is therefore not falsely reported as PASS; the added JUnit fixtures are executed by the Stage 23 CI release gate in a networked build environment.
