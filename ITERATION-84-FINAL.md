# MyHomeLib 8.0.0 — Iteration 84 FINAL

Дата локальної фінальної валідації: 2026-09-14
Статус: **FINAL source candidate; local Linux validation PASS**.

Iteration 84 завершує поглиблений аудит Iteration 83 і фокусується на завершеності користувацьких workflow, насамперед Reader annotations/notes, Reader UI, Annotation Manager, Smart Collection scope, restore-position, великому INPX та release/QA gates.

## Головне, що зроблено

- Reader отримує повну модель анотації (type, quote, note, tags, state), має hit-test, mouse/keyboard activation, popover, edit/delete/copy/reanchor/rebind.
- Додано спільний `AnnotationEditorDialog` і винесено orchestration в `ReaderAnnotationCoordinator`.
- Reader має єдину бічну панель: Зміст, Пошук, Закладки, Нотатки/підсвітки, Карта книги.
- Annotation Manager підтримує коректний multi-select, batch delete/color/tags/export і bounded Undo до 20 логічних операцій.
- Додано Markdown «Конспект з анотацій» з порядком читання, групуванням за книгою/главою та MyHomeLib backlinks.
- Smart Collection зберігається як явний search scope; unsupported content scope більше не підміняється глобальними результатами.
- Main toolbar стала однорядковою адаптивною; selection actions контекстні; Book Details структуровано за секціями; Follow Author показує state/new count.
- Restore position використовує semantic `textOffset` як джерело істини після reflow/document change.
- Reader `ImageCache` виправлено для replacement-accounting та thread-visible reads/statistics.
- CSV/digest export публікуються через temp + atomic move; CSV нейтралізує spreadsheet formula markers.
- Large full-snapshot INPX >=100k використовує bounded batch 5000; cancellation до bulk mutation покрито контрактом.
- Активна product/Maven identity уніфікована на **8.0.0**; legacy `v71-*` entrypoints залишені лише для сумісності.
- JavaFX CI gate тепер реально виконує `*FxTest` через Xvfb, а не приймає zero-test green result.

## Фінальна локальна валідація

- `mvn clean verify`: **1080 тестів**, 0 failures, 0 errors, 11 skipped — BUILD SUCCESS.
- JavaFX/Xvfb: **5 тестів у 3 Fx suites**, 0 failures/errors/skipped.
- Static/release gates: **21/21 PASS**.
- FXML: 29 workspaces, 256 handler references, 0 missing.
- SQLite: 60 migrations, integrity OK.
- Real INPX: **707154/707154** records, 0 errors, ~78.75 s, ~8979.73 books/s.
- Real FB2 corpus: 2 archives, 0 failures; ~874.9 ms (14.6 MB) and ~467.0 ms (8.2 MB).

Повний звіт: `docs/release/ITERATION-84-TEST-REPORT.md`.

## Межа підтвердження

Локальний Linux/Java 21 цикл зелений. Реальні Windows-only release gates (Windows TTS, DPI matrix, standard-user MSI/EXE/portable install/update/uninstall, live GitHub/SBOM/SCA/CodeQL evidence) не симулюються і залишаються зовнішніми acceptance gates.
