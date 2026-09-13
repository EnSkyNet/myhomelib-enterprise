# MyHomeLib — Iteration 42 checkpoint: external acceptance evidence hardening

**Дата:** 11.09.2026  
**База:** Iteration 41

## Мета

Закрити локальні integrity/privacy gaps у фінальному 7.1 external-acceptance flow, не заявляючи live GitHub/Windows PASS.

## Реалізовано

### Shared bounded ZIP safety

`tools/zip_evidence_safety.py` централізує metadata guards для acceptance ZIP:

- safe normalized relative paths only;
- Windows drive-qualified / absolute / parent-traversal names rejected;
- duplicate normalized members rejected;
- encrypted ZIP members rejected;
- Unix symlink/special members rejected;
- bounded file count, member size та total uncompressed size.

GitHub artifact ingest зберігає свої більш вузькі limits, але використовує той самий helper. Nested Windows evidence та outer reviewer bundle отримали ті самі базові guards.

### Session chronology

`windows-acceptance-evidence-check.py` тепер парсить timezone-aware ISO-8601 timestamps, включно з PowerShell round-trip fractional precision. Installer, portable, requested desktop і DPI evidence не можуть мати timestamp раніше за `windows-host-binding` поточної session.

Це не замінює host/session fingerprints, а додає окремий stale-evidence barrier.

### Closed evidence set

- live Windows evidence closure будується з fixed reports і фактично referenced logs/screenshots;
- unreferenced file у evidence directories робить final validation FAIL;
- nested Windows ZIP повторно будує expected member set із JSON reports і відхиляє будь-які extras навіть при коректно перерахованому `manifest.sha256`;
- final reviewer bundle тепер вимагає exact `REQUIRED_MEMBERS`, а не лише їх наявність.

Це одночасно зменшує ризик evidence mixing і випадкового потрапляння приватного/службового файла в reviewer bundle.

### Candidate-bound ratchet

Новий ZIP helper додано до `CRITICAL_FILES` acceptance harness. Отже зміна helper-а змінює `acceptance-harness.sha256` і Windows host не зможе провести final acceptance зі старою/іншою копією helper-а.

PR/static/supply-chain checks також вимагають нові contracts.

## Acceptance plan

Тести запускаються лише після завершення всіх source/doc/test змін.

План фінального циклу:

1. Python syntax/YAML/static gates;
2. ZIP-safety + Windows evidence + GitHub ingest + harness-binding regressions;
3. final external aggregator + final reviewer bundle regressions;
4. release/supply-chain/implementation-completeness gates;
5. reactor `test-compile` як regression barrier production source;
6. clean source staging і ZIP без Maven/wrapper/`.mvn`/dependency JAR.

## External backlog status

**Не змінюється:** MHL-010, MHL-011, MHL-012, MHL-017, MHL-018, MHL-019 залишаються externally gated. Iteration 42 лише робить майбутній live evidence flow більш fail-closed.

## Final validation evidence

Буде доповнено після єдиного фінального test cycle.
