# MyHomeLib V34 R1 — findings зовнішнього Windows-тестування

**Дата:** 2026-10-02

За результатами ручного тестування зафіксовані відкриті питання для наступного candidate:

1. **Conversion primary artifact defect (P0).** Після FB2→EPUB conversion EPUB відображається як primary/active artifact без явної команди користувача. Очікування: conversion додає secondary artifact, primary змінюється лише через `Зробити основним`.
2. **Repeated author navigation defect (P0).** Після виходу з author workspace в Operations/Reader/інший workspace повторний клік на того самого автора з правої панелі не повертає до автора.
3. **Remote-only Book Details UX defect (P0).** Для незавантаженої книги показується технічне `не вдалося отримати розширені дані/файл недоступний`, хоча має бути штатний стан `Книгу не завантажено` + download action.
4. **R1 regression gate (P1).** Перевірити на Windows, що passive Book Details/FB2 inspection більше не бере global library lease, не повторює HTTP download і не блокує Operations/Reader/Conversion/navigation.
5. **Operation/status consistency (P1).** Blocking operations мають бути видимі; завершене `Завантажено: ...` не повинно виглядати як нова активна операція після passive background task.
6. **Memory optimization (P1).** Провести same-machine baseline і A/B для SQLite pool/cache/mmap та application caches; не знижувати `Xmx` без доказу.

Детальний порядок, acceptance criteria та regression matrix: `docs/current/NEXT-TASKS-V34-UA.md`.
