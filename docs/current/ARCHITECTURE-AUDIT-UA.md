# MyHomeLib — актуальний архітектурний аудит V34

Дата: **2026-10-02**.  
Scope: exact-current source після V30–V34 та real-data tuning, перед зовнішнім acceptance.

## Висновок

Архітектура залишається модульним desktop-monolith із OPDS/Web і MCP sidecar runtimes. V31 додав окремий **JDK-only `myhomelib-updater`**, який не є другим application runtime. Глобальний redesign не потрібний; hard boundaries лишаються acyclic і перевіряються Python guard + ArchUnit.

Під час deep audit 2026-10-02 знайдено й виправлено дві прогалини, які попередній green build не виявив:

1. `myhomelib-updater` був у root Maven reactor, але відсутній у `architecture-check.py` та `LayerArchitectureTest.PRODUCTION_MODULES`. Через це architecture gate фактично контролював 16 із 17 runtime/product modules.
2. `KoboSyncPort` містив невикористаний explicit import; `implementation-completeness-check.py` коректно його виявив.

Після fix `architecture-module-coverage-test.py` вимагає однакове покриття всіх **17/17 runtime/product modules** у Python checker і ArchUnit. Updater має explicit JDK-only boundary і нуль internal Maven dependencies.

## Актуальні production boundaries

- `shared` — низькорівневі primitives/security/path/archive/text utilities без product/framework dependencies.
- `domain` — domain model/value objects/events; лише Shared/JDK.
- `application` — use cases/DTO/ports; без adapter/UI/JDBC/Lucene coupling.
- `epub-optimizer` — EPUB optimization поверх Shared.
- `library-organizer` — Analyze/Plan/Apply, metadata/fingerprint/duplicate/safe publication.
- `converter-toolchain` — provider-neutral conversion orchestration.
- `plugin-api` / `plugin-samples` / `converter-calibre-plugin` — versioned extension boundary та reference implementations.
- `infrastructure` — SQLite/Flyway/Lucene/network/filesystem/archive adapters.
- `reader` — portable reader core + окремий JavaFX renderer boundary.
- `ui` — JavaFX presentation без direct Infrastructure/JDBC/Lucene access.
- `updater` — standalone JDK-only update/rollback helper, без product-module dependencies.
- `bootstrap` — desktop composition root.
- `web` / `opds` — server-rendered Web Library та HTTP sidecar через Application contracts.
- `mcp` — окремий technology sidecar із дозволеним direct SQLite/archive boundary.

Verification-only modules: `architecture-tests`, `e2e-tests`, `benchmark`. Root reactor: **20 child modules / 21 Maven projects including root**.

## Deep-audit cleanup

- Єдиний exact cross-module method clone >=180 chars був у JSON escaping (`AnnotationExportService` і `KoboProtocolRenderer`). Його усунуто через малий `shared.text.JsonStrings`; той самий primitive використано в OPDS JSON renderers, щоб не мати кількох реалізацій control-character escaping.
- Додано `project-hygiene-check.py`: local Markdown links, duplicate Java FQCN, Flyway-version uniqueness per migration location, current/release duplicate docs, root historical-doc placement і build/cache trash.
- Source packager тепер запускає `implementation-completeness-check.py`, `project-hygiene-check.py` та `architecture-module-coverage-test.py`, тому ці прогалини не можуть повторно пройти release packaging непомітно.

## Відомий керований debt

UI ще має ratcheted використання частини application output ports/domain models. Це ceiling, а не ціль: нові users заборонені, зменшення дозволене. Великі класи не декомпозуються лише за LOC; extraction робиться тільки при окремому responsibility contract і behavioral evidence.

## Acceptance

Exact-current test counts і Maven reactor status фіксуються після фінального clean verify у `docs/release/CURRENT-VALIDATION.md`. Обов'язкові structural gates для цього checkpoint:

- `tools/implementation-completeness-check.py`;
- `tools/project-hygiene-check.py`;
- `tools/architecture-module-coverage-test.py` — **17/17**;
- `tools/architecture-check.py`;
- `myhomelib-architecture-tests` із окремим updater rule;
- static release, supply-chain, UI behavior, user-functions, localization/help.
