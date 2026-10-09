# MyHomeLib — матриця можливостей конвертації v1

**Стан коду:** 2026-09-20, після фінального build/test/real-fixture gate.

Цей файл описує **заявлені кодом** маршрути. Основний reactor і regression gate підтверджені, але фактична runtime-доступність конкретного маршруту все одно залежить від discovery/version probe зовнішніх інструментів. Для production Pandoc bridge потрібен **Pandoc >= 3.11**; наявний у Linux-середовищі Pandoc 3.1.11.1 навмисно діагностується як несумісний через mandatory `--sandbox`. fbc/libmobi/EPUBCheck у цьому середовищі відсутні, тому відповідні real-tool acceptance маршрути залишаються зовнішньо заблокованими, а не позначаються PASS.

## 1. MyHomeLib Toolchain

Джерела істини: `FbcCapabilities`, `PandocCapabilities`, `LibMobiCapabilities`, `ConversionRoutePlanner`. Planner вибирає спочатку прямий маршрут, далі — максимум один проміжний hop; маршрут `source == target` не створюється. У матриці нижче: **F** = fbc, **P** = Pandoc, **M** = libmobi; `F→P` тощо означає двокроковий маршрут.

| Source \ Target | `docx` | `epub` | `fb2` | `kepub` | `pdf` | `rtf` | `txt` |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `azw` | M→P | M | M→P | — | — | M→P | M→P |
| `azw3` | M→P | M | M→P | — | — | M→P | M→P |
| `azw4` | M→P | M | M→P | — | — | M→P | M→P |
| `docx` | — | P | P | P→F | P→F | P→P | P→P |
| `epub` | P | — | P | P→F | P→F | P | P |
| `fb2` | F→P | F | — | F | F | F→P | F |
| `fb2_zip` | F→P | F | F→P | F | F | F→P | F |
| `htm` | P→P | P | P→P | — | — | P→P | P→P |
| `html` | P→P | P | P→P | — | — | P→P | P→P |
| `markdown` | P→P | P | P→P | — | — | P→P | P→P |
| `md` | P→P | P | P→P | — | — | P→P | P→P |
| `mobi` | M→P | M | M→P | — | — | M→P | M→P |
| `odt` | P | P | P | P→F | P→F | P→P | P→P |
| `prc` | M→P | M | M→P | — | — | M→P | M→P |
| `rtf` | P→P | P | P | P→F | P→F | — | P→P |
| `txt` | P→P | P | P→P | — | — | P→P | — |

### Прямі engine capabilities

- **fbc:** `fb2`/`fb2_zip` → `epub`, `kepub`, `pdf`, `txt`.
- **Pandoc:** `epub` → `fb2`, `docx`, `rtf`, `txt`; `docx` → `epub`, `fb2`; `rtf` → `epub`, `fb2`; `html`/`htm`/`txt`/`md`/`markdown` → `epub`; `odt` → `epub`, `docx`, `fb2`.
- **libmobi:** `mobi`/`prc`/`azw`/`azw3`/`azw4` → `epub`. DRM/encrypted input має завершуватися `SECURITY_REJECTED`, а не fallback-ом у Calibre.

Маршрут у таблиці існує лише тоді, коли **всі** потрібні engines пройшли discovery/version diagnostics. Наприклад, `mobi → fb2` потребує одночасно libmobi і Pandoc; `docx → pdf` — Pandoc і fbc.

## 2. Calibre compatibility fallback

`ebook-convert` не є primary engine. Поточний Calibre adapter декларує повний декартів добуток між таким набором входів і цілей (окрім рішень orchestration щодо `source == target`):

- **Sources (33):** azw, azw3, azw4, cbz, cbr, cb7, cbc, chm, djvu, docx, epub, fb2, fb2_zip, fbz, html, htm, htmlz, kepub, lit, lrf, mobi, odt, pdf, prc, pdb, pml, pmlz, rb, rtf, snb, tcr, txt, txtz.
- **Targets (19):** azw3, epub, docx, fb2, htmlz, kepub, lit, lrf, mobi, pdb, pmlz, rb, pdf, rtf, snb, tcr, txt, txtz, zip.

Application-level fallback до наступного provider дозволений **лише**, якщо exception implements `ConversionFailureSignal` і `mayTryNextProvider() == true`; у v1 це призначено для `UNAVAILABLE`/`UNSUPPORTED`. Invalid input, security rejection, cancellation, timeout, crash, invalid output та I/O/corruption — hard stop і не повинні маскуватися Calibre fallback-ом.

## 3. Provider selection modes

- `AUTO`: усі дозволені providers у детермінованому priority order.
- `TOOLCHAIN`: direct built-in providers + `myhomelib-toolchain`; Calibre/manual external відсікаються.
- `CALIBRE`: direct built-in providers + Calibre.
- `MANUAL`: direct built-in providers + explicit external converters priority 300.

Priority у поточному контракті: direct built-ins `>=1000`, Toolchain `500`, explicit external `300`, Calibre `100`.

## 4. Acceptance boundary

Наявність capability не означає прийняття результату. Після engine output діють format-specific validators; publication/Organizer commit виконуються лише після валідації, source binding і безпечного filesystem boundary. EPUB може додатково проходити optimizer acceptance (`STANDARD`/`STRICT`, reader reopen, EPUBCheck залежно від режиму/наявності).
