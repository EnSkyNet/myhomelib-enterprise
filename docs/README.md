# Документація MyHomeLib

## Поточний контракт

Актуальний практичний аудит спрощення: [current/SIMPLIFICATION-AUDIT-UA.md](current/SIMPLIFICATION-AUDIT-UA.md).

У корені репозиторію навмисно залишаються лише два Markdown entry points:

- `README.md` — старт, build/run, актуальний validation boundary;
- `ARCHITECTURE.md` — модулі, dependency rules і поточний architecture contract.

Актуальні описові документи зібрані в `docs/current/`:

- `FEATURES.md` — підтримувана функціональність і deliberate limits;
- `OPERATIONS.md` — runtime/data/backup/upgrade/troubleshooting;
- `DEVELOPMENT.md` — build, tests, performance та contribution gates;
- `RELEASE.md` — release policy;
- `USER-FUNCTIONS-UA.md` — повний user-facing каталог українською;
- `FUNCTIONAL-AUDIT-UA.md` — functional coverage/evidence boundary;
- `UI-ACTION-COVERAGE.json` / `UI-ACTION-COVERAGE-UA.md` — machine-checkable 100% action inventory;
- `UI-ACTION-BEHAVIOR-COVERAGE.json` / `UI-ACTION-BEHAVIOR-COVERAGE-UA.md` — strict action-specific behavioral evidence (483 automated + 1 justified exclusion);
- `TASK-CLOSURE-AUDIT-UA.md` — аудит усіх попередніх NEXT-TASKS;
- `ARCHITECTURE-AUDIT-UA.md` — останній архітектурний аудит;
- `CONVERTERS-WINDOWS-UA.md` — актуальний Windows runtime-tools/Calibre/libmobi contract;
- `LANGUAGE-PACKS.md` — формат і правила runtime language packs;
- `SMART-COLLECTIONS-PERFORMANCE.md` — чинний performance/design contract Smart Collections.

## Release evidence

Актуальний extraction follow-up: `release/CONTENT-EXTRACTION-MEMORY-AUDIT-2026-10-11-UA.md` — виправлення, ліміти пам’яті та явна межа тестування.

`docs/release/` містить лише матеріали, прив'язані до release/acceptance: `CURRENT-VALIDATION.md`, фінальний deep-audit report, external runbook/test plan, source-package policy, performance evidence та список реально відкритих live tasks.

## Історія

- `history/HISTORY-UA.md` — канонічний український огляд розвитку;
- `history/HISTORY.md` — попередній великий англомовний consolidated archive;
- `history/records/` — exact checkpoint/task/iteration evidence;
- `history/artifacts/` — історичні patch/list/text artifacts;
- `history/source-notes/` — legacy notes, якщо присутні;
- `history/handoffs/` — архівні handoff-пакети попередніх сесій.

Історичні файли не є поточною специфікацією і не повинні повертатися в корінь репозиторію.

## Інші матеріали

Plugin SDK: `plugin-sdk/README.md`, `plugin-sdk/COMPATIBILITY.md`, `plugin-sdk/SAMPLES.md`.  
Performance references: `performance-baseline.json`, `current/SMART-COLLECTIONS-PERFORMANCE.md`.  
Runtime F1 help (`myhomelib-ui/src/main/resources/help/`) є частиною продукту, а не project documentation archive. Runtime language JSON лежить у `Lang/`, а його project documentation — у `current/LANGUAGE-PACKS.md`.

Додатковий звіт: [Перевірка папок авторів за структурованими полями SQLite](current/AUTHOR-FOLDER-CATALOG-AUDIT-UA.md).
