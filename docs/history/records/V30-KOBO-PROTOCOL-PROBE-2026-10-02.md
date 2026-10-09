# V30 — Kobo protocol research/probe record

**Дата:** 2026-10-02  
**Статус:** compatibility research/probe; не live Kobo acceptance.

## Спостережений third-party interoperability contract

Для сумісного native Kobo sync поширений підхід Calibre-Web використовує `api_endpoint=` у `.kobo/Kobo/Kobo eReader.conf`, де URL містить opaque per-user token. Kobo звертається щонайменше до initialization та library sync endpoints; Calibre-Web також реалізує metadata/download/reading-state flows і може проксіювати невідомі Store API requests.

Джерела probe:
- Calibre-Web `cps/kobo.py`: https://github.com/janeczku/calibre-web/blob/master/cps/kobo.py
- Calibre-Web `cps/kobo_auth.py`: https://github.com/janeczku/calibre-web/blob/master/cps/kobo_auth.py
- Calibre-Web NextGen Kobo sync setup notes: https://github.com/new-usemame/Calibre-Web-NextGen

## Висновок для MyHomeLib V30

MyHomeLib реалізує вузький deterministic subset у чинному OPDS sidecar: token-scoped initialization, device auth, incremental `/v1/library/sync`, metadata, content download і reading-state exchange. Stable identity — MyHomeLib book id + content/artifact revision; credential зберігається тільки в `SecretStore`; cursor/projection/progress-version state — у V66.

Це не є офіційною специфікацією Kobo. Поведінка Kobo firmware може відрізнятися, тому fixture-level PASS не дозволяє стверджувати live interoperability. Фізичний Kobo acceptance залишається окремим обов'язковим gate.
