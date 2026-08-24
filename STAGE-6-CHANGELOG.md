# Stage 6 Changelog — Online update model: data layer

Дата: 24 серпня 2026

## Scope

Цей реліз виконує тільки етап 6 узгодженого roadmap: catalog/source revision model, stable remote source identity, безпечний catalog UPSERT, download baseline та визначення `NEW_BY_FOLLOWED_AUTHOR` / `UPDATED_DOWNLOADED_BOOK`. Stage 7 Updates UI не розпочинався.

## Реалізовано

### 1. Flyway V31: catalog/source revision model

Додано `V31__catalog_update_revision_model.sql` з чотирма окремими структурами:

- `catalog_sources` — стабільний logical source, sanitized location, SHA-256 fingerprint і монотонна `source_revision`;
- `catalog_book_state` — per-book catalog fingerprint/storage metadata, `first_seen_revision`, `last_seen_revision` і downloaded baseline;
- `followed_authors` — явний persistent follow-state автора;
- `catalog_update_events` — pending/acknowledged event state для двох Stage 6 update types.

Catalog-owned state відокремлений від `books` і user-data, тому revision tracking не потребує перезапису rating/progress/review/bookmarks або фізичного local storage.

### 2. Stable source identity для remote INPX

- Додано `CatalogSourceIdentity`.
- `UpdateCollectionFromNetworkUseCase` передає `remote-collection:<collection.id>` як stable logical source key.
- Тимчасове ім'я завантаженого INPX у cache більше не є identity remote catalog.
- `InpxImportPipeline` SHA-256 fingerprint-ить INPX bytes перед import.
- Той самий source fingerprint не змінює revision; змінений fingerprint збільшує revision рівно на 1.
- `source_location` зберігається лише як diagnostics після видалення credentials/query/fragment.
- Для manual/local INPX tracker використовує logical root-relative source key, але старий `inpx:<path>` book-ID marker збережено для сумісності наявних локальних імпортів.

### 3. Per-book catalog fingerprint

- Додано `CatalogBookSnapshot`.
- Для кожної INPX книги рахується deterministic SHA-256 catalog fingerprint із нормалізованих INPX metadata + resolved archive/folder/file identity.
- Source revision та per-book fingerprint розділені: зміна всього INPX не породжує book update, якщо конкретний book fingerprint не змінився.
- Reappearance книги використовує наявний `catalog_book_state`, тому не класифікується як нова книга вдруге.

### 4. Downloaded revision / baseline

- `DownloadBookUseCase` після успішного physical download та `updateStorage(..., local=true)` викликає `markDownloadedBaseline(bookId)`.
- Поточні `catalog_revision` / `catalog_fingerprint` копіюються в `downloaded_revision` / `downloaded_fingerprint`.
- Успішний download також acknowledge-ить pending catalog events цієї книги, включно з `NEW_BY_FOLLOWED_AUTHOR`.
- `RemoveLocalBookCopyUseCase` очищає downloaded baseline при явному видаленні local bytes.
- На першому Stage 6 baseline sync уже локальні книги отримують best-effort downloaded baseline, щоб upgrade не створив хибні update notifications.

### 5. `UPDATED_DOWNLOADED_BOOK`

Event створюється лише коли одночасно виконано:

1. є попередній `catalog_book_state`;
2. книга `local = 1`;
3. є downloaded baseline;
4. incoming catalog fingerprint відрізняється від попереднього catalog fingerprint;
5. incoming fingerprint відрізняється від downloaded fingerprint.

`PRIMARY KEY (book_id, update_type)` і conditional UPSERT не дають повторному sync тієї самої revision створювати дублікати. Якщо catalog повернувся до exact downloaded fingerprint, stale `UPDATED_DOWNLOADED_BOOK` прибирається.

### 6. `NEW_BY_FOLLOWED_AUTHOR`

Event створюється лише коли:

1. source вже має initial baseline;
2. book ще не має `catalog_book_state`;
3. хоча б один її автор присутній у `followed_authors`.

Перший sync існуючого catalog навмисно не генерує лавину `NEW` events. Followed author — окреме поняття, не implicit alias для book group Favorites; application API для follow/unfollow додано в `CatalogUpdateService`.

### 7. Безпечний remote UPSERT

`JdbcBatchWriter` при conflict продовжує оновлювати catalog metadata, але якщо existing row локальний, зберігає:

- `file_name`;
- `folder`;
- `archive_entry`;
- `file_size`;
- `collection_root`;
- `local = 1`.

Також явно зберігаються existing `rate`, `progress`, `review`, `created_at`. Bookmarks знаходяться в окремій таблиці й INPX import їх не перезаписує. Відсутня в новій revision catalog книга отримує `deleted = 1`, але її локальні bytes/storage metadata не знищуються.

### 8. Transaction semantics

Коли active collection має datasource, в одній existing INPX transaction виконуються:

- source revision update;
- mark previously tracked rows missing;
- book/author/genre UPSERT;
- per-book catalog state;
- update-event classification.

Cancellation/exception rollback-ить Stage 6 state разом з catalog mutation.

### 9. Application/persistence API

Додано:

- `CatalogUpdateTrackingPort`;
- `CatalogUpdateService`;
- `CatalogUpdateType`;
- `CatalogUpdateRecord`;
- `CatalogSyncSession`;
- `CatalogBookSnapshot`;
- `CatalogSourceIdentity`;
- SQLite adapter `SqliteCatalogUpdateTrackingAdapter`.

Stage 7 зможе читати pending events/count і керувати follow-state через application layer без SQL у JavaFX controller.

### 10. Tests / regression guards

Додано/оновлено:

- `CatalogSourceIdentityTest`;
- `UpdateCollectionFromNetworkUseCaseStage6Test`;
- `SqliteCatalogUpdateTrackingAdapterTest`;
- `JdbcBatchWriterStage6Test`;
- `InpxImportPipelineTest` для нового dependency contract;
- `tools/stage6-online-update-check.py`;
- Stage 6 guardrails у `tools/architecture-check.py`.

Offline regression перевіряє initial baseline, repeated identical sync, exactly-one changed downloaded update, local/user-data preservation, followed-author NEW event і acknowledgement після successful download.

### 11. Documentation

- Додано `docs/architecture/ONLINE_UPDATE_MODEL_STAGE6.md`.
- Оновлено `ARCHITECTURE.md`.
- Оновлено `README.md`.

## Архітектурні рішення

- Stable remote source identity прив'язаний до collection identity, а не до cache path або signed URL.
- Source fingerprint і book fingerprint мають різні ролі: перший версіонує catalog sync, другий визначає meaningful book update.
- Favorites group не використовується як прихований followed-author state.
- Catalog storage metadata зберігається окремо від installed local storage.
- Stage 6 не додає navigation `Updates`, badge/tree або UI керування events; це scope Stage 7.

## Post-delivery compile hotfix

Після повного `mvn clean` / test compilation на Windows було виявлено одну помилку Java generic inference у `JdbcBatchWriterStage6Test`:

- було: `writer.batchInsertFull(List.of(row), ...)`;
- стало: `writer.batchInsertFull(List.<Object[]>of(row), ...)`.

Причина: для аргументу `Object[]` varargs overload `List.of(E...)` може вивести `E = Object`, утворюючи `List<Object>` замість контрактного `List<Object[]>`. Виправлення стосується тільки Stage 6 regression test; production-код та data semantics не змінювалися. Інших аналогічних test patterns не знайдено.

