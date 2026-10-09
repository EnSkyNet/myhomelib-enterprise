# MyHomeLib — технічний та архітектурний аудит V26

Дата: 2026-09-29. Baseline: clean V25 checkpoint.

V26 повторно перевірив historical backlog, user-facing behavior, hidden corruption paths, architecture boundaries і documentation/source hygiene. Масовий rewrite не виправданий.

Підтверджені defects: Book Details Edit no-op; truncated unknown-size MOBI fail-open. Обидва закриті regression-first. Архітектурно завершено native chooser boundary.

Action-specific UI evidence: **447/447** (446 automated + 1 justified). Functional inventory: 29 FXML, 262 FXML bindings, 185 programmatic actions, 0 missing handlers/routes.

Architecture: 16/16 product/runtime modules guarded, direct forbidden UI→Infrastructure/JDBC/Lucene imports 0, native chooser owner 1 (`FileChooserService`), async/executor/sleep gates PASS.

Release-tooling audit additionally reproduced a self-mutation bug: on a pristine tree `build-check-v7.py` spawned a Python behavior gate that created `tools/__pycache__`, then failed its own cleanliness check. Regression-first fix sets `PYTHONDONTWRITEBYTECODE=1` for all child Python gates; the non-mutating regression and clean-stage build-check pass.

Фінальний release-tooling audit також відтворив source-metric contamination: `static_release_check.py` включав `target/generated-sources/**/*.java` у Java/test counts. Після regression-first fix generated `target` виключений, regression не залишає `target` на pristine tree і запускається у Linux CI та formal source-packager. Exact-current static count: **1989 Java sources / 595 test sources**.
External acceptance readiness regression також виявив stale V25 validation tokens (`1842`, `17 skipped`). Contract оновлено до exact-current **V26 / 1960 / 18 skipped** із явними MHL-010/MHL-019 markers; offline readiness regression має знову PASS перед архівацією.

Full clean verify: **1960 / 0 / 0 / 18 skipped; 20/20 SUCCESS**.

Open tasks are external only: Windows Calibre/batch/installer/DPI, connected release evidence and live integrations.
