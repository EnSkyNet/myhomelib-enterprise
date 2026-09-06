# Continuation brief — 7.1 Final external acceptance

## Goal

Close the six remaining externally evidenced 7.1 Final items without manufacturing PASS:

- GitHub: **MHL-010, MHL-017, MHL-018, MHL-019**.
- Windows: **MHL-011, MHL-012**.

## GitHub — one connected acceptance run

A single workflow now collects the repository-side evidence:

```text
GitHub Actions → GitHub connected acceptance → Run workflow
```

Defaults:

- minimum successful PR Fast gate samples: 5;
- Fast gate median limit: 600 seconds;
- CodeQL maximum analysis age: 14 days;
- latest successful `ci-release.yml` is used unless `release_run_id` is supplied.

The workflow must PASS all of the following:

1. default branch requires `Fast gate`;
2. real hosted Fast gate median <=10 min;
3. successful release artifact contains valid CycloneDX 1.6 JSON/XML BOMs;
4. release artifact contains real Dependency-Check JSON/SARIF/HTML evidence;
5. recent default-branch CodeQL analysis exists with rules executed;
6. no open High/Critical code-scanning alert exists.

Authoritative evidence:

```text
target/github-connected-acceptance/github-connected-acceptance.json
target/github-connected-acceptance/github-connected-acceptance.md
```

After a real `Overall: PASS`, update MHL-010/MHL-017/MHL-018/MHL-019 in the backlog with the run URL/id and evidence artifact name.

## Windows — unchanged final acceptance

MHL-011/MHL-012 still require a real interactive Windows host/VM. Follow `CONTINUATION-ITERATION-16-WINDOWS-ACCEPTANCE.md` exactly:

- standard/non-elevated disposable user;
- a real previous-release MSI for final upgrade evidence;
- portable Unicode + spaces isolation smoke;
- DPI 100/125/150/200%;
- all 20 P4 screenshot-backed checks per scale;
- strict evidence validator;
- `windows-final-evidence-pack.ps1`.

## Final 7.1 release gate

Only after both connected GitHub PASS and real Windows PASS:

```powershell
.\mvnw.cmd -B -ntp clean verify -Pproduction
python tools\static_release_check.py
python tools\stage23-cross-platform-release-check.py --dist dist --require-checksums --require-portable --expect-installer
```

Then reconcile all six external backlog rows and create the final non-WIP 7.1 checkpoint.

## One final external decision

After the live GitHub artifact has been placed under `target/github-connected-acceptance/` and the real Windows evidence remains under `target/`, run on the Windows acceptance host:

```powershell
python tools\v71-final-external-acceptance-check.py `
  --windows-root target `
  --windows-archive target\windows-final-acceptance-evidence.zip `
  --github-json target\github-connected-acceptance\github-connected-acceptance.json
```

Do not mark the six external backlog items completed unless this command reports:

```text
MyHomeLib 7.1 final external acceptance: PASS
```

Keep the generated `target/v71-final-external-acceptance/` JSON/Markdown together with the GitHub and Windows evidence bundles.
