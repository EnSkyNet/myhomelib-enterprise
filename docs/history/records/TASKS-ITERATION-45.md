# MyHomeLib — Iteration 45 — Archive traversal efficiency + port boundary

**Дата:** 11.09.2026  
**База:** Iteration 44 reader transition/runtime efficiency  
**Правило:** усі source/docs/test зміни завершуються до першого test/compile запуску; після freeze — лише виправлення конкретного gate-дефекту.

## 1. BookResourceResolver -> ArchiveReader port

`BookResourceResolver` більше не залежить від concrete `ZipArchiveReader`. Архівна технологія залишається infrastructure adapter, а resource resolver використовує application output port `ArchiveReader`.

## 2. One enumeration per archive-entry resolution

Exact stored entry та legacy/server-renamed fallback визначаються з одного bounded `listEntries()` snapshot на одну resolver-операцію.

- exact logical path порівнюється після нормалізації slash/leading slash;
- exact match має пріоритет;
- legacy token/single-FB2 fallback зберігає попередню семантику;
- concrete-only `containsEntry()` більше не потрібний.

Це особливо важливо для 7z/RAR/tar/cpio, де enumeration означає реальний sequential scan.

## 3. Single-pass findFirstEntry

`ZipArchiveReader.findFirstEntry()` більше не виконує `listEntries()` + повторний `readEntry()`.

- ZIP: один central-directory traversal, selected entry повертається як bounded owner stream;
- 7z: один sequential pass, selected entry spooled у bounded temp file;
- RAR: один header pass, selected entry повертається як bounded owner stream;
- tar/tar.gz/tar.bz2/tar.xz/cpio: один sequential pass, selected entry spooled у bounded temp file.

ArchiveSafetyLimits, compression-ratio check для ZIP, temp cleanup та owner-close semantics зберігаються.

## Acceptance після freeze

1. `BookResourceResolverArchiveCompatibilityTest` — compatibility + one-enumeration + port dependency.
2. `ZipArchiveReaderTransparencyTest` — `findFirstEntry()` не використовує старий list/reopen шлях.
3. `tools/iteration45-archive-efficiency-check.py`.
4. Full infrastructure affected regressions.
5. Architecture/completeness/security/static gates.
6. 13-module offline `test-compile`.
7. Clean source ZIP без Maven/wrapper/JAR/target.

## Status

**Completed.** Final validation is recorded in `ITERATION-45-ARCHIVE-TRAVERSAL-EFFICIENCY-CHECKPOINT.md`.
