# MyHomeLib — точка продовження після deep-audit

**Дата:** 2026-09-20

Deep-audit і повний regression gate завершені. Не повторювати стару інструкцію «не запускати Maven»: цей етап уже пройдено.

Спочатку прочитати `DEEP-AUDIT-VERIFICATION-2026-09-20.md` і `STATUS-UA.md`.

Поточний core/source/regression state — green: 20/20 modules BUILD SUCCESS; 1373 tests, 0 failures/errors; real FB2/INPX/EPUB fixtures PASS. Залишені лише зовнішні acceptance prerequisites: Pandoc >=3.11, fbc/fb2cng, libmobi, EPUBCheck і Windows runtime для Calibre/DPAPI.

Не розширювати JPEG/lossy/font/JXL/AVIF scope без окремої потреби.
