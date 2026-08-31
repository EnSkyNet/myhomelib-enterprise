# Stage 5 Changelog — Recent / AlreadyRead / History + navigation history

Дата: 24 серпня 2026

## Scope

Цей реліз виконує тільки етап 5 узгодженого roadmap. Етап 6 (online update model) не розпочинався.

## Реалізовано

### 1. Recent books з timestamp

- Додано application-level `ReadingHistoryService` і окремий `ReadingHistoryPort` contract для журналу відкриттів.
- Додано меню `Недавні книги`, яке динамічно показує останні книги разом з локальним timestamp `dd.MM.yyyy HH:mm`.
- Вибір книги з Recent відкриває її у вбудованому Reader.
- У журнал потрапляє лише успішне відкриття книги в `NewReaderWorkspaceController`; невдале відкриття не створює history entry.
- Повторне відкриття тієї самої книги оновлює `last_opened_at` і збільшує `open_count`, не створюючи дубліката.

### 2. Окремий persistent reading history

- Додано Flyway migration `V30__create_reading_history.sql`.
- Нова таблиця `reading_history` відокремлює журнал відкриттів від reader progress/bookmarks/user state.
- Під час upgrade V30 best-effort backfill переносить останній відомий час із наявних `reading_stats.last_read_at` та `reading_progress.updated_at`.
- Додано індекс за `last_opened_at` для швидкого Recent/History ordering.

### 3. AlreadyRead mode/workspace

- До `NavigationMode` додано `ALREADY_READ`.
- Додано synthetic navigation node та окремий book-table workspace.
- Semantics: книга вважається прочитаною, коли `progress = 100`; deleted books не показуються.
- Режим працює через спільний `NavigationQueryService`/`BookQuery`, без SQL у JavaFX controller.

### 4. History mode/workspace

- До `NavigationMode` додано `HISTORY`.
- Додано synthetic navigation node та окремий book-table workspace.
- `BookQuery.onlyInHistory` використовує `reading_history` join та стабільне сортування `last_opened_at DESC, book_id ASC`.
- History використовує стандартну пагінацію book table та не перетворюється на ad-hoc search results.

### 5. Navigation Back / Forward

- Існуючий `WorkspaceManager` history stack розширено для `already-read` та `history` workspace types.
- Back/Forward відновлює ці workspace-и без повторного додавання тієї ж точки в navigation history.
- Додано keyboard navigation `Alt+Left` / `Alt+Right`.

### 6. Clear history

- Додано дію `Очистити історію читання` з confirmation dialog.
- Очищується тільки `reading_history`.
- Reading progress, AlreadyRead state, bookmarks, rating/review та інші user-data не видаляються.
- Після очищення оновлюються navigation counts, Recent menu та відкритий History workspace.

### 7. Localization та UI

- Оновлено `uk`, `en`, `bg` каталоги у root `Lang/` і bundled default language resources.
- Додано localized keys для Recent, AlreadyRead, clear-history та empty-history state.
- Каталоги залишаються синхронізованими: 158 ключів у кожній мові.

### 8. Tests / regression guards

- Додано `ReadingHistoryServiceTest`.
- Додано `BookQueryBuilderStage5Test`.
- Розширено `DefaultNavigationQueryServiceTest` для `ALREADY_READ` та `HISTORY`.
- Додано offline regression script `tools/stage5-history-check.py`.
- Розширено `tools/architecture-check.py` Stage 5 guardrails.
- Stage 3 та Stage 4 offline regression checks продовжують проходити.

### 9. Documentation

- Оновлено `ARCHITECTURE.md`.
- Оновлено `README.md`.
- Оновлено `docs/architecture/NAVIGATION_CORE.md`.
- Додано `docs/architecture/RECENT_HISTORY_STAGE5.md` з data-flow та safety semantics.

## Архітектурні рішення

- Reading history навмисно не зберігається як побічний ефект `reading_progress`: це дозволяє очищати історію без втрати позиції читання.
- `AlreadyRead` лишається derived state з `progress = 100`, а не дублюється в новій таблиці.
- `History` ordering задається у persistence query layer; JavaFX лише відображає готовий результат.
- Етап 5 не додає online revision/update semantics і не торкається scope етапу 6.
