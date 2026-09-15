# MyHomeLib 8.0.0 / Iteration 85 — post-audit fixes and validation

Date: 2026-09-15

## Implemented fixes

1. Added shared `ProcessExecutionSupport` with concurrent bounded stdout/stderr draining, timeout handling and descendant termination; migrated TTS, command-backed SecretStore and OS power probes to it.
2. Changed supported-platform power-probe failures to a conservative battery state so heavy indexing does not start when power state is unknown.
3. Moved long-running JavaFX command/batch/copy/export work off the FX thread and preserved UI-owned state through safe snapshots/callbacks.
4. Hardened OPDS/Web access: non-loopback OPDS requires authentication by default; web responses add `nosniff`, `DENY`, `no-referrer` and CSP/frame-ancestor protections.
5. Added configurable online download limits and free-space guards for both direct HTTP and ConnectionScript execution, including streamed/chunked overruns and partial-file cleanup.
6. Removed per-resource Reader `deleteOnExit()` use in favor of explicit session cleanup.
7. Added MCP SQLite schema-shape validation before queries.
8. Pinned GitHub Actions to immutable commit SHAs and added Dependabot for Maven and Actions.
9. Added JDK 21+ / Maven 3.9.6+ checks to build launchers.
10. Reduced `MainController` and `LuceneSearchService` below existing project ratchets by extracting focused services.
11. Updated release identity/documentation to the exact hardened Iteration 85 candidate.

## Final local validation

- Maven 3.9.6 / OpenJDK 21.0.11 / `C.UTF-8`, offline repository.
- Full `clean verify`: **16/16 reactor projects BUILD SUCCESS**.
- Tests: **1,097**, failures **0**, errors **0**, skipped **12**.
- Display-capable JavaFX: **8/8 tests**, **6/6 suites**, skipped **0**.
- Real INPX (`flibusta_online_fb2.inpx`): **707,154/707,154 imported**, errors **0**, 96,218 ms, 7,349.50 books/s.
- Real FB2 corpus: **2/2 supplied archives PASS**.
- Linux `jpackage` app-image smoke: PASS.
- Extracted Linux portable archive smoke: PASS.
- Release checksums: PASS, 3 artifacts.
- Stage 23 artifact validation with required portable/checksums: PASS.
- Architecture, implementation-completeness, security/static, supply-chain, startup, persistence, archive/XML, INPX and UI policy gates: PASS.

## External / deliberately unresolved release gates

The following are not claimed locally because they require external credentials, services, signing identities or Windows hosts:

- Windows TTS/DPAPI runtime acceptance;
- Windows DPI 100/125/150/200 visual acceptance;
- MSI/EXE install/update/uninstall under a standard Windows user;
- live GitHub required-check evidence;
- live SBOM/SCA/CodeQL evidence bound to the exact release SHA;
- installer/code signing and provenance tied to real signing credentials.

Dependency major/minor migration (Spring Boot/JavaFX/sqlite-jdbc) was not performed in the offline remediation pass because the supplied repository contains the candidate's current dependency set. Such upgrades must be done in a separate dependency-update branch with the new artifacts available and the same full validation matrix.
