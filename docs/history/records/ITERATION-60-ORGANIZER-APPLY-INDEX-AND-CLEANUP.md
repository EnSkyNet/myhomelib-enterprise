# Iteration 60 — organizer Apply fast path

Date: 2026-10-09. Based on Iteration 59.

## Changes

- `ApplyLibraryOrganizationUseCase`: skip `cleanupCommittedStaging()` for `NOOP`, `KEEP_AS_IS`, `REVIEW_DUPLICATE`, and `SKIP_UNSUPPORTED` because those actions do not stage files. Per skipped file this avoids checking/enumerating three workspace directories (`output`, `input`, `backup`). The overall workspace is still removed after a successful operation; for a failed/cancelled operation recovery evidence is preserved as previously.
- `ParallelStaging`: use immutable `Set<UUID>` to check whether an item is eligible for preparation instead of scanning the full candidate list on every non-staged item. Duplicate staged IDs now fail early with an explicit error.
- Regression tests cover non-mutating staging cleanup and the combination of many skipped books with ZIP extraction.
- Added opt-in benchmark `OrganizerApplySkipBenchmark` (JUnit class name intentionally not ending in `Test`) for repeatable Linux measurements.

## Verification

- Organizer test suite: 64 tests, 0 failures/errors/skips.
- Maven 21-module package with `-Dmaven.test.skip=true`: success (compiles production sources but skips tests/test compilation).
- On-demand benchmark: 4,000 unchanged books, 5 timed repeats after one warm-up, results (milliseconds):
  - Iteration 59 run A: [376, 380, 380, 392, 404], median 380.
  - Iteration 60 run A: [84, 103, 120, 142, 583], median 120.
  - Iteration 60 run B: [81, 93, 97, 113, 152], median 97.
  - Iteration 59 run B: [386, 404, 418, 424, 455], median 418.
- The speedup applies **only** to the measured all-skipped synthetic apply scenario on this Linux host. It is not a Windows/reality or convert/move benchmark.

For the benchmark (from project root, with Maven and dependencies available):

```sh
mvn -pl myhomelib-library-organizer -am -Dtest=OrganizerApplySkipBenchmark \
  -Dsurefire.failIfNoSpecifiedTests=false -Dmhl.benchmark.books=4000 \
  -Dmhl.benchmark.runs=5 test
```

Safety: No hashing, ownership-check, journaling, or conversion security gates were removed for file-mutating actions. Run the new version against a **copy** of the production library first.
