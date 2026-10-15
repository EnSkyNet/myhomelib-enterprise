# MyHomeLib Enterprise 8.0.0

Поточні виправлення й перевірки: [аудит 2026-10-15](docs/release/AUDIT-REMEDIATION-2026-10-15-UA.md).

Поточний source checkpoint: **R18 + виправлення упорядника й аудит, 2026-10-15**. Виправлено перехід до результату після помилки, логування етапів і винятків, скасування підготовки, довгі тимчасові назви; буфери SHA-256 зменшено до 64 KiB. Збережено оптимізації FB2/EPUB/TXT, захист заголовків і межі метаданих EPUB.

Повний root `-Pproduction clean verify` від **2026-10-13: BUILD SUCCESS**, 2347 тестів у звітах, 23 пропущено, **2324 виконано; 0 failures / 0 errors**. 15 жовтня підтверджено тотожність усіх 2448 файлів тестового маніфесту та пройдено 11 перевірок вихідного коду й пакування. Новий root-прогін 15 жовтня не виконувався. **Stage 25C залишається FAIL: FolderSyncService 375 рядків при межі 360**; Windows/user-library acceptance та live SCA відкриті. Докладні докази й межі — у поточному звіті.

Після оновлення потрібна **одноразова перебудова повнотекстового індексу** (schema 1 → 2) у вікні перевірки цілісності колекції.

Канонічна історія розвитку українською: [docs/history/HISTORY-UA.md](docs/history/HISTORY-UA.md). Історичні checkpoint/task/audit матеріали зберігаються під `docs/history/`; активні специфікації — під `docs/current/` і `docs/release/`.

## Quick start

Вимоги: JDK 21, Maven 3.9.6+ або наявний Maven Wrapper у developer-source handoff, dependency access чи підготовлений offline repository, JavaFX-compatible desktop environment.

```bash
./build.sh
./run.sh
./package.sh
```

Windows PowerShell:

```powershell
.\build.ps1
.\run.ps1
.\package.ps1
```

Очікуваний executable Boot JAR: `myhomelib-bootstrap/target/myhomelib-bootstrap-8.0.0.jar`.

## Основні можливості

MyHomeLib підтримує кілька SQLite-колекцій, INPX і directory import, локальні/online каталоги, Lucene search, integrated Reader, PDF/comic/audio paths, annotations, Organizer, conversion, export/device actions, backup/restore, folder/WebDAV sync, OPDS/Web, MCP, plugins та provider-neutral AI integration boundary.

Повний end-user каталог з форматами, передумовами, обмеженнями й рівнем перевірки: [docs/current/USER-FUNCTIONS-UA.md](docs/current/USER-FUNCTIONS-UA.md).

## Дані

Звичайний installed mode використовує `${user.home}/.myhomelibcorp`. Portable mode активується `myhomelib2.ini` біля launcher або `-Dmyhomelib.portable=true` і використовує `<launch-dir>/data`. Деталі й overrides: [docs/current/OPERATIONS.md](docs/current/OPERATIONS.md).

## Документація

- [ARCHITECTURE.md](ARCHITECTURE.md) — актуальна модульна архітектура, dependency graph і hard boundaries;
- [docs/current/ARCHITECTURE-AUDIT-UA.md](docs/current/ARCHITECTURE-AUDIT-UA.md) — останній архітектурний аудит;
- [docs/current/FEATURES.md](docs/current/FEATURES.md) — поточний feature contract;
- [docs/current/USER-FUNCTIONS-UA.md](docs/current/USER-FUNCTIONS-UA.md) — повний опис функцій користувача;
- [docs/current/UI-ACTION-COVERAGE-UA.md](docs/current/UI-ACTION-COVERAGE-UA.md) — inventory 484 user actions;
- [docs/current/UI-ACTION-BEHAVIOR-COVERAGE-UA.md](docs/current/UI-ACTION-BEHAVIOR-COVERAGE-UA.md) — action-specific behavioral evidence 484/484;
- [docs/current/TASK-CLOSURE-AUDIT-UA.md](docs/current/TASK-CLOSURE-AUDIT-UA.md) — консолідований аудит усіх попередніх task/handoff документів;
- [docs/current/OPERATIONS.md](docs/current/OPERATIONS.md) — експлуатація, data paths, recovery і troubleshooting;
- [docs/current/DEVELOPMENT.md](docs/current/DEVELOPMENT.md) — build/test/development guardrails;
- [docs/current/RELEASE.md](docs/current/RELEASE.md) — release/upgrade contract;
- [docs/release/CURRENT-VALIDATION.md](docs/release/CURRENT-VALIDATION.md) — exact-current validation evidence;
- [docs/release/DEEP-PROJECT-AUDIT-V34-2026-10-02-UA.md](docs/release/DEEP-PROJECT-AUDIT-V34-2026-10-02-UA.md) — фінальний deep source/docs/hygiene audit перед зовнішнім тестуванням;
- [docs/release/REAL-DATA-ACCEPTANCE-V34-2026-10-02.md](docs/release/REAL-DATA-ACCEPTANCE-V34-2026-10-02.md) — large real-data acceptance: 707 154-record INPX, migration/search/restart/V33/V34 probes;
- [docs/release/WHAT-REMAINS-UA.md](docs/release/WHAT-REMAINS-UA.md) — незакриті локальні та зовнішні перевірки;
- [docs/history/HISTORY-UA.md](docs/history/HISTORY-UA.md) — загальна історія робіт українською;
- [docs/README.md](docs/README.md) — карта документації.

Runtime help/localization assets (`Lang/`, `myhomelib-ui/src/main/resources/help/`) не змішуються з project documentation.

## Validation boundary

`tools/build-check-v7.py` fail-closed перевіряє migrations/source invariants, 100% UI action inventory, **action-specific behavioral coverage**, native chooser ownership і зв'язок кожної JavaFX action з канонічним user-function flow. `tools/architecture-check.py` і ArchUnit контролюють 17 production/runtime modules, включно зі standalone updater helper. Source/release tooling також перевіряє supply-chain policy, archive safety, static invariants і cleanliness.

Локальний Linux/Xvfb PASS **не замінює** реальну Windows x64 acceptance для Calibre/process teardown, installer/portable/DPI, device/system integrations і candidate-bound GitHub/SBOM/SCA/CodeQL/signing evidence. Актуальний перелік: `docs/release/WHAT-REMAINS-UA.md`.


Для Windows bootstrap-portable див. [docs/current/WINDOWS-PORTABLE-NEXT-UA.md](docs/current/WINDOWS-PORTABLE-NEXT-UA.md).
