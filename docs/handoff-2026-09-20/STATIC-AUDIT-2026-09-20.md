# MyHomeLib 8.0.0 — фінальний статичний compile-risk / integration audit

**Дата:** 2026-09-20  
**Дерево:** merged canonical handoff worktree `/mnt/data/MyHomeLib-canonical`  
**Фаза:** виконано до першого Maven compile/test gate.

## Висновок

Фінальний статичний audit завершено. На момент переходу до build/test gate немає відомих незакритих v1 P0/P1 coding gaps, які планується виправляти до компіляції. Maven, `javac` і програмні тести під час цього audit не запускалися.

## Виправлення, зроблені під час audit

- усунено compile-risk у `Fb2MetadataPatcherTest` з XML-атрибутами в AssertJ literals;
- вирівняно прямі reactor-залежності UI та Calibre plugin;
- синхронізовано shipped/default UA/EN/BG localization catalogs, включно з provider/recovery/preview keys;
- додано/оновлено conversion capability matrix;
- посилено no-link/no-replace publication boundary Toolchain, Optimizer, Application Convert/Export та legacy converter adapters;
- staging output contract вирівняно на fresh/non-existing output path;
- rollback/overwrite переведено на SHA-256 ownership-aware detach/remove/restore;
- Optimizer image/font candidates та writers вирівняно з централізованою path-safety policy;
- Organizer recovery/undo/archive-entry paths посилено перевірками path safety, ownership та archive limits;
- Export destination directory creation переведено на no-link parent-chain policy;
- додано неперевірені regression tests для publication races/symlink destination та пов'язаних path-safety сценаріїв.

## Статичні перевірки

- `python3 tools/check-all-ui-localization.py` — PASS: 3590 synchronized UI keys, locales `uk/en/bg`, 0 literal UI-sink violations.
- `python3 tools/build-check-v7.py` — PASS після очищення службового `tools/__pycache__`: Flyway baseline/invariants, metadata migrations, XML/FXML, source invariants, release-tree cleanliness.
- усі 20 `pom.xml` успішно XML-parsed; duplicate direct dependencies: 0.
- main Flyway migrations: `V1..V62`, без пропусків/дублікатів; metadata migrations: `V1..V10`, без пропусків/дублікатів.
- watched reactor modules не мають відомих missing direct internal dependencies або dependency cycles, пов'язаних із поточним handoff diff.
- syntax-aware lexical scan 1754 Java files (включно з text blocks): 0 unterminated strings/comments/blocks, 0 bracket mismatches.
- changed-file scan: 58 змінених/нових файлів від pristine handoff; `TODO/FIXME/HACK/XXX`: 0.
- Spring wiring review: наявні реалізації reader/EPUBCheck acceptance, journal/catalog/output-validation adapters та PNG provider; `List<FontOptimizerProvider>` свідомо optional і може бути порожнім — font-subsetting backend не є v1 prerequisite.
- localization root/default catalogs: 3590 keys для кожної `uk/en/bg`, key/value parity підтверджено.
- offline dependency SHA-256 звірені з `INPUT-SHA256.txt` для Maven repo, JavaFX, PDF dependencies, `.mvn` та двох supplied FB2 ZIP fixtures.

## Обмеження статичного audit

Статичний audit не є доказом компіляції або runtime correctness. Нові/оновлені unit/integration/e2e tests ще не виконувалися. Реальні supplied fixtures ще не запускалися. Windows/GitHub external acceptance evidence лишається окремим зовнішнім gate.

## Рішення gate

**Development/static-audit phase: COMPLETE.**  
Дозволено перейти до першого offline Maven compile/build/test gate відповідно до `FINAL-GATE-CHECKLIST-UA.md`. Усі дефекти build/test gate мають бути виправлені та gate повторений до green або документованого зовнішнього обмеження.
