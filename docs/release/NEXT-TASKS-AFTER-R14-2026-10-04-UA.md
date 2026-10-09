# MyHomeLib — наступні задачі після V34 External Test R14

**Дата handoff:** 2026-10-04  
**База:** exact R14 candidate після повного `clean verify` 2097/0/0/23  
**Фокус:** live Windows verification фактичних користувацьких сценаріїв; локальний root regression green.

## P0 — Windows: download під час «Оновлення пошукового індексу»

На exact R14 portable і великій online collection:

1. запустити full-text rebuild / UI flow, який показує «Оновлення пошукового індексу»;
2. не зупиняючи rebuild, вибрати remote-only книгу й натиснути `Завантажити`;
3. зафіксувати wall-clock download — він не повинен чекати завершення rebuild;
4. підтвердити local artifact/file та правильний download queue terminal state;
5. після завершення rebuild/deferred repair перевірити catalogue search;
6. для full-text-supported формату перевірити content search;
7. перевірити Operation Center: немає INDEX↔DOWNLOAD blocking conflict;
8. окремо переконатися, що SWITCH/DELETE/RESTORE під active INDEX усе ще не дозволяють небезпечну зміну collection lifecycle.

Якщо Windows scenario все ще блокується — зберегти `logs/`, Operation Center timestamps та точний момент start/end INDEX/DOWNLOAD; не маскувати retries/sleeps.

## P0 — R13 conversion/EPUB acceptance лишається обов'язковим

На supplied `729586 Демон. Пожиратель душ #01-18.fb2.zip` і backup копії real `data/`:

- V68 repair повертає original FB2/FB2-in-ZIP як primary для вже пошкодженого R12 запису;
- нова FB2→EPUB conversion лишає original primary, EPUB secondary;
- restart не змінює preference;
- повторна conversion не створює duplicate artifact row;
- normal portable AUTO route використовує bundled FBC, а не Calibre через відсутність FBC;
- зафіксувати реальний Windows conversion elapsed;
- Book Details converted EPUB: full parse або OPF metadata fallback з warning.

Якщо конкретний generated EPUB не читається — зберегти саме цей EPUB як regression corpus.

## P1 — Windows packaged / memory / external integrations

Після P0:

- updater/installer/portable/DPAPI/DPI/rollback;
- 1–2 h Windows target-machine memory soak;
- physical Kobo;
- real Readwise;
- unrestricted-browser PWA;
- real large CBR/CB7 corpus;
- exact-candidate GitHub/SBOM/SCA/CodeQL/signing.

## Не робити

- не відкривати V35 product scope;
- не зменшувати SQLite cache/mmap, BookCache/CoverCache або `-Xmx1024m` без нового representative A/B;
- не повертати automatic conversion primary promotion;
- не робити INDEX/DOWNLOAD «fix» через sleeps/retries або вимкнення rebuild;
- не вважати Linux concurrency tests заміною Windows live acceptance.
