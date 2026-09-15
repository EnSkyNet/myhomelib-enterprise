# Current validation status

**Current local source candidate:** MyHomeLib 8.0.0 / Iteration 85  
**Date:** 2026-09-15  
**Status:** **LOCAL PASS**

- Maven `clean verify`: 1097 tests / 0 failures / 0 errors / 12 skipped.
- Display-capable JavaFX/Xvfb: 8 tests / 6 suites / 0 skipped / 0 failures.
- Static/release gates: PASS.
- Reader JFR/heap 20/50/100 MB: PASS.
- Real INPX 707154/707154: PASS, 0 errors, 96.218 s, 7349.50 books/s.
- Real FB2 corpus 2/2: PASS.
- Linux portable package + extracted smoke + checksums + Stage 23 artifact validation: PASS.

Detailed evidence:

- `ITERATION-85-TEST-REPORT.md`
- `ITERATION-85-READER-MEMORY-JFR.md`
- `ITERATION-85-CHANGELOG.md`

Windows-native and live-service release acceptance remains external and is not inferred from the Linux/offline result.
