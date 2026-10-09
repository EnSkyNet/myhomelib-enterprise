# MyHomeLib 8.0.0 — DEEP AUDIT VERIFICATION

**Дата:** 2026-09-20  
**База deep-audit:** `MyHomeLib-8.0.0-R4-FINAL-SOURCE-2026-09-20.zip`  
**Мета:** незалежний adversarial/security/correctness аудит після попереднього green release gate.

## Підсумок

Deep-audit завершено без подальшого розширення scope. Відносно попереднього FINAL-SOURCE змінено **80** існуючих файлів і додано **5** regression-тестів; видалень немає.

Фінальний стан після всіх deep-audit змін:

- 20-модульний Maven reactor: **`clean package` BUILD SUCCESS**, 20/20 modules;
- Surefire XML: **1373 tests**, **0 failures**, **0 errors**, **14 skipped**; фактично виконано 1359 ordinary tests;
- UI: **153 tests**, 0 failures/errors/skips;
- architecture: **14/14 PASS**;
- e2e: **15/15 PASS**;
- clean release static check: **PASS**;
- real FB2.ZIP fixtures: **2/2 PASS**;
- real INPX: **707154 records PASS**;
- real EPUB STANDARD + MyHomeLib Reader reopen: **PASS**;
- EPUB STRICT без EPUBCheck: очікуваний fail-closed.

## Ключові додаткові дефекти, знайдені deep-audit і виправлені

1. EPUB fallback acceptance міг переносити acceptance-статус кандидата на source-файл, який фактично повертався користувачу.
2. Private-workspace cleanup у application/Organizer міг видалити підмінений regular file; додано ownership/no-link checks.
3. `Fb2ZipBookConverter` міг створювати traversal-like ZIP entry з некоректних metadata; entry нормалізується до safe leaf-name.
4. Compatibility external/Calibre converters не контролювали `maxOutputBytes` під час виконання процесу; додано live monitor + process-tree termination.
5. Installable Calibre plugin писав прямо у final target і міг видалити попередній файл; переведено на private staging + no-replace publication.
6. Міжколекційне копіювання мало predictable `.part` + replace boundary; переведено на random private staging + SHA-256 ownership.
7. Markdown/Annotation/Digest/INPX export boundaries переведено на replace-only-if-unchanged publication.
8. Backup/restore, collection snapshot/rollback і crash recovery отримали SHA-256 ownership, no-link markers/checkpoints та evidence retention замість blind delete/replace.
9. External-reader cache більше не входить у symlinked root і не видаляє сторонні файли.
10. Resumable direct/catalog HTTP downloads більше не пишуть через symlinked `.part`; final target publication snapshot-aware.
11. Download payload validation більше не використовує predictable sibling `.validate-*`, який міг стерти користувацький файл.
12. Credential master key, DPAPI blob і OPDS PKCS#12 boundaries переведено на no-link + ownership-aware publication.
13. Settings rollback синхронізує файл та in-memory state; support bundle не читає symlinked logs/output.
14. Local-copy deletion recovery protocol v2 тепер зберігає SHA-256 ownership; foreign/legacy/symlink recovery state fail-closed.
15. Portable user-data export/import: private snapshot, SHA-bound import, ownership-aware preferences rollback.
16. Plugin install/remove/export: stable staged package validation, no blind replace/delete, plugin export працює лише у host-owned workspace.
17. Sync-folder: no-link root/lock/bundle paths, no-replace final publish.
18. Lucene index/state/checkpoint boundaries посилені проти symlink/foreign-marker cleanup.
19. Organizer crash manifest переведено на snapshot/ownership publication.
20. Archive compression-ratio checks централізовано через overflow-safe exact helper; boundary `100x` дозволено, `100x + 1 byte` відхиляється.
21. RAR cumulative decompression limit тепер є fatal stop-boundary: після перевищення iterator закриває archive і не продовжує розпаковування наступних entries.

## Фінальний build gate

Команда:

```text
LANG=C.UTF-8 LC_ALL=C.UTF-8 \
  xvfb-run -a /mnt/data/MyHomeLib-canonical/.mvn/maven/apache-maven-3.9.6/bin/mvn \
  -o -B -ntp -Dmaven.repo.local=/mnt/data/MyHomeLib-canonical/.mvn/repository clean package
```

Результат: **BUILD SUCCESS**, total time приблизно `02:01 min`.

Test totals:

| Модуль | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| application | 356 | 0 | 0 | 1 |
| infrastructure | 504 | 0 | 0 | 7 |
| reader | 92 | 0 | 0 | 1 |
| ui | 153 | 0 | 0 | 0 |
| converter-toolchain | 32 | 0 | 0 | 1 |
| epub-optimizer | 16 | 0 | 0 | 0 |
| library-organizer | 21 | 0 | 0 | 0 |
| architecture-tests | 14 | 0 | 0 | 0 |
| e2e-tests | 15 | 0 | 0 | 0 |
| benchmark | 3 | 0 | 0 | 3 |
| **Усього** | **1373** | **0** | **0** | **14** |

## Clean release static check

`tools/build-check-v7.py` на чистому release tree: **exit code 0**. PASS: Flyway baseline/upgrade, metadata migrations, XML/FXML, source invariants, release-tree cleanliness.

## Реальні fixtures після deep-audit

### FB2.ZIP

- fixture 1: `14618515` bytes, `6657913` text chars, `54551` paragraphs, `16` chapters, `316` TOC, `41` resources;
- fixture 2: `8181973` bytes, `3470361` text chars, `28682` paragraphs, `9` chapters, `172` TOC, `38` resources.

Результат: **2/2 PASS**.

### INPX

```text
records=707154
emptyTitles=3
replacementCharacters=4
libraryRatings=707154
titleSha256=3707906834d7c2f16fe4b5150aac00f6f5b1b76e54ab007ea0b6b32eb36aa945
```

Результат: **PASS**.

### EPUB

```text
source=5791
result=5791
semantic=EQUIVALENT_SOURCE_KEPT
reader=PASSED
epubcheck=NOT_AVAILABLE
warnings=5
```

STANDARD: **PASS**. STRICT без EPUBCheck: очікуваний **fail-closed**.

## Зовнішні acceptance prerequisites

Цей deep-audit не змінює обмеження середовища попереднього фінального gate: повний runtime sign-off для зовнішніх конвертерів потребує Pandoc >= 3.11, fbc/fb2cng, libmobi, EPUBCheck та Windows runtime для наданого Calibre/DPAPI. Їх відсутність не маскується як PASS.

## Висновок

Після deep-audit **відомих дефектів у перевіреному core/source/regression/real-fixture контурі не залишилося**. Абсолютну відсутність дефектів гарантувати неможливо; external-tool/OS acceptance лишається окремою зовнішньою передумовою.
