# Iteration 16 — Windows acceptance harness hardening (WIP2)

Scope: MHL-011 + MHL-012.

## Fixed in WIP2

1. **DPI screenshot evidence path**
   - The first WIP returned screenshot paths relative to `target` but validated them from the repository root.
   - A legitimate screenshot could therefore be rejected after the tester selected PASS.
   - Evidence is now resolved relative to the report directory.

2. **Multi-monitor DPI reconciliation**
   - The first WIP could mark `AUTO-0 = BLOCKED` for a `GetDpiForSystem()` mismatch and ask P4-01 to confirm the actual tested monitor, but had no code path to reconcile that confirmation.
   - A screenshot-backed `P4-01 = PASS` can now resolve that multi-monitor diagnostic to PASS.
   - A single-monitor DPI mismatch remains FAIL.

3. **Fail-closed evidence validation**
   - Exact 100/125/150/200 embedded scales are required.
   - `AUTO-0`, `AUTO-1`, and every P4-01..P4-20 row must appear exactly once and PASS.
   - DPI screenshots must be unique, relative, inside the evidence bundle, PNG-looking, and present on disk.
   - Standard-user acceptance requires explicit `requireStandardUser=true` and `isAdministrator=false`.
   - Portable acceptance requires Unicode **and spaces** in extract/home/cwd paths, with distinct path roles.
   - `--require-real-previous` requires an externally supplied previous-release MSI rather than the synthetic `7.0.99` preflight package.

4. **Windows PowerShell compatibility**
   - Windows guards no longer depend on `$IsWindows`.
   - Relative evidence paths no longer depend on `.NET Path.GetRelativePath()`.
   - The manual Windows acceptance harness therefore does not require PowerShell 7 merely for those APIs.

5. **JavaFX CI/runtime-test robustness**
   - JavaFX runtime tests now preflight whether a Linux `DISPLAY` is actually reachable before starting the JavaFX singleton.
   - This prevents a stale `DISPLAY=:0` from poisoning the Surefire JVM and hanging later JavaFX tests.
   - The previous `DISPLAY`-only JUnit gating was removed, so these runtime regression tests are no longer automatically disabled on Windows merely because Windows normally has no `DISPLAY` environment variable.

6. **Regression ratchet**
   - `tools/windows-acceptance-evidence-check-test.py` exercises valid and malformed synthetic evidence bundles.
   - PR CI runs both the Windows harness structure check and the validator regression test.

## Validation completed in this environment

- `python3 tools/windows-acceptance-harness-check.py` — **PASS**.
- `python3 tools/windows-acceptance-evidence-check-test.py` — **PASS**.
- `python3 tools/static_release_check.py` — **PASS**.
- `./mvnw -o -Dmaven.repo.local=/mnt/data/maven-offline-repo -B -ntp -pl myhomelib-ui -am test` — **BUILD SUCCESS**; the UI reactor completes without the previous JavaFX hang.
- `./mvnw -o -Dmaven.repo.local=/mnt/data/maven-offline-repo -B -ntp verify -Pproduction` — **BUILD SUCCESS** for all 13 reactor modules, including architecture and E2E tests.
- `tools/stage23-cross-platform-release-check.py` was not run to completion because this WIP workspace has no final `dist` directory yet; that remains a post-Windows-PASS release gate.

## Remaining P0 acceptance work

This iteration remains **WIP**. This Linux environment cannot provide either of the two authoritative Windows acceptance artifacts:

- **MHL-011:** interactive 100/125/150/200% Windows DPI screenshots and reports;
- **MHL-012:** real Windows installer/upgrade/reinstall/uninstall + portable lifecycle evidence from a standard/non-elevated user, including a real previous-release MSI for final upgrade acceptance.

No Windows PASS is claimed by this checkpoint.
