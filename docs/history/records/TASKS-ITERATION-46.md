# MyHomeLib — Iteration 46 — Bounded streaming materialization

**Дата:** 12.09.2026  
**База:** Iteration 45 archive traversal efficiency + port boundary  
**Правило:** усі source/docs/test зміни завершуються до першого test/compile запуску; після freeze — лише виправлення конкретного gate-дефекту.

## 1. Bounded streaming contract

`ArchiveReader` отримує окремий `materializeEntry(...)` contract: caller-owned target, явний `maxBytes`, cooperative cancellation і fail-closed publication без повного entry `byte[]` у пам'яті.

## 2. Multi-format direct materialization

`ZipArchiveReader` матеріалізує вибраний entry напряму для ZIP/FB2ZIP/CBZ/JAR, 7z, RAR/CBR, TAR/TAR.GZ/TAR.BZ2/TAR.XZ та CPIO. Запис іде в sibling staging file, а target публікується лише після повного успіху. Центральні `ArchiveSafetyLimits`, ZIP compression-ratio guard, 7z decoder memory limit та entry-count guards не послаблюються. Для фактичної роботи Commons Compress з 7z/LZMA2 та `.tar.xz` явно декларується runtime-залежність Tukaani XZ, яку Commons Compress позначає optional.

## 3. Reader one-resolution flow

`BookResourcePort.locateBookContainer(...)` визначає фізичний контейнер без archive-member enumeration. `materializeArchiveBookEntry(...)` один раз застосовує exact-first compatibility resolution і передає фактичне ім'я member до `ArchiveReader.materializeEntry(...)`. Reader більше не читає entry як `InputStream`, щоб потім повторно копіювати його у власний temp-файл.

## 4. Temp lifecycle та cancellation

Reader-owned materialized temp видаляється при preparation error, cancelled/stale handoff, book switch, Back та workspace dispose. Adapter staging видаляється при size violation, cancellation, format/read failure або missing entry.

## 5. Observability

Reader-open timing розділяє `resolve -> materialize -> parse -> render-ready`, а також дозволяє `format` і `size_bytes`. Вміст книги в timing event не логують.

## Acceptance після freeze

1. `ZipArchiveReaderTransparencyTest`: bounded ZIP, cancellation cleanup, max-byte guard, ZIP ratio guard, direct TAR та 7z materialization.
2. `BookResourceResolverArchiveCompatibilityTest`: no-enumeration container locate, exact-first та legacy fallback, рівно одна compatibility enumeration на materialization.
3. `ReaderArchiveCompatibilityUiContractTest`: application-port flow, lifecycle cleanup, timing contract.
4. `tools/iteration46-archive-streaming-check.py`.
5. Affected application/infrastructure/UI regressions.
6. Architecture/completeness/security/static gates.
7. 13-module offline `test-compile`.
8. Real uploaded ZIP/FB2 samples — non-destructive archive/member validation.
9. Clean Iteration 46 source ZIP без Maven/wrapper/JAR/class/target/verification/__pycache__; CRC/path-safety/manifest/SHA-256.

## Статус виконання — 12.09.2026

Реалізацію Iteration 46 завершено. Affected infrastructure regressions: **26/26 PASS**; UI contract: **3/3 PASS**; Iteration 46 contract gate та загальні architecture/completeness/security/static gates: **PASS**. Реальні завантажені FB2 ZIP та INPX пройшли non-destructive integrity/content-root перевірки.

Після підключення окремо наданого PDF offline dependency bundle повний 13-модульний offline `test-compile` пройшов **13/13, BUILD SUCCESS**. Додатковий повний `mvn test` після audit-only виправлення headless JavaFX test harness також пройшов **13/13 модулів, BUILD SUCCESS**: 807 тестів, 0 failures, 0 errors, 10 skipped. Dependency JAR не додаються до source ZIP.

## External gates

MHL-010/011/012/017/018/019 залишаються **OPEN**, доки немає реального Windows/GitHub evidence. Iteration 46 не закриває їх синтетичними локальними результатами.
