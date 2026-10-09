# MyHomeLib — V34 External Test R4 validation

**Дата:** 2026-10-03  
**База:** V34 External Test R3  
**Мета:** закрити залишковий post-R1 operation/status regression, test-runtime maintenance та формальний one-shot Maven gate без відкриття V35 scope.

## 1. Status bar: completed download більше не «оживає» після іншої background operation

У R3 успішне `Завантажено: <книга>` зберігалося як звичайний foreground status. Якщо пізніше coordinator показував іншу background operation, `StatusBarViewModel` тимчасово приховував foreground message, а після `clearBackgroundOperation()` відновлював його. Через це старе completion-повідомлення могло знову з'явитися й виглядати як нова download activity.

R4 додає явну transient foreground semantics:

- `StatusBarViewModel.setTransientStatusText(...)` показує terminal notification одразу, але не відновлює її після наступної background operation, якщо foreground за цей час не змінювався;
- звичайний `setStatusText(...)` зберігає попередню semantics і після background operation відновлюється;
- якщо під час background operation з'явився новий foreground status/progress, він не губиться і стає видимим після завершення background operation;
- успішний single-book download використовує transient status для `Завантажено: ...`.

Нові regression tests у `StatusBarViewModelTest`:

1. old transient download completion не відновлюється після наступної background operation;
2. новий foreground update, отриманий під час background operation, коректно відновлюється.

Targeted status/operation run: **6 tests / 0 failures / 0 errors**.

## 2. Mockito / Byte Buddy test runtime

Mockito 5 test modules повинні використовувати explicit `-javaagent`, а не dynamic self-attachment JVM. У R3 ця настройка вже була в Application, Infrastructure, Reader, UI та EPUB Optimizer, але була відсутня в двох Mockito-споживачах:

- `myhomelib-bootstrap`;
- `myhomelib-e2e-tests`.

R4 додає `${mhl.mockito.agentArg}` до обох модулів. `tools/static_release_check.py` тепер автоматично сканує test sources: якщо модуль імпортує `org.mockito`, його `pom.xml` зобов'язаний явно підключати `mhl.mockito.agentArg`.

Validation:

- Bootstrap: **57 tests / 0 failures / 0 errors / 4 skipped**;
- E2E: **15/15 PASS**;
- у логах R4 root verify немає Mockito self-attach / Byte Buddy dynamic-agent warning;
- offline static release check — **PASS**.

## 3. Повний one-shot root `clean verify`

R4 пройшов один безперервний root reactor run у supplied offline environment:

```text
xvfb-run -a mvn -o -Dmaven.repo.local=<offline-repo> clean verify
BUILD SUCCESS
Total time: 02:27 min
```

Environment: JDK 21, Apache Maven 3.9.16, `C.UTF-8`, JavaFX через Xvfb, supplied offline Maven repository.

Exact Surefire aggregate після цього run:

- **2084 tests**;
- **0 failures**;
- **0 errors**;
- **22 skipped**;
- reports присутні для **20/20** test/product modules.

Ключові модулі:

- Application **509 / 0 / 0 / 1 skipped**;
- Infrastructure **681 / 0 / 0 / 12 skipped**;
- Reader **150 / 0 / 0 / 1 skipped**;
- UI **373/373**;
- Bootstrap **57 / 0 / 0 / 4 skipped**;
- E2E **15/15**;
- Architecture **20/20**;
- OPDS **29/29**;
- Web **18/18**;
- MCP **10/10**.

Таким чином попередній R2/R3 execution-tool gap для one-shot root `clean verify` локально закритий фактичним `BUILD SUCCESS`, а не split-only inference.

## 4. Scope, який не підміняється локальним PASS

R4 не змінює external/live gates. Ще потрібні: real Windows repeated-selection/non-blocking acceptance, physical Kobo, packaged Windows updater/Calibre/DPAPI/DPI/file-lock/rollback/signing, real Readwise account, unrestricted-browser PWA, real large CBR/CB7 corpus, 1–2 h target-machine soak та same-machine Windows/large-library memory A/B.

Production Hikari/SQLite/cache/Xmx limits у R4 не зменшувалися: synthetic Linux evidence недостатній для такого рішення.
