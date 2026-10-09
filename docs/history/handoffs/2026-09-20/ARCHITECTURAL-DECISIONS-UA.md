# Ключові архітектурні рішення для продовження

1. Не переписувати fb2cng/Pandoc/libmobi у Java. Тримати process boundary.
2. Не копіювати GPL-код у MyHomeLib source tree.
3. EPUB3 — canonical reflowable storage; PDF default залишати PDF.
4. Calibre — compatibility fallback, а не primary engine.
5. Fallback дозволяти лише для failure kinds, які явно означають unavailable/unsupported; invalid/security/timeout/cancel/crash/output-invalid/I/O не маскувати.
6. Organizer destructive apply тільки після Analyze/Plan/Preview і повторної SHA-256 перевірки source.
7. Parallelism — тільки staging. Journal/catalog/final filesystem commits послідовні.
8. Ніякого permanent delete у v1: quarantine/archive з rollback.
9. Optimizer fail-closed: якщо resource graph/semantic/reader/EPUBCheck verification не дають достатньої впевненості, зберігати source bytes або лише repack без destructive transform.
10. Image/font provider не є доказом якості. Candidate незалежно перевіряється ядром.
11. Не вмикати WOFF/WOFF2 subsetting або lossy/cross-codec image conversion без окремого backend audit.
12. Всі filesystem recovery paths мають бути no-link/no-reparse.
