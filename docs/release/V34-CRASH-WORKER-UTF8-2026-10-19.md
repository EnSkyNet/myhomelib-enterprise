# V34 Windows crash-worker diagnostic encoding

Base: `37dbc2f450a42754cecfef35ab3f67735aad8ced`.

A full isolated Windows 11 `clean verify` for PR #25 source `252f7067` failed in
SqliteOrganizerApplyCrashIntegrationV27Test while reading crash-worker.log:
`MalformedInputException: Input length = 3`. The child JVM's redirected stdout/
stderr used the Windows native encoding, while Files.readString expected UTF-8.
This masked the journal/manifest recovery assertions after the worker exited.

The child now starts with JDK 21 `file.encoding`, `stdout.encoding` and
`stderr.encoding` explicitly set to UTF-8. It emits a Cyrillic marker on both
streams, and the parent verifies both markers after strict UTF-8 decoding. No
recovery assertion is removed or weakened. The existing three killpoints still
verify exit code 83, SQLite integrity, persisted state, rollback and source SHA-256.
CI explicitly runs this integration test on Windows.

Validation: isolated Windows 11 / NTFS, JDK 21.0.12, Maven 3.10.0,
**1 test PASS, 0 failures/errors/skips**, covering BEFORE_VALIDATED,
BEFORE_COMMITTED and AFTER_COMMITTED. Test time 5.055 s; reactor 43.034 s.

```powershell
./tools/windows-isolated.ps1 -RunRoot RUN -Mode Maven -LocalRepository CACHE `
  -MavenArguments @('-o','test','-pl','myhomelib-infrastructure','-am',
    '-Dtest=SqliteOrganizerApplyCrashIntegrationV27Test',
    '-Dsurefire.failIfNoSpecifiedTests=false')
```

The earlier full run is a FAIL, not a current full-build PASS. It reached the
infrastructure module (847 tests, one error, 16 skipped); downstream modules were
not executed. A new full-reactor run is required. This test-only change does not
provide production GUI/installer acceptance or SCA clearance.
