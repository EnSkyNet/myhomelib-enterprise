# MyHomeLib — наступні задачі після V34 External Test R16

**Дата:** 2026-10-04  
**Фокус:** Windows live retest саме дефектів із другого реального логу; новий product scope не відкривати.

## P0 — already-local / repeated download

На наданих 18 ZIP повторно пройти список і Book Details. Для вже фізично присутньої книги HTTP не повинен стартувати. У log очікується `ONLINE_DOWNLOAD_SKIP_LOCAL` або відсутність download flow взагалі. Особливо перевірити `729586` після restart.

## P0 — INDEX + download

Запустити повне «Оновлення пошукового індексу» й одразу materialize remote-only книгу. DOWNLOAD повинен стартувати під активним INDEX; post-download Lucene/full-text repair може виконатися відкладено після commit.

## P0 — background QUERY + conversion

Після швидкої навігації/author workspace запустити `729586` FB2.ZIP→EPUB. Passive QUERY не повинен відхиляти CONVERSION. Default AUTO route — bundled FBC; silent Calibre fallback заборонений.

## P0 — covers / rapid navigation

Швидко пройти вперед/назад усі 18 книг. Обкладинка поточної книги повинна з'являтися після повернення; stale cancelled request не повинен залишати cover порожнім назавжди. Log: `COVER_START/DONE/NONE/FAIL`.

## P0 — network failure UX

Для недоступної/502 книги один ConnectionScript request не повинен висіти legacy 120 s; hard cap = 30 s, default auto-retry = 0. Network failure не повинна блокувати details/covers/conversion/navigation.

## P0 — shutdown after rapid navigation

Після швидкої навігації закрити програму. Нормальні cancellation старих Author requests не повинні логуватися як user-visible ERROR; executor не повинен залишатися stuck через накопичені passive QUERY leases.

## Після P0

Повторити Windows updater/portable/DPAPI/DPI/rollback, 1–2 h memory soak, physical Kobo, real Readwise, unrestricted PWA, real large CBR/CB7, exact-candidate GitHub/SBOM/SCA/CodeQL/signing.
