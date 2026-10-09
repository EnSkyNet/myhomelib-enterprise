# MyHomeLib — Technical / Architecture / Backlog Audit V25

Дата: 2026-09-29  
Continuation baseline: clean V24 checkpoint.  
Product: MyHomeLib Enterprise 8.0.0.

## Висновок

V25 є consolidation checkpoint: перевірено всі наявні historical `NEXT-TASKS`/handoff backlog-и, включно з окремо наданим extended V10 contract; локально виконувані вимоги або закриті доказами, або свідомо залишені без production change, якщо не було red regression/A-B evidence. Реально відкритими лишаються Windows/connected/live gates.

## Підтверджені локальні зміни

1. Library Organizer: explicit metadata-support classification для всіх recognized extensions; ODT/PDF/MOBI-family/comic metadata adapters; recursive mixed-library acceptance; multi-book ZIP; apply/rerun/collision/rollback evidence; surrogate-safe archive-entry filename boundary.
2. Converter naming: підтверджено existing public filename contract без UUID suffix у normal path та non-destructive deterministic collision naming.
3. UI interaction coverage: 447/447 current JavaFX actions (262 FXML + 185 programmatic) мають reviewed automated evidence + source fingerprint.
4. Documentation completeness: regression-first додано `user-functions-documentation-check.py`; 447/447 JavaFX actions мають `user_function_ref` до canonical documented flow.
5. Architecture: guards поширено на 16/16 production/runtime modules; виправлено два hidden transitive compile edges; Backup/Restore native directory selection переведено на existing `FileChooserService` boundary.
6. Documentation/tree cleanup: descriptive historical files прибрано з root/release-active locations у `docs/history`; active docs класифіковано під `docs/current`; README, ARCHITECTURE, development/release/validation/history/task closure оновлено до V25.

## Exact-current automated evidence

- full successful Maven 3.9.16 `clean verify` після production/test Java changes: **1842 tests / 0 failures / 0 errors / 17 skipped; 20/20 reactor projects SUCCESS**;
- UI: 248 tests; E2E: 15 tests; Architecture tests: 19 tests;
- functional audit: 29 FXML, 262 bindings, 244 unique handlers, 14/14 CoreActions, 25/25 workspace help routes, 185 programmatic `setOnAction`;
- UI action registry: 447/447; documentation links: 447/447;
- static release, architecture, architecture-module coverage, architecture regression, Backup/Restore chooser boundary, catalog lifecycle, standalone Java smoke, Stage 8+9/24/25C, supply-chain: PASS;
- external acceptance readiness with offline regressions: `READY_FOR_LIVE_EVIDENCE`.

Після останнього успішного full `clean verify` production/test Java source не змінювався; фінальні зміни стосувалися Markdown/JSON/Python release/documentation gates. Спроба повторного cold `clean verify` після цих doc/tool-only edits була обмежена wall-time інструмента до завершення reactor і не рахується ні PASS, ні FAIL; Maven-релевантний source state відповідає попередньому успішному full run, а Python/doc gates перевірені окремо.

## Реальні supplied-data probes

- Flibusta INPX: **707154 imported / 0 errors**; DB 1,078,251,520 bytes; core import ~77.771 s; `quick_check=ok`. Timing — observational, не SLA/A-B claim.
- supplied FB2 corpus: 3/3 reader probes PASS; large sample 17,736,920 bytes / 7,756,433 text chars / 49,010 paragraphs / 17 chapters.

## Архітектурне рішення

Глобальний redesign не виправданий: hard-layer scan і 16-module architecture guards green, dependency graph acyclic, а відомий UI debt ratcheted. Внесено тільки три concrete architecture corrections, для яких був ownership/guard evidence. Масовий chooser/executor/controller rewrite відхилено як overengineering без regression.

## Відкритий залишок

Детально: `docs/release/WHAT-REMAINS-UA.md`. Ключове: Windows Calibre >5 min/cancel/process-tree/file-lock/batch-concurrency; Windows installer/portable/DPI/DPAPI; candidate-bound GitHub/SBOM/SCA/CodeQL; real device/system/provider/live endpoint acceptance; signing/distribution.
