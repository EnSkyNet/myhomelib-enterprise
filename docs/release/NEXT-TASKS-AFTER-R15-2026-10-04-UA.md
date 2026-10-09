# MyHomeLib — наступні задачі після V34 External Test R15

**Дата:** 2026-10-04  
**Фокус:** Windows live retest на exact R15 portable; не відкривати новий product scope.

## P0 — швидкий перегляд реальних 18 FB2

На наданому corpus швидко пройти Book Details вперед/назад декілька разів. Очікування:

- network timeouts/indexing не затримують local details;
- deep analysis не тримає spinner >5 s;
- `BOOK_DETAILS_START/DONE/TIMEOUT` у log має title/id/file/duration;
- stale selection не підміняє details новішої книги.

## P0 — INDEX + online download

Запустити «Оновлення пошукового індексу» і під час rebuild скачати remote-only книгу. `ONLINE_DOWNLOAD_START` має з'явитися відразу; download не чекає завершення INDEX. Після deferred repair книга має знайтися search/full-text where supported.

## P0 — conversion на 729586 compilation

- AUTO FB2→EPUB має вибрати bundled FBC;
- log має показати `CONVERSION_START`, route/provider, `FBC_START`, `FBC_DONE`, total duration;
- silent Calibre fallback за default setting заборонений;
- original лишається primary після restart; EPUB — secondary;
- EPUB Book Details: full parse або OPF metadata fallback.

Якщо FBC/EPUB сценарій не проходить — зберегти generated EPUB і `myhomelib.log`; це буде exact real regression corpus.

## Далі

Windows updater/portable/DPAPI/DPI/rollback; 1–2 h memory soak; physical Kobo; real Readwise; unrestricted PWA; real CBR/CB7; connected GitHub/SBOM/SCA/CodeQL/signing.
