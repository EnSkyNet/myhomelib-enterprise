# Stage 23 Validation — Cross-platform CI/release

Date: 2026-08-25

## Result

**PASS for all checks executable in the current Linux packaging environment.**

The actual GitHub-hosted Windows/macOS matrix and native `jpackage` executions require their respective runners and therefore are represented by the committed workflow/scripts here; they cannot be truthfully reported as executed locally. The workflow makes those platform jobs release-blocking.

A local full Maven build remains unavailable because this container has no Maven installation/cached wrapper distribution and no external dependency access.

## Dedicated Stage 23 check

```bash
python3 tools/stage23-cross-platform-release-check.py
```

Result: **PASS**.

Validated:

- workflow YAML parses;
- Windows/Linux/macOS matrix is present;
- all matrix jobs run `clean verify -Pproduction`;
- tagged publication depends on the complete matrix;
- Unix release scripts pass `bash -n`;
- portable `jpackage app-image` archive paths exist for Unix and Windows;
- checksums and GitHub tagged-release assembly are wired;
- `MHL_SKIP_BUILD` preserves the already-verified jar for packaging;
- packaged launcher is invoked with `--release-smoke`;
- smoke branch runs before `Application.launch`;
- smoke verifies main FXML, Flyway migration, help and bundled language resources;
- `ReleaseSmokeCheck.java` compiles standalone with the installed JDK;
- release documentation states the dependency-download-free runtime contract and the separate clean-machine GUI smoke requirement.

## Full regression after Stage 23

All available offline guards passed after changing bootstrap/release scripts:

- static release check: 38 XML, 25 FXML, 161 handler refs, 33 migrations/integrity `ok`, 632 Java sources, 57 test sources;
- architecture baseline and UI debt ratchet;
- large-library pre-Stage7 guard;
- Stage 3 through Stage 22 regression scripts;
- OPDS real loopback HTTP smoke;
- Reader portable smoke;
- language catalogue validation (`uk/en/bg`, schema 2, 200 UI keys, 110 genre keys each);
- Stage 23 dedicated check.

## External release gates

GitHub-hosted CI must still execute before a tagged release is accepted:

1. Maven `clean verify` on Windows, Linux and macOS;
2. native platform `jpackage --type app-image`;
3. native packaged-launcher `--release-smoke`;
4. platform artifact upload and final SHA-256 manifest;
5. an interactive clean-machine GUI smoke before signing/final distribution.
