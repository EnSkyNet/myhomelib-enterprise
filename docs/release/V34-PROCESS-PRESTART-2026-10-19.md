# V34: cancellation before native process launch

Base: `37dbc2f450a42754cecfef35ab3f67735aad8ced` (`origin/master`, fetched 2026-10-19).

`ProcessExecutionSupport.run` previously checked cancellation only after
`ProcessBuilder.start()`. An already cancelled/interrupted task could launch a
converter and truncate redirected output before returning. Three new regression
tests failed on the base: missing executable produced IOException instead of
CancellationException, and both cancellation and interruption erased pre-existing
redirected output. The fix checks interruption/cancellation before native launch.

Windows 11, Temurin JDK 21.0.12, Maven 3.10.0: isolated shared reactor **61 tests,
0 failures, 0 errors, 0 skipped**, including **10 ProcessExecutionSupport tests**.
Maven time 14.840 seconds. Synthetic test paths were on C: (NTFS); the checkout
is on D: (exFAT). The existing isolation harness passed parent/child preflight and
used in-memory Preferences and isolated application data. No user books were used.

Command (use fresh paths for RUN and the existing Maven cache for CACHE):

```powershell
./tools/windows-isolated.ps1 -RunRoot RUN -Mode Maven -LocalRepository CACHE `
  -MavenArguments @('-o','-pl','myhomelib-shared','-am','test')
```

CI now explicitly executes ProcessExecutionSupportTest on Windows as well as in
the existing Linux shared-module gate. CI results are recorded in the PR/Issue #17.

This is not full Windows acceptance. Further probing with a live descendant that
holds a Windows NOSHARE_DELETE handle reproduced transient failure of immediate
cleanup after process exit. That independent cleanup issue remains under
investigation; no arbitrary sleep was added to process termination. The experimental
tests and failed-run evidence were saved outside the committed source.

The initial full-reactor attempt stopped before tests on an uncached Maven plugin
dependency. A subsequent diagnostic run stopped on the experimental cleanup tests.
Neither is a full clean-verify PASS. Historical full-reactor results are not results
for this change. SCA remains blocked on NVD credentials, and GUI/device/installer
acceptance remains outstanding. Release decision: **NO-GO**.
