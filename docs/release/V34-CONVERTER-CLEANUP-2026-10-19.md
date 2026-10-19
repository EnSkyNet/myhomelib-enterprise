# V34: bounded cleanup after native converter termination

Base: `37dbc2f450a42754cecfef35ab3f67735aad8ced`; independent of PR #25.

## Reproduction and fix

A synthetic parent JVM spawns a child holding a file with Windows NOSHARE_DELETE.
After cancellation or interruption, both ProcessHandles report terminated, but
immediate deletion can still fail with a sharing violation. Both scenarios failed
on Windows 11 / NTFS. Calibre and external-command converters previously attempted
private-workspace cleanup once, logged the failure, and retained temporary input.

Both converters now use a narrowly scoped cleanup helper after process execution.
On Windows it retries filesystem exceptions every 25 ms within a single two-second
retry budget. Each attempt calls the existing no-link validation and streamed
workspace traversal. It preserves interruption, propagates persistent failure to
the existing warning log, and does not retry generic path-safety violations.
Other workspace cleanup and Organizer fileKey/ownership rules are unchanged.
There is no new executor or unconditional delay. Non-Windows uses the existing
single-attempt cleanup.

Windows can defer lock release after process termination; see
[Microsoft LockFileEx remarks](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-lockfileex).
The retry budget limits retries, not the duration of an individual filesystem call.
Persistent I/O failure retains evidence and is not reported as successful cleanup.

## Validation

Windows 11, Temurin JDK 21.0.12, Maven 3.10.0; existing isolation harness on C: NTFS,
synthetic files only, in-memory Preferences, parent/child isolation preflight PASS.

Targeted reactor: **51 tests, 0 failures, 0 errors, 4 skipped** (47 executed),
56.463 seconds. Five new cleanup tests cover real parent/child cancellation and
interruption, bounded persistent sharing violation with interrupt preservation,
replacement by a file, and rejection of a linked workspace. The Calibre regression
holds a native Windows handle for 200 ms and verifies cleanup still removes the
private input/workspace while cancellation and an untouched destination are retained.

```powershell
./tools/windows-isolated.ps1 -RunRoot RUN -Mode Maven -LocalRepository CACHE `
  -MavenArguments @('test','-pl','myhomelib-infrastructure','-am',
    '-Dtest=ProcessWorkspaceCleanupTest,ProcessExecutionSupportTest,NoLinkPathSafetyTest,SafeFilePublicationTest,CalibreCliBookConverterTest,CalibreExportCapabilityIntegrationTest,ExternalCommandBookConverterSecurityTest',
    '-Dsurefire.failIfNoSpecifiedTests=false')
```

Skipped tests are existing non-Windows shell fixtures. The new native-process and
Calibre cleanup regressions executed. This is not installed-Calibre acceptance.
The new cleanup suite also runs explicitly in the Windows CI filesystem job and
in the Linux shared-module gate (the Windows-only permanent-lock test skips there).

Full Windows application/installer acceptance and SCA remain outstanding. The
separate full-reactor run for PR #25 exposed an existing crash-test diagnostic
encoding failure (`MalformedInputException` reading crash-worker.log), not a
successful full verify. Results of this targeted run are not a release approval.
